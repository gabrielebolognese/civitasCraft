package dev.civitas.core.war;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.util.Result;

/**
 * Deciding a war and paying for it, SPEC 11.9.
 *
 * <h2>What a war costs and what it does not</h2>
 * SPEC 1.2 and SPEC 11.1 both insist the stakes are "money, ranking, and reputation, never
 * blocks". This class is the money and the ranking; M18 is what guarantees the blocks come
 * back. Everything here runs <em>before</em> the rollback starts, so that a war is settled
 * even if the restore afterwards runs into trouble: a player who lost should know they lost
 * rather than waiting on a rollback to finish.
 *
 * <h2>The two seven-day consequences</h2>
 * SPEC 11.9 gives the loser immunity and the winner a market bonus, and is careful about which
 * is which: immunity "is protection, not punishment". A city that just lost a war cannot be
 * declared on again for a week, which is one of SPEC 15.2's named defences against serial
 * harassment.
 */
public final class WarResolution {

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final CityRegistry cities;
    private final TreasuryService treasury;
    private final WarPayouts payouts;
    private final WarRewards rewards;
    private final Logger logger;

    public WarResolution(DatabaseManager db, DaoRegistry daos, CityRegistry cities,
                         TreasuryService treasury, WarPayouts payouts, WarRewards rewards,
                         Logger logger) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.payouts = Objects.requireNonNull(payouts, "payouts");
        this.rewards = Objects.requireNonNull(rewards, "rewards");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** How a war ended, for the announcement and the tests. */
    public record Outcome(War war, Integer winnerCityId, boolean draw,
                          BigDecimal winnerReturn, BigDecimal loserReturn, BigDecimal burned) {

        public boolean isDraw() {
            return draw;
        }
    }

    /**
     * Decides the war, moves the money, and applies the two consequences.
     *
     * <p>All of it in one transaction: a war that paid the winner and then failed to record
     * that it was resolved would pay them again on the next sweep.
     */
    public CompletableFuture<Result<Outcome>> resolve(War war, long now) {
        boolean draw = payouts.isDraw(war.attackerScore(), war.defenderScore());
        boolean attackerWon = war.attackerScore() > war.defenderScore();

        Optional<City> attacker = cities.city(war.attackerCityId());
        Optional<City> defender = cities.city(war.defenderCityId());
        if (attacker.isEmpty() || defender.isEmpty()) {
            // SPEC 17.4 case 39's neighbour: a city that is gone cannot be paid. The war still
            // has to stop being open, so it resolves with no payout rather than hanging.
            logger.warning("War " + war.id() + " resolved with a missing city; no payout made.");
            war.state(WarState.ROLLING_BACK);
            return CompletableFuture.completedFuture(
                    Result.success(new Outcome(war, null, true, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO)));
        }

        WarPayouts.Split split = draw ? payouts.drawn(war.wager()) : payouts.decided(war.wager());
        City winner = draw ? null : (attackerWon ? attacker.get() : defender.get());
        City loser = draw ? null : (attackerWon ? defender.get() : attacker.get());
        war.winnerCityId(winner == null ? null : winner.id());

        return db.transaction(connection -> {
            if (draw) {
                // SPEC 11.9: "Both wagers refunded in full."
                Result<BigDecimal> toAttacker = treasury.adjust(connection, attacker.get(),
                        split.winnerReturn(), TransactionType.WAR_WAGER_REFUND, null,
                        metadata(war, "draw"));
                if (toAttacker instanceof Result.Failure<BigDecimal> failure) {
                    return Result.<Outcome>propagate(failure);
                }
                Result<BigDecimal> toDefender = treasury.adjust(connection, defender.get(),
                        split.loserReturn(), TransactionType.WAR_WAGER_REFUND, null,
                        metadata(war, "draw"));
                if (toDefender instanceof Result.Failure<BigDecimal> failure) {
                    return Result.<Outcome>propagate(failure);
                }
            } else {
                Result<BigDecimal> toWinner = treasury.adjust(connection, winner,
                        split.winnerReturn(), TransactionType.WAR_WAGER_PAYOUT, null,
                        metadata(war, "win"));
                if (toWinner instanceof Result.Failure<BigDecimal> failure) {
                    return Result.<Outcome>propagate(failure);
                }
                if (split.loserReturn().signum() > 0) {
                    Result<BigDecimal> toLoser = treasury.adjust(connection, loser,
                            split.loserReturn(), TransactionType.WAR_WAGER_REFUND, null,
                            metadata(war, "loss"));
                    if (toLoser instanceof Result.Failure<BigDecimal> failure) {
                        return Result.<Outcome>propagate(failure);
                    }
                }
                // The burn moves no money anywhere: SPEC 11.9 deletes it from circulation.
                // It is recorded so an admin auditing the money supply can see where it went.
                if (split.burned().signum() > 0) {
                    daos.ledger().insert(connection, new dev.civitas.storage.row.LedgerRow(
                            0, now, TransactionType.WAR_WAGER_PAYOUT.name(), null, null,
                            loser.id(), split.burned().negate(), loser.treasury(),
                            metadata(war, "burn")));
                }

                // SPEC 11.9's two seven-day consequences.
                long immunityUntil = now + TimeUnit.DAYS.toMillis(payouts.immunityDays());
                daos.cities().updateWarProtection(connection, loser.id(), immunityUntil);
                loser.setWarProtectionUntil(immunityUntil);
            }

            // rollback_completed_at is deliberately left null: SPEC 11.8.2 step 9 makes it the
            // moment the *restore* finished, which is M18's to record and has not happened
            // yet. Writing it here would claim a rollback that has not started.
            daos.wars().update(connection, war.toRow(null, null));
            return Result.success(new Outcome(war, war.winnerCityId(), draw,
                    split.winnerReturn(), split.loserReturn(), split.burned()));
        }).thenApply(result -> {
            if (result instanceof Result.Success<Outcome>(Outcome outcome)) {
                if (!outcome.isDraw() && winner != null) {
                    rewards.grant(winner.id(),
                            now + TimeUnit.DAYS.toMillis(payouts.marketBonusDays()));
                }
                logOutcome(war, outcome);
            }
            return result;
        }).exceptionally(error -> {
            logger.log(Level.SEVERE, "Could not resolve war " + war.id()
                    + "; the wagers are still escrowed and an admin must settle it.", error);
            return Result.failure("RESOLVE_FAILED", "war.resolve-failed");
        });
    }

    private void logOutcome(War war, Outcome outcome) {
        if (outcome.isDraw()) {
            logger.info("War " + war.id() + " ended in a draw at " + war.attackerScore()
                    + " to " + war.defenderScore() + "; both wagers refunded.");
            return;
        }
        logger.info("War " + war.id() + " won by city " + outcome.winnerCityId() + " at "
                + war.attackerScore() + " to " + war.defenderScore() + ". Winner receives "
                + outcome.winnerReturn() + ", loser " + outcome.loserReturn() + ", "
                + outcome.burned() + " destroyed.");
    }

    private static String metadata(War war, String result) {
        return "{\"war\":" + war.id() + ",\"result\":\"" + result + "\"}";
    }
}
