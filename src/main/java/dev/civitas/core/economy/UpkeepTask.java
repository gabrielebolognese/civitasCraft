package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityMember;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ClaimCostEngine;
import dev.civitas.core.claim.ClaimService;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.CityRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The daily upkeep sweep, SPEC 4.3 and SPEC 17.3 cases 31 to 33.
 *
 * <p>Runs off the main thread on a timer, charging every city whose {@code upkeep_due} has
 * passed. Three things make it safe to run on a server that has been offline, laggy, or
 * both:
 *
 * <ul>
 *   <li><b>Idempotency.</b> {@code upkeep_due} is read and advanced inside the same
 *       transaction as the charge, so a cycle can only ever be charged once, whatever the
 *       scheduler does (SPEC 17.3 case 33). A flag also stops two sweeps overlapping.</li>
 *   <li><b>A catch-up cap.</b> A server down for a month does not return and charge thirty
 *       days at once; SPEC 17.3 case 31 caps it and resets the timer.</li>
 *   <li><b>Grace before consequences.</b> A city that cannot pay becomes delinquent and is
 *       warned for the grace period before it starts losing land.</li>
 * </ul>
 */
public final class UpkeepTask implements Runnable {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final CityRegistry cities;
    private final ClaimService claims;
    private final TreasuryService treasury;
    private final UpkeepCalculator calculator;
    private final Notifier notifier;
    private final Scheduler scheduler;
    private final Logger logger;
    private final ZoneId zone;

    /** Stops a slow sweep from being started again on top of itself. */
    private final AtomicBoolean running = new AtomicBoolean();

    public UpkeepTask(DatabaseManager db, DaoRegistry daos, CityRegistry cities,
                      ClaimService claims, TreasuryService treasury, UpkeepCalculator calculator,
                      Notifier notifier, Scheduler scheduler, Logger logger, ZoneId zone) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public void run() {
        if (!calculator.enabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            logger.fine("Upkeep sweep skipped: the previous one is still running.");
            return;
        }
        try {
            sweep(System.currentTimeMillis());
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "The upkeep sweep failed", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * Charges every city that is due.
     *
     * @return how many cities were processed, for tests and for {@code /ca perf}
     */
    public int sweep(long now) {
        List<City> due = cities.cities().stream()
                .filter(city -> !city.isDeleted())
                .filter(city -> city.upkeepDue() <= now)
                .toList();

        int processed = 0;
        for (City city : due) {
            try {
                processCity(city, now);
                processed++;
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Upkeep failed for city " + city.name(), e);
            }
        }
        return processed;
    }

    /** One city, from "is it due" through to losing land if it still cannot pay. */
    void processCity(City city, long now) {
        int maxCycles = calculator.maxCatchupCycles();
        int cycles = 0;

        while (city.upkeepDue() <= now && cycles < maxCycles) {
            chargeOneCycle(city, now);
            cycles++;
        }

        if (city.upkeepDue() <= now) {
            // SPEC 17.3 case 31: after the cap, forget the rest and restart the clock rather
            // than leaving a backlog that charges again on the next sweep.
            long next = calculator.nextChargeAfter(now, zone);
            logger.info(() -> "City " + city.name() + " missed more than " + maxCycles
                    + " upkeep cycles; charging stopped at the cap and the timer was reset.");
            setUpkeepDue(city, next);
        }

        if (city.isDelinquent()) {
            // SPEC 12.3: units deactivate while the bill goes unpaid. They despawn, their
            // rows survive, and the city gets its army back when it settles up rather than
            // having to buy it again.
            setDefenseActive(city, false);
            handleDelinquency(city, now);
        } else {
            setDefenseActive(city, true);
        }
    }

    /**
     * One day's charge.
     *
     * <p>The whole cycle is one transaction: the due time is re-read, the charge is applied
     * and the due time advanced together. If two sweeps somehow overlap, the second reads
     * the already-advanced time and does nothing (SPEC 17.3 case 33).
     */
    private void chargeOneCycle(City city, long now) {
        BigDecimal amount = amountFor(city);
        long dueAtStart = city.upkeepDue();

        Result<CycleOutcome> result = await(db.transaction(connection -> {
            Optional<CityRow> current = daos.cities().findById(connection, city.id());
            if (current.isEmpty()) {
                return Result.<CycleOutcome>failure("CITY_GONE", "city.unknown");
            }
            CityRow row = current.get();

            if (row.upkeepDue() != dueAtStart || row.upkeepDue() > now) {
                // Somebody else advanced it. Not an error, just nothing left to do.
                return Result.success(new CycleOutcome(false, amount, row.treasury(),
                        row.upkeepDue(), row.delinquentSince()));
            }

            long nextDue = row.upkeepDue() + MILLIS_PER_DAY;
            boolean paid = row.treasury().compareTo(amount) >= 0 || amount.signum() == 0;
            BigDecimal treasuryAfter = row.treasury();
            Long delinquentSince = row.delinquentSince();

            if (paid) {
                if (amount.signum() > 0) {
                    Result<BigDecimal> charged = treasury.adjust(connection, city, amount.negate(),
                            TransactionType.UPKEEP_CHARGE, null, null);
                    if (charged instanceof Result.Failure<BigDecimal> failure) {
                        return Result.<CycleOutcome>propagate(failure);
                    }
                    treasuryAfter = charged.orElseThrow();
                }
                delinquentSince = null;
            } else {
                // A failed charge is still a ledger event: SPEC 4.6 gives it its own type so
                // an admin can see exactly when a city stopped being able to pay.
                daos.ledger().insert(connection, new dev.civitas.storage.row.LedgerRow(
                        0, now, TransactionType.UPKEEP_FAILED.name(), null, null, city.id(),
                        amount.negate(), row.treasury(),
                        "{\"reason\":\"insufficient_treasury\"}"));
                if (delinquentSince == null) {
                    delinquentSince = now;
                }
            }

            daos.cities().update(connection, withUpkeep(row, nextDue, delinquentSince,
                    treasuryAfter));
            return Result.success(new CycleOutcome(paid, amount, treasuryAfter, nextDue,
                    delinquentSince));
        }));

        if (result instanceof Result.Failure<CycleOutcome> failure) {
            logger.warning(() -> "Upkeep cycle for " + city.name() + " failed: " + failure.reason());
            return;
        }

        CycleOutcome outcome = result.orElseThrow();
        scheduler.runOnMain(() -> {
            city.setUpkeepDue(outcome.nextDue());
            city.setTreasury(outcome.treasury());
            city.setDelinquentSince(outcome.delinquentSince());
        });

        if (!outcome.paid()) {
            warnMembers(city, "economy.upkeep.failed", outcome.amount());
        }
    }

    /**
     * What happens once a city has been delinquent longer than the grace period.
     *
     * <p>SPEC 17.3 case 32: warn during grace, then take the outermost chunks, three a day,
     * refunding half of what each cost into the treasury. That refund is often enough to
     * clear the debt by itself, which is why the charge is retried immediately afterwards
     * rather than a day later.
     */
    private void handleDelinquency(City city, long now) {
        long delinquentFor = now - Objects.requireNonNullElse(city.delinquentSince(), now);
        long grace = (long) calculator.gracePeriodDays() * MILLIS_PER_DAY;

        if (delinquentFor < grace) {
            long daysLeft = Math.max(1L, (grace - delinquentFor + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY);
            warnMembers(city, "economy.upkeep.grace",
                    LangManager.placeholder("days", String.valueOf(daysLeft)));
            return;
        }

        if (!calculator.autoUnclaimEnabled()) {
            return;
        }

        int budget = calculator.unclaimsPerDay();
        List<Claim> released = new ArrayList<>();

        // SPEC 39.5: "outposts are released before city chunks, furthest first, because a city
        // should lose its frontier before it loses its home." Each outpost released also cuts
        // the daily bill by its own distance-scaled share, so this is the cheapest debt to shed
        // as well as the least painful.
        int taken = releaseOutposts(city, budget, released);

        // Re-asked after every release, because removing the furthest chunk can make the
        // next-furthest safe to remove when it was not before.
        for (; taken < budget; taken++) {
            List<Claim> candidates = claims.releasableForDebt(city, 1);
            if (candidates.isEmpty()) {
                break;
            }
            Result<Claim> result = await(claims.releaseForDebt(city, candidates.get(0)));
            if (result instanceof Result.Failure<Claim>) {
                break;
            }
            released.add(result.orElseThrow());
        }

        if (released.isEmpty()) {
            // Nothing left that may legally go. The core is never taken (SPEC 17.3 case 32),
            // so a city reduced to its core simply stays in debt.
            warnMembers(city, "economy.upkeep.nothing-to-sell");
            return;
        }

        warnMembers(city, "economy.upkeep.land-sold",
                LangManager.placeholder("count", String.valueOf(released.size())));
        logger.info(() -> "City " + city.name() + " lost " + released.size()
                + " chunks to unpaid upkeep.");

        // The refunds may have cleared the debt; find out now rather than tomorrow.
        retryAfterRefund(city, now);
    }

    /**
     * Releases whole outposts, furthest first, up to the day's budget. SPEC 39.5.
     *
     * <p>A whole outpost at a time rather than a chunk at a time, and each counts once against
     * the budget. An outpost is a place: leaving a city with two chunks of a four-chunk holding
     * a million blocks away would still charge it a distance-scaled bill for a fragment nobody
     * can use, where releasing the whole thing ends the cost and the journey together.
     *
     * @return how much of {@code budget} was spent
     */
    private int releaseOutposts(City city, int budget, List<Claim> released) {
        if (outposts == null) {
            return 0;
        }
        int taken = 0;
        while (taken < budget) {
            List<dev.civitas.core.outpost.Outpost> held =
                    new ArrayList<>(outposts.registry().of(city.id()));
            if (held.isEmpty()) {
                break;
            }
            // Recomputed each time rather than sorted once: releasing one does not move the
            // others, but a list read once and then mutated is how a stale entry gets picked.
            dev.civitas.core.outpost.Outpost furthest = held.stream()
                    .max(java.util.Comparator.comparingDouble(
                            outpost -> outposts.blocksFromCore(city, outpost)))
                    .orElseThrow();

            List<Claim> chunks = outposts.chunksOf(furthest);
            Result<dev.civitas.core.outpost.Outpost> result =
                    await(outposts.releaseForDebt(city, furthest));
            if (result instanceof Result.Failure<dev.civitas.core.outpost.Outpost>) {
                break;
            }
            released.addAll(chunks);
            taken++;
        }
        return taken;
    }

    /** Charges again immediately, in case the refunds covered what was owed. */
    private void retryAfterRefund(City city, long now) {
        BigDecimal amount = amountFor(city);
        if (amount.signum() <= 0 || city.treasury().compareTo(amount) < 0) {
            return;
        }

        Result<BigDecimal> charged = await(db.transaction(connection ->
                treasury.adjust(connection, city, amount.negate(),
                        TransactionType.UPKEEP_CHARGE, null, "{\"reason\":\"after_land_sale\"}")));
        if (charged instanceof Result.Failure<BigDecimal>) {
            return;
        }

        await(db.run(connection -> {
            Optional<CityRow> row = daos.cities().findById(connection, city.id());
            if (row.isEmpty()) {
                return 0;
            }
            return daos.cities().update(connection,
                    withUpkeep(row.get(), row.get().upkeepDue(), null, charged.orElseThrow()));
        }));

        scheduler.runOnMain(() -> {
            city.setTreasury(charged.orElseThrow());
            city.setDelinquentSince(null);
        });
        warnMembers(city, "economy.upkeep.debt-cleared");
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    /**
     * Turns a city's units off while it is in debt, and back on when it is not.
     *
     * <p>Only acts when the answer would change, so a solvent city is not rewritten every
     * sweep and a city in debt does not despawn units it has already despawned.
     */
    private void setDefenseActive(City city, boolean active) {
        if (defense == null || defenseService == null) {
            return;
        }
        boolean anyDiffer = defense.of(city.id()).stream()
                .anyMatch(unit -> unit.active() != active);
        if (anyDiffer) {
            defenseService.setActive(city, active);
        }
    }

    /** What this city owes today. */
    public BigDecimal amountFor(City city) {
        BigDecimal landValue = ClaimCostEngine.landValue(claims.registry().claimsOf(city.id()));
        // Defense units are M12 and read zero until then. Passing every term explicitly keeps
        // the formula honest about what it is not yet counting.
        BigDecimal standard = calculator.dailyUpkeep(landValue, outpostUpkeep(city),
                defenseUpkeep(city), treasuryInterestLevel(city));

        // SPEC 9.4.2's per-city override, applied last. An admin who halved a returning
        // city's upkeep meant half of what it would otherwise owe, including its SPEC 5.7
        // discount and any SPEC 13.5 event multiplier — not half of the raw land value.
        return overrides == null
                ? standard
                : standard.multiply(overrides.multiplierFor(city.id(),
                                System.currentTimeMillis()))
                        .setScale(dev.civitas.storage.SqlDialect.MONEY_SCALE,
                                java.math.RoundingMode.DOWN);
    }

    private dev.civitas.core.admin.UpkeepOverrides overrides;

    /** SPEC 9.4.2's {@code /ca city setupkeep}, wired by M21. */
    public void useOverrides(dev.civitas.core.admin.UpkeepOverrides adminOverrides) {
        this.overrides = adminOverrides;
    }

    /** SPEC 39.5: each outpost costs {@code 1200 * D(d) * chunks} a day, distance included. */
    private BigDecimal outpostUpkeep(City city) {
        return outposts == null ? SqlDialect.zero() : outposts.upkeepFor(city);
    }

    /**
     * Told about outposts once they exist.
     *
     * <p>Set rather than injected because the upkeep sweep is built before the outpost service
     * and a city with no outposts owes nothing extra either way.
     *
     * <p>The service rather than the registry: SPEC 39.5 prices an outpost by how far it is
     * from the city core, which is the cost engine's question and not the registry's.
     */
    public void useOutposts(dev.civitas.core.outpost.OutpostService service) {
        this.outposts = service;
    }

    private dev.civitas.core.outpost.OutpostService outposts;

    /** SPEC 12.2: every standing unit costs its city a flat fee a day. */
    private BigDecimal defenseUpkeep(City city) {
        return defense == null ? SqlDialect.zero() : defense.dailyUpkeep(city.id());
    }

    /** Told about defense units once they exist. */
    public void useDefense(dev.civitas.core.defense.DefenseRegistry registry,
                           dev.civitas.core.defense.DefenseService service) {
        this.defense = registry;
        this.defenseService = service;
    }

    private dev.civitas.core.defense.DefenseRegistry defense;
    private dev.civitas.core.defense.DefenseService defenseService;

    /** SPEC 5.7: Treasury Interest takes a slice off the daily bill. */
    private int treasuryInterestLevel(City city) {
        return upgrades == null ? 0
                : upgrades.levelOf(city, dev.civitas.core.upgrade.UpgradeType.TREASURY_INTEREST);
    }

    /** Told about upgrades once they exist. */
    public void useUpgrades(dev.civitas.core.upgrade.UpgradeService service) {
        this.upgrades = service;
    }

    private dev.civitas.core.upgrade.UpgradeService upgrades;

    private static CityRow withUpkeep(CityRow row, long upkeepDue, Long delinquentSince,
                                      BigDecimal treasury) {
        return new CityRow(row.id(), row.name(), row.displayName(), row.tag(), row.mayorUuid(),
                row.foundedAt(), treasury, row.coreWorld(), row.coreChunkX(), row.coreChunkZ(),
                row.spawnX(), row.spawnY(), row.spawnZ(), row.spawnYaw(), row.spawnPitch(),
                row.openJoin(), row.motd(), upkeepDue, delinquentSince,
                row.warProtectionUntil(), row.frozen(), row.deletedAt());
    }

    private void setUpkeepDue(City city, long due) {
        await(db.run(connection -> {
            Optional<CityRow> row = daos.cities().findById(connection, city.id());
            if (row.isEmpty()) {
                return 0;
            }
            return daos.cities().update(connection,
                    withUpkeep(row.get(), due, row.get().delinquentSince(), row.get().treasury()));
        }));
        scheduler.runOnMain(() -> city.setUpkeepDue(due));
    }

    /** Tells whoever is online. A city nobody is playing gets the message when they return. */
    private void warnMembers(City city, String messageKey,
                             net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra) {
        scheduler.runOnMain(() -> {
            for (CityMember member : city.members()) {
                notifier.tell(member.uuid(), messageKey, extra);
            }
        });
    }

    /**
     * How a member is told what the sweep did to their city.
     *
     * <p>A seam rather than a direct {@code Bukkit.getPlayer}, so the sweep can be run and
     * asserted on without a server, which is the only way SPEC 17.3 cases 31 to 33 get a
     * test at all.
     */
    @FunctionalInterface
    public interface Notifier {

        void tell(java.util.UUID member,
                  String messageKey,
                  net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra);

        /** Sends to the member if they are online, and drops the message otherwise. */
        static Notifier online(LangManager lang) {
            Objects.requireNonNull(lang, "lang");
            return (member, key, extra) -> {
                Player player = Bukkit.getPlayer(member);
                if (player != null) {
                    lang.send(player, key, extra);
                }
            };
        }
    }

    private void warnMembers(City city, String messageKey, BigDecimal amount) {
        warnMembers(city, messageKey, LangManager.placeholder("amount", amount.toPlainString()));
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Upkeep database work failed", e);
        }
    }

    /** The outcome of one charge, so the cache update happens outside the transaction. */
    private record CycleOutcome(
            boolean paid,
            BigDecimal amount,
            BigDecimal treasury,
            long nextDue,
            Long delinquentSince) {
    }
}
