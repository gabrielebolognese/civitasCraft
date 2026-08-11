package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SPEC 21.7's triggers. Pure, so every threshold has a test with no clock and no database.
 *
 * <h2>What this is for, in SPEC's own words</h2>
 *
 * <p>SPEC 21.4 Class C6: "You cannot enumerate future exploits. The circuit breaker is the
 * mitigation for the exploits you have not thought of yet, and it is <b>the single most valuable
 * safety mechanism in this section</b>."
 *
 * <p>Every other defence in Part II names a vector and closes it. This one names none: it watches
 * for money arriving faster than it should and shuts the faucet, whatever the reason turns out to
 * be. That is why the thresholds are all <em>ratios against a recent baseline</em> rather than
 * absolute figures — an absolute figure is a guess about how big the server will be, and a ratio
 * is not.
 *
 * <h2>Sells freeze, everything else keeps running</h2>
 *
 * <p>SPEC 21.7: "Freezing sells rather than the whole economy is deliberate. Players can still buy,
 * trade with each other, claim, and play. Only the money faucet closes. A server that halts
 * entirely because of a suspected exploit does more damage than the exploit."
 */
public final class CircuitBreaker {

    /** Which rule fired. */
    public enum Trigger {
        /** Server-wide money creation in an hour, against the 7-day hourly average. */
        HOURLY_CREATION,
        /** One player's income in 24h, against their own 30-day daily average. */
        PLAYER_INCOME,
        /** One item's sell volume in an hour, against its 7-day hourly average. */
        ITEM_VOLUME,
        /** Circulation growth week over week, past the warn threshold. */
        INFLATION_WARN,
        /** Circulation growth week over week, past the freeze threshold. */
        INFLATION_FREEZE,
        /** SPEC 17.4 case 73: items existing after a rollback that did not exist before. */
        ITEM_DUPLICATION
    }

    /** What a trip does. SPEC 21.7's table gives a different answer per row. */
    public enum Action {
        /** Console and admins only. */
        WARN,
        /** Close the money faucet server-wide, leaving buying and player trade open. */
        FREEZE_SELLS,
        /** Take one item off the buy list and leave the rest of the market alone. */
        SUSPEND_ITEM,
        /** Put one player past their quota immediately, SPEC 21.5's soft cap. */
        THROTTLE_PLAYER
    }

    /**
     * One firing.
     *
     * @param observed  what was measured
     * @param baseline  what it was measured against
     * @param subject   the player or item the rule is about, empty for the server-wide rules
     */
    public record Trip(Trigger trigger, Action action, BigDecimal observed, BigDecimal baseline,
                       Optional<String> subject, String detail) {

        /** How many times over the baseline, for the message and the audit row. */
        public BigDecimal ratio() {
            if (baseline.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return observed.divide(baseline, 2, RoundingMode.HALF_UP);
        }
    }

    /** SPEC 21.11's thresholds, read live so {@code /ca reload} takes effect. */
    public record Thresholds(
            boolean enabled,
            double hourlyCreationMultiplier,
            double playerIncomeMultiplier,
            double itemVolumeMultiplier,
            double weeklyInflationWarnPercent,
            double weeklyInflationFreezePercent,
            boolean freezeOnTrip) {
    }

    private final Thresholds thresholds;

    public CircuitBreaker(Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    public Thresholds thresholds() {
        return thresholds;
    }

    /**
     * Whether a rule that would freeze sells actually does.
     *
     * <p>SPEC 21.11's {@code action-on-trip: FREEZE_SELLS | WARN_ONLY}. An operator who has set
     * WARN_ONLY still gets every alert, every audit row and every console line — they have chosen
     * to be told rather than protected, which is a real choice on a server whose admins are
     * awake.
     */
    private Action freezeOrWarn() {
        return thresholds.freezeOnTrip() ? Action.FREEZE_SELLS : Action.WARN;
    }

    // ==================================================================================
    // SPEC 21.7's six rows
    // ==================================================================================

    /** Row 1: money created this hour against the seven-day hourly average. */
    public Optional<Trip> checkHourlyCreation(BigDecimal thisHour, BigDecimal sevenDayHourlyMean) {
        if (!thresholds.enabled() || sevenDayHourlyMean.signum() <= 0) {
            // No baseline yet. A new server creates its entire money supply in its first hours,
            // and tripping on that would close the market on day one.
            return Optional.empty();
        }
        BigDecimal limit = sevenDayHourlyMean
                .multiply(BigDecimal.valueOf(thresholds.hourlyCreationMultiplier()));
        if (thisHour.compareTo(limit) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Trip(Trigger.HOURLY_CREATION, freezeOrWarn(), thisHour,
                sevenDayHourlyMean, Optional.empty(),
                "server-wide money creation this hour"));
    }

    /** Row 2: one player's day against their own thirty-day average. */
    public Optional<Trip> checkPlayerIncome(String player, BigDecimal lastDay,
                                            BigDecimal thirtyDayDailyMean) {
        if (!thresholds.enabled() || thirtyDayDailyMean.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal limit = thirtyDayDailyMean
                .multiply(BigDecimal.valueOf(thresholds.playerIncomeMultiplier()));
        if (lastDay.compareTo(limit) <= 0) {
            return Optional.empty();
        }
        // Throttle rather than freeze: this is one account, and closing the whole market because
        // one player had an extraordinary day would punish everybody for somebody else's luck.
        return Optional.of(new Trip(Trigger.PLAYER_INCOME, Action.THROTTLE_PLAYER, lastDay,
                thirtyDayDailyMean, Optional.of(player), "24-hour income"));
    }

    /** Row 3: one item's hour against its own seven-day hourly average. */
    public Optional<Trip> checkItemVolume(String material, BigDecimal thisHour,
                                          BigDecimal sevenDayHourlyMean) {
        if (!thresholds.enabled() || sevenDayHourlyMean.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal limit = sevenDayHourlyMean
                .multiply(BigDecimal.valueOf(thresholds.itemVolumeMultiplier()));
        if (thisHour.compareTo(limit) <= 0) {
            return Optional.empty();
        }
        // SPEC 21.7: "Automatically remove that item from the buy list." One item, because a
        // vector is usually one item — a new dupe, a farm nobody costed — and closing the whole
        // market for it would be the overreaction SPEC 21.7 spends a paragraph rejecting.
        return Optional.of(new Trip(Trigger.ITEM_VOLUME, Action.SUSPEND_ITEM, thisHour,
                sevenDayHourlyMean, Optional.of(material), "sell volume this hour"));
    }

    /**
     * Rows 4 and 5: circulation week over week, at two thresholds.
     *
     * <p>Two rows rather than one because they do different things. SPEC 4.8 already warns at 15%
     * and deliberately does nothing else — "silent balance reduction destroys player trust" — and
     * SPEC 21.7 adds a freeze at 40%, which is growth no legitimate week produces.
     */
    public Optional<Trip> checkInflation(BigDecimal percentGrowth) {
        if (!thresholds.enabled()) {
            return Optional.empty();
        }
        if (percentGrowth.doubleValue() > thresholds.weeklyInflationFreezePercent()) {
            return Optional.of(new Trip(Trigger.INFLATION_FREEZE, freezeOrWarn(), percentGrowth,
                    BigDecimal.valueOf(thresholds.weeklyInflationFreezePercent()),
                    Optional.empty(), "circulation growth week over week"));
        }
        if (percentGrowth.doubleValue() > thresholds.weeklyInflationWarnPercent()) {
            return Optional.of(new Trip(Trigger.INFLATION_WARN, Action.WARN, percentGrowth,
                    BigDecimal.valueOf(thresholds.weeklyInflationWarnPercent()),
                    Optional.empty(), "circulation growth week over week"));
        }
        return Optional.empty();
    }

    /**
     * Row 6, SPEC 17.4 case 73: items that exist after a rollback and did not exist before.
     *
     * <p>SPEC 11.8.3's no-drops rule is the primary defence — "blocks broken in war simply vanish
     * and later reappear" — and this is the check that it worked. Any growth at all is reported;
     * there is no tolerance, because a rollback is not supposed to create a single item.
     */
    public List<Trip> checkItemGrowth(java.util.Map<String, Long> before,
                                      java.util.Map<String, Long> after) {
        List<Trip> trips = new ArrayList<>();
        if (!thresholds.enabled()) {
            return trips;
        }
        for (var entry : after.entrySet()) {
            long was = before.getOrDefault(entry.getKey(), 0L);
            long now = entry.getValue();
            if (now > was) {
                trips.add(new Trip(Trigger.ITEM_DUPLICATION, Action.WARN,
                        BigDecimal.valueOf(now), BigDecimal.valueOf(was),
                        Optional.of(entry.getKey()),
                        "item count after a rollback, +" + (now - was)));
            }
        }
        return trips;
    }
}
