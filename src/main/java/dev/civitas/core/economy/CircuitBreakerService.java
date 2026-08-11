package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.CircuitBreaker.Trip;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.dao.LedgerDao;

/**
 * SPEC 21.7's breakers: measuring, tripping, and the latch that stays shut.
 *
 * <h2>The latch is the point</h2>
 *
 * <p>A breaker that reports and keeps serving is a log line. This one <b>closes the money
 * faucet</b> and stays closed until an admin clears it with {@code /ca breaker reset}, because the
 * alternative — resetting itself when the hour ticks over — would reopen the market to whatever
 * tripped it while nobody was awake.
 *
 * <p>What stays open is deliberate and is SPEC 21.7's own reasoning: buying, player-to-player
 * trade, claiming and playing all continue. "A server that halts entirely because of a suspected
 * exploit does more damage than the exploit."
 *
 * <h2>Baselines come from the ledger</h2>
 *
 * <p>Every threshold is a ratio against recent history, and the history is the ledger rather than a
 * counter this class keeps. A counter resets on restart, and a breaker whose baseline resets when
 * the server does is a breaker an exploiter turns off by crashing it.
 */
public final class CircuitBreakerService {

    private static final long HOUR = 60L * 60 * 1000;
    private static final long DAY = 24 * HOUR;

    private final DaoRegistry daos;
    private final MoneySupplyService supply;
    private final ConfigManager configs;
    private final Logger logger;

    /** Set when a freezing rule trips. Cleared only by an admin. */
    private volatile Trip frozenBy;

    /** Items taken off the buy list by SPEC 21.7's row 3, until an admin puts them back. */
    private final Set<String> suspendedItems = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Recent trips, for {@code /ca breaker status}. Bounded; the audit log is the real record. */
    private final Deque<Trip> recent = new ArrayDeque<>();

    public CircuitBreakerService(DaoRegistry daos, MoneySupplyService supply,
                                 ConfigManager configs, Logger logger) {
        this.daos = Objects.requireNonNull(daos, "daos");
        this.supply = Objects.requireNonNull(supply, "supply");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CircuitBreaker breaker() {
        var economy = configs.get(ConfigFile.ECONOMY);
        return new CircuitBreaker(new CircuitBreaker.Thresholds(
                economy.getBoolean("circuit-breaker.enabled", true),
                economy.getDouble("circuit-breaker.hourly-creation-multiplier-trigger", 3.0),
                economy.getDouble("circuit-breaker.player-income-multiplier-trigger", 10.0),
                economy.getDouble("circuit-breaker.item-volume-multiplier-trigger", 20.0),
                economy.getDouble("circuit-breaker.weekly-inflation-warn-percent", 15),
                economy.getDouble("circuit-breaker.weekly-inflation-freeze-percent", 40),
                "FREEZE_SELLS".equalsIgnoreCase(
                        economy.getString("circuit-breaker.action-on-trip", "FREEZE_SELLS"))));
    }

    // ==================================================================================
    // The latch
    // ==================================================================================

    /** Whether the money faucet is shut. Read on every sell. */
    public boolean sellsFrozen() {
        return frozenBy != null;
    }

    public Optional<Trip> frozenBy() {
        return Optional.ofNullable(frozenBy);
    }

    /** SPEC 21.7 row 3: an item taken off the buy list until somebody looks at it. */
    public boolean isSuspended(String material) {
        return material != null && suspendedItems.contains(material.toUpperCase(Locale.ROOT));
    }

    public Set<String> suspendedItems() {
        return Set.copyOf(suspendedItems);
    }

    public List<Trip> recentTrips() {
        return List.copyOf(recent);
    }

    /**
     * SPEC 22.7.1's {@code /ca breaker reset}.
     *
     * <p>Clears the freeze <b>and</b> every suspended item, because an admin who has decided the
     * market is safe has decided it about the whole market: leaving one item suspended after a
     * reset would be a state nobody can see and nobody remembers to fix.
     *
     * @return what it was frozen by, or empty if it was already open
     */
    public Optional<Trip> reset() {
        Optional<Trip> was = frozenBy();
        frozenBy = null;
        suspendedItems.clear();
        recent.clear();
        return was;
    }

    /** Applied by the service that trips, and by the tests. */
    void apply(Trip trip) {
        recent.addLast(trip);
        while (recent.size() > 20) {
            recent.removeFirst();
        }

        switch (trip.action()) {
            case FREEZE_SELLS -> {
                if (frozenBy == null) {
                    frozenBy = trip;
                }
                logger.log(Level.SEVERE, () -> describe(trip)
                        + " — market sells are FROZEN server-wide until /ca breaker reset.");
            }
            case SUSPEND_ITEM -> {
                trip.subject().ifPresent(item ->
                        suspendedItems.add(item.toUpperCase(Locale.ROOT)));
                logger.log(Level.SEVERE, () -> describe(trip)
                        + " — that item has been removed from the buy list.");
            }
            case THROTTLE_PLAYER -> logger.log(Level.WARNING, () -> describe(trip)
                    + " — that player is past their sell quota for the rest of the day.");
            case WARN -> logger.log(Level.WARNING, () -> describe(trip));
        }
        listener.accept(trip);
    }

    private static String describe(Trip trip) {
        return "Circuit breaker " + trip.trigger() + ": " + trip.detail()
                + " was " + trip.observed().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " against a baseline of "
                + trip.baseline().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " (" + trip.ratio().toPlainString() + "x)"
                + trip.subject().map(subject -> " for " + subject).orElse("");
    }

    /**
     * Where a trip goes besides the log.
     *
     * <p>SPEC 21.7: "Every circuit breaker trip writes to `audit_log` and produces an in-game
     * message to online admins and a console message with the full triggering data." The console
     * message is above; the other two are the plugin's to wire, because this class has no server.
     */
    private java.util.function.Consumer<Trip> listener = trip -> { };

    public void onTrip(java.util.function.Consumer<Trip> handler) {
        this.listener = Objects.requireNonNull(handler, "handler");
    }

    // ==================================================================================
    // The hourly sweep
    // ==================================================================================

    /**
     * Runs every rule that can be evaluated from stored history.
     *
     * @return the trips that fired, in the order they were checked
     */
    public CompletableFuture<List<Trip>> sweep(long now) {
        CircuitBreaker rules = breaker();
        if (!rules.thresholds().enabled()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return creationInWindow(now - HOUR, now).thenCompose(thisHour ->
                creationInWindow(now - 7 * DAY, now).thenCompose(week -> {
                    List<Trip> fired = new java.util.ArrayList<>();

                    BigDecimal hourlyMean = week.divide(BigDecimal.valueOf(7 * 24), 2,
                            RoundingMode.HALF_UP);
                    rules.checkHourlyCreation(thisHour, hourlyMean).ifPresent(fired::add);

                    return itemVolumes(now, rules).thenCompose(itemTrips -> {
                        fired.addAll(itemTrips);
                        return inflation(now, rules).thenApply(inflationTrip -> {
                            inflationTrip.ifPresent(fired::add);
                            fired.forEach(this::apply);
                            return List.copyOf(fired);
                        });
                    });
                }));
    }

    /** Money created — credits only — in a window. */
    private CompletableFuture<BigDecimal> creationInWindow(long from, long to) {
        return daos.ledger().flowsBetween(from, to).thenApply(flows -> flows.stream()
                .map(LedgerDao.FlowRow::in)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private CompletableFuture<List<Trip>> itemVolumes(long now, CircuitBreaker rules) {
        return daos.ledger().marketVolumeBetween(now - HOUR, now).thenCompose(thisHour ->
                daos.ledger().marketVolumeBetween(now - 7 * DAY, now).thenApply(week -> {
                    List<Trip> trips = new java.util.ArrayList<>();
                    for (var entry : thisHour.entrySet()) {
                        BigDecimal weekly = week.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                        BigDecimal mean = weekly.divide(BigDecimal.valueOf(7 * 24), 2,
                                RoundingMode.HALF_UP);
                        rules.checkItemVolume(entry.getKey(), entry.getValue(), mean)
                                .ifPresent(trips::add);
                    }
                    return trips;
                }));
    }

    private CompletableFuture<Optional<Trip>> inflation(long now, CircuitBreaker rules) {
        return supply.supplyOver(7, now).thenApply(report ->
                report.percentChange().flatMap(rules::checkInflation));
    }

    /**
     * SPEC 17.4 case 73's post-rollback audit, called by the war system when a restore finishes.
     *
     * <p>Kept as a method the rollback calls rather than a sweep of its own, because the two
     * counts have to be taken either side of one specific event and nothing else knows when that
     * is.
     */
    public List<Trip> auditRollback(java.util.Map<String, Long> before,
                                    java.util.Map<String, Long> after) {
        List<Trip> trips = breaker().checkItemGrowth(before, after);
        trips.forEach(this::apply);
        return trips;
    }

    /**
     * SPEC 22.7.1's {@code /ca market volume [hours]}: sell volume by item against its baseline.
     */
    public record Volume(String material, BigDecimal window, BigDecimal baselineMean,
                         BigDecimal ratio) {
    }

    public CompletableFuture<List<Volume>> volumes(int hours, long now) {
        long window = Math.max(1, hours) * HOUR;
        return daos.ledger().marketVolumeBetween(now - window, now).thenCompose(recentVolume ->
                daos.ledger().marketVolumeBetween(now - 7 * DAY, now).thenApply(week -> {
                    Set<String> materials = new LinkedHashSet<>(recentVolume.keySet());
                    materials.addAll(week.keySet());

                    return materials.stream().map(material -> {
                        BigDecimal inWindow = recentVolume.getOrDefault(material, BigDecimal.ZERO);
                        BigDecimal mean = week.getOrDefault(material, BigDecimal.ZERO)
                                .divide(BigDecimal.valueOf(7 * 24), 2, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(Math.max(1, hours)));
                        BigDecimal ratio = mean.signum() <= 0 ? BigDecimal.ZERO
                                : inWindow.divide(mean, 2, RoundingMode.HALF_UP);
                        return new Volume(material, inWindow, mean, ratio);
                    }).sorted((a, b) -> b.window().compareTo(a.window())).toList();
                }));
    }
}
