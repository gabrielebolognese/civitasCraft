package dev.civitas.core.war;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;

/**
 * Suing for peace, SPEC 8.8.
 *
 * <p>"Sue for Peace, sends a peace offer to the opponent, requires both mayors to accept,
 * forfeits 25% of your wager." It is the mid-war counterpart of SPEC 11.3's decline: a way out
 * that costs something, so that a side losing badly has an alternative to logging off for a
 * week, and so that offering one is not free.
 *
 * <h2>What SPEC does not say, and what this does</h2>
 * SPEC 8.8 does not say whether a peace during ACTIVE still rolls the world back. It must:
 * damage has already been done and logged, and SPEC 1.2 promises it is never permanent. So a
 * peace agreed during ACTIVE goes to {@link WarState#ROLLING_BACK} exactly as a war that ran
 * its course, with no winner. A peace agreed during PREP has nothing to restore and simply
 * cancels. Recorded in OPEN_QUESTIONS.md.
 */
public final class PeaceOffer {

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final CityRegistry cities;
    private final TreasuryService treasury;
    private final ConfigManager configs;
    private final Scheduler scheduler;

    /** War id to the city that has offered peace. One standing offer per war. */
    private final Map<Integer, Integer> offers = new ConcurrentHashMap<>();

    public PeaceOffer(DatabaseManager db, DaoRegistry daos, CityRegistry cities,
                      TreasuryService treasury, ConfigManager configs, Scheduler scheduler) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Puts an offer on the table. Nothing is charged until the other side accepts. */
    public Result<Void> offer(UUID actor, City offering, War war) {
        Result<Void> checked = check(actor, offering, war);
        if (checked instanceof Result.Failure<Void> failure) {
            return Result.propagate(failure);
        }
        if (Integer.valueOf(offering.id()).equals(offers.get(war.id()))) {
            return Result.failure("ALREADY_OFFERED", "war.peace.already-offered");
        }
        offers.put(war.id(), offering.id());
        return Result.ok();
    }

    public Optional<Integer> pendingOffer(int warId) {
        return Optional.ofNullable(offers.get(warId));
    }

    /**
     * Accepts a standing offer from the other side, which ends the war.
     *
     * <p>The forfeit falls on whoever offered. SPEC 8.8 words it from the suing side's point
     * of view — "forfeits 25% of <em>your</em> wager" — and that is what makes an offer a
     * concession rather than a free attempt to stop a fight you are losing.
     */
    public CompletableFuture<Result<War>> accept(UUID actor, City accepting, War war, long now) {
        Result<Void> checked = check(actor, accepting, war);
        if (checked instanceof Result.Failure<Void> failure) {
            return CompletableFuture.completedFuture(Result.propagate(failure));
        }

        Integer offeredBy = offers.get(war.id());
        if (offeredBy == null) {
            return CompletableFuture.completedFuture(
                    Result.failure("NO_OFFER", "war.peace.none"));
        }
        if (offeredBy == accepting.id()) {
            // Accepting your own offer would be a way to end a war unilaterally, which is
            // exactly what "requires both mayors" rules out.
            return CompletableFuture.completedFuture(
                    Result.failure("OWN_OFFER", "war.peace.own-offer"));
        }

        Optional<City> suing = cities.city(offeredBy);
        if (suing.isEmpty()) {
            return CompletableFuture.completedFuture(
                    Result.failure("CITY_GONE", "city.unknown"));
        }

        BigDecimal forfeit = forfeitOf(war);
        boolean duringPrep = war.state() == WarState.PREP;

        return db.transaction(connection -> {
            // Both wagers come back, less the suing side's forfeit, which goes to the other.
            Result<BigDecimal> toSuing = treasury.adjust(connection, suing.get(),
                    war.wager().subtract(forfeit), TransactionType.WAR_WAGER_REFUND, actor,
                    metadata(war));
            if (toSuing instanceof Result.Failure<BigDecimal> failure) {
                return Result.<War>propagate(failure);
            }
            Result<BigDecimal> toAccepting = treasury.adjust(connection, accepting,
                    war.wager().add(forfeit), TransactionType.WAR_WAGER_PAYOUT, actor,
                    metadata(war));
            if (toAccepting instanceof Result.Failure<BigDecimal> failure) {
                return Result.<War>propagate(failure);
            }

            // No winner: a peace is not a victory, so nothing goes on either war record.
            war.winnerCityId(null);
            war.state(duringPrep ? WarState.CANCELLED : WarState.ROLLING_BACK);
            daos.wars().updateState(connection, war.id(), war.state().key());
            return Result.success(war);
        }).thenApply(result -> {
            if (result instanceof Result.Success<War>) {
                scheduler.runOnMain(() -> offers.remove(war.id()));
            }
            return result;
        });
    }

    private Result<Void> check(UUID actor, City city, War war) {
        if (!city.hasPermission(actor, CityPermission.DECLARE_WAR)) {
            return Result.failure("NO_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.DECLARE_WAR.name()));
        }
        if (!war.involves(city.id())) {
            return Result.failure("NOT_IN_WAR", "war.peace.not-in-war");
        }
        if (war.state() != WarState.PREP && war.state() != WarState.ACTIVE) {
            return Result.failure("WRONG_PHASE", "war.peace.wrong-phase");
        }
        return Result.ok();
    }

    /** SPEC 8.8's 25%, on the wager of the side that asked for peace. */
    public BigDecimal forfeitOf(War war) {
        // "peace.forfeit-percent", the name war.yml ships. This read
        // "declaration.peace-forfeit-percent", which is not in the file, so SPEC 8.8's
        // forfeit was permanently 25% whatever the operator set.
        double percent = configs.get(ConfigFile.WAR)
                .getDouble("peace.forfeit-percent", 25);
        return war.wager().multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
    }

    /** Drops a finished war's offer. */
    public void forget(int warId) {
        offers.remove(warId);
    }

    private static String metadata(War war) {
        return "{\"war\":" + war.id() + ",\"result\":\"peace\"}";
    }
}
