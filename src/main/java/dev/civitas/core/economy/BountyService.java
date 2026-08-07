package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.BountyDao;
import dev.civitas.storage.row.BountyRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;

/**
 * SPEC 4.7's bounties.
 *
 * <h2>The rule that shapes everything else</h2>
 * A bounty is "claimed by whoever kills the target <b>during an active war</b>", and SPEC 4.7
 * says why in the same sentence: "deliberately, so bounties cannot be used to fund random
 * murder outside of the sanctioned combat window". Without that clause a bounty would be a
 * standing contract to hunt somebody, which is exactly the toxicity SPEC 15 is built to
 * prevent — and it would not even work, since SPEC 5.5 disables PvP outside war anyway.
 *
 * <p>So this service refuses to pay out unless the kill happened inside a live war between the
 * two players' cities. That check is not a formality: it is the whole difference between a
 * bounty and a hit list.
 *
 * <h2>The money leaves immediately</h2>
 * SPEC 4.7: "The money is escrowed immediately." A bounty nobody could afford when it was
 * collected would be a promise the server could not keep, so the placer pays at once and the
 * row is the record that they did. Rows are never deleted, only settled, because SPEC 1.5
 * makes every coin movement auditable and a deleted row is money the ledger says went nowhere.
 */
public final class BountyService {

    private static final long MILLIS_PER_DAY = TimeUnit.DAYS.toMillis(1);

    private final DatabaseManager db;
    private final BountyDao bounties;
    private final EconomyService economy;
    private final ConfigManager configs;
    private final Scheduler scheduler;
    private final Logger logger;

    public BountyService(DatabaseManager db, BountyDao bounties, EconomyService economy,
                         ConfigManager configs, Scheduler scheduler, Logger logger) {
        this.db = Objects.requireNonNull(db, "db");
        this.bounties = Objects.requireNonNull(bounties, "bounties");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // Placing, SPEC 4.7
    // ==================================================================================

    /**
     * Places a bounty and escrows it.
     *
     * <p>The withdrawal and the row go in one transaction, so a bounty either exists and has
     * been paid for, or neither.
     */
    public CompletableFuture<Result<BountyRow>> place(UUID placer, UUID target, BigDecimal amount,
                                                      long now) {
        if (placer.equals(target)) {
            return completed(Result.failure("SELF_BOUNTY", "bounty.self"));
        }
        BigDecimal minimum = minimumAmount();
        if (amount.compareTo(minimum) < 0) {
            return completed(Result.failure("BELOW_MINIMUM", "bounty.too-small",
                    Map.of("min", minimum.toPlainString())));
        }

        BigDecimal staked = Money.floor(amount);
        long expiresAt = now + expiryDays() * MILLIS_PER_DAY;

        return db.transaction(connection -> {
            Result<BigDecimal> paid = economy.withdraw(connection, placer, staked,
                    TransactionType.BOUNTY_PLACE, null,
                    "{\"target\":\"" + target + "\"}");
            if (paid instanceof Result.Failure<BigDecimal> failure) {
                return Result.<BountyRow>propagate(failure);
            }

            BountyRow row = new BountyRow(0, placer, target, staked, now, expiresAt,
                    BountyDao.OPEN, null, null);
            long id = bounties.insert(connection, row);
            return Result.success(new BountyRow(id, placer, target, staked, now, expiresAt,
                    BountyDao.OPEN, null, null));
        });
    }

    // ==================================================================================
    // Claiming, SPEC 4.7
    // ==================================================================================

    /**
     * Pays every open bounty on a victim to their killer.
     *
     * <p>Every open one, not the largest: SPEC 4.7 lets anybody place a bounty on anybody, so
     * a well-hated player may carry several, and the kill satisfies all of them at once.
     *
     * @param duringWar whether the kill happened inside a live war between the two sides;
     *                  false pays nothing, which is SPEC 4.7's whole restriction
     * @return the total paid, which is zero when nothing was owed
     */
    public CompletableFuture<Result<BigDecimal>> claim(UUID killer, UUID victim,
                                                       boolean duringWar, long now) {
        if (!duringWar) {
            return completed(Result.failure("NOT_IN_WAR", "bounty.not-in-war"));
        }
        if (killer.equals(victim)) {
            return completed(Result.failure("SELF_KILL", "bounty.self"));
        }

        return db.transaction(connection -> {
            List<BountyRow> open = bounties.findOpenOnSync(connection, victim);
            BigDecimal total = BigDecimal.ZERO;

            for (BountyRow bounty : open) {
                // A bounty the killer placed themselves pays out normally. SPEC 4.7 names no
                // exception, and refusing it would only teach players to place bounties
                // through a second account.
                if (bounties.settle(connection, bounty.id(), BountyDao.CLAIMED, killer, now) == 0) {
                    // Somebody else settled it between the read and the write.
                    continue;
                }
                Result<BigDecimal> paid = economy.deposit(connection, killer, bounty.amount(),
                        TransactionType.BOUNTY_CLAIM, null,
                        "{\"bounty\":" + bounty.id() + ",\"victim\":\"" + victim + "\"}");
                if (paid instanceof Result.Failure<BigDecimal> failure) {
                    // The whole transaction rolls back, so no bounty is marked claimed and
                    // nothing is paid. Half a payout is worse than none.
                    return Result.<BigDecimal>propagate(failure);
                }
                total = total.add(bounty.amount());
            }

            if (total.signum() == 0) {
                return Result.<BigDecimal>failure("NO_BOUNTY", "bounty.none-on-target");
            }
            return Result.success(total);
        });
    }

    // ==================================================================================
    // Expiry, SPEC 4.7
    // ==================================================================================

    /**
     * SPEC 4.7: "Bounties expire after 30 days and refund."
     *
     * <p>Refunded to the placer, under {@code BOUNTY_REFUND}, so the ledger shows the money
     * making a round trip rather than appearing from nowhere.
     *
     * @return how many were refunded
     */
    public CompletableFuture<Integer> expireDue(long now) {
        try {
            return bounties.findExpired(now)
                    .thenCompose(due -> {
                        if (due.isEmpty()) {
                            return CompletableFuture.completedFuture(0);
                        }
                        return db.transaction(connection -> {
                            int refunded = 0;
                            for (BountyRow bounty : due) {
                                if (bounties.settle(connection, bounty.id(), BountyDao.REFUNDED,
                                        null, now) == 0) {
                                    continue;
                                }
                                Result<BigDecimal> back = economy.deposit(connection,
                                        bounty.placerUuid(), bounty.amount(),
                                        TransactionType.BOUNTY_REFUND, null,
                                        "{\"bounty\":" + bounty.id() + ",\"reason\":\"expired\"}");
                                if (back instanceof Result.Failure<BigDecimal> failure) {
                                    return Result.<Integer>propagate(failure);
                                }
                                refunded++;
                            }
                            return Result.success(refunded);
                        }).thenApply(result -> result instanceof Result.Success<Integer>(
                                Integer count) ? count : 0);
                    })
                    .exceptionally(error -> {
                        logger.log(Level.WARNING, "Could not expire bounties.", error);
                        return 0;
                    });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not expire bounties.", e);
            return CompletableFuture.completedFuture(0);
        }
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    /** Every open bounty, richest first, for {@code /bounty list}. */
    public CompletableFuture<List<BountyRow>> listOpen() {
        return bounties.findAllOpen(listSize());
    }

    /** What is on one player's head right now. */
    public CompletableFuture<BigDecimal> totalOn(UUID target) {
        return bounties.findOpenOn(target).thenApply(rows -> rows.stream()
                .map(BountyRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public BigDecimal minimumAmount() {
        return new BigDecimal(configs.get(ConfigFile.ECONOMY)
                .getString("bounties.minimum", "1000"));
    }

    public long expiryDays() {
        return configs.get(ConfigFile.ECONOMY).getLong("bounties.expiry-days", 30);
    }

    public int listSize() {
        return configs.get(ConfigFile.ECONOMY).getInt("bounties.list-size", 20);
    }

    /** The sweep interval, in minutes. */
    public long expirySweepMinutes() {
        return configs.get(ConfigFile.ECONOMY).getLong("bounties.expiry-sweep-minutes", 60);
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /** Kept so a caller can hop back to the server thread the way every other service does. */
    public Scheduler scheduler() {
        return scheduler;
    }

    /** Exposed for the tests that drive the DAO directly. */
    public BountyDao dao() {
        return bounties;
    }

    /** Unused today; here so a later admin command can settle a bounty by hand. */
    public Result<Integer> settleSync(Connection connection, long id, String state, UUID by,
                                      long now) throws SQLException {
        return Result.success(bounties.settle(connection, id, state, by, now));
    }
}
