package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.SqlDialect;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * What a city owes each day, SPEC 4.3.
 *
 * <pre>
 *   upkeep = land_value * 0.4%
 *          + outpost upkeep
 *          + defense unit upkeep
 *          reduced by 4% per Treasury Interest level
 * </pre>
 *
 * <p>Outpost upkeep arrives as a figure rather than a count. Part I 7.2 charged every outpost
 * the same 2,000 a day, so a count was enough; SPEC 39.5 scales it by distance and by chunks
 * held, so two outposts of the same city rarely cost the same and only the outpost service can
 * price them. Taking a count here would have meant this class quietly averaging them.</p>
 *
 * <p>Pure arithmetic, so SPEC 18.1's three city sizes can be checked directly.
 *
 * <p>"Land value" is the sum of {@code claims.cost_paid}: what the city actually paid, which
 * is the only value the data model records. Worth knowing when reading SPEC 4.3's worked
 * example of "roughly 22,700 C" for a 100-chunk, 10-member city: that figure only appears if
 * the land is priced at solo rates. A real ten-member city paid the member-divided price
 * (SPEC 6.2), so its land value, and therefore its upkeep, is about 2.6 times lower.
 */
public final class UpkeepCalculator {

    private final ConfigManager configs;

    public UpkeepCalculator(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /**
     * The daily charge.
     *
     * @param landValue             sum of what was paid for every claim
     * @param outpostUpkeep         total daily cost of every outpost, priced by SPEC 39.5
     * @param defenseUpkeep         total daily cost of active defense units (SPEC 12.2)
     * @param treasuryInterestLevel the SPEC 5.7 upgrade level, 0 to 5
     */
    public BigDecimal dailyUpkeep(BigDecimal landValue, BigDecimal outpostUpkeep,
                                  BigDecimal defenseUpkeep, int treasuryInterestLevel) {
        FileConfiguration cities = configs.get(ConfigFile.CITIES);

        double landPercent = cities.getDouble("upkeep.percent-of-land-value-per-day", 0.4);
        BigDecimal fromLand = Money.percentOf(landValue, landPercent);

        BigDecimal gross = fromLand.add(outpostUpkeep.max(SqlDialect.zero())).add(defenseUpkeep);

        double reductionPerLevel = cities.getDouble("upgrades.treasury-interest.effect-per-level", 4.0);
        double reduction = Math.min(100.0, reductionPerLevel * Math.max(0, treasuryInterestLevel));
        BigDecimal discounted = gross.subtract(Money.percentOf(gross, reduction));

        // SPEC 13.5's Double Upkeep applies after the SPEC 5.7 discount, not instead of it: a
        // city that bought cheaper upkeep keeps its discount and pays double the discounted
        // figure, which is what the two features promise when read together.
        return discounted.multiply(eventMultiplier()).max(SqlDialect.zero())
                .setScale(SqlDialect.MONEY_SCALE, RoundingMode.DOWN);
    }

    private dev.civitas.core.events.EventEffects events;

    /** SPEC 13.5 Double Upkeep. */
    public void useEvents(dev.civitas.core.events.EventEffects effects) {
        this.events = effects;
    }

    private BigDecimal eventMultiplier() {
        return events == null ? BigDecimal.ONE : events.upkeepMultiplier();
    }

    /** The charge for a city with no outposts, no defense units and no upgrades. */
    public BigDecimal dailyUpkeep(BigDecimal landValue) {
        return dailyUpkeep(landValue, SqlDialect.zero(), SqlDialect.zero(), 0);
    }

    /** How many whole days a treasury can pay for at this rate, for the SPEC 8.5 runway line. */
    public long daysOfRunway(BigDecimal treasury, BigDecimal dailyUpkeep) {
        if (dailyUpkeep.signum() <= 0) {
            return Long.MAX_VALUE;
        }
        return treasury.divide(dailyUpkeep, 0, RoundingMode.DOWN).longValue();
    }

    /** Milliseconds between charges. Config-driven so a server can run a faster cycle. */
    public long chargeIntervalMillis() {
        return configs.get(ConfigFile.CITIES).getLong("upkeep.charge-interval-hours", 24)
                * 3_600_000L;
    }

    /**
     * The next time upkeep should fall due after {@code from}, at the configured hour.
     *
     * <p>Anchored to a wall-clock hour rather than to "24 hours after the last charge", so
     * every city on the server is charged in the same sweep and a restart cannot walk the
     * charge time around the clock.
     */
    public long nextChargeAfter(long from, java.time.ZoneId zone) {
        int hour = configs.get(ConfigFile.CITIES).getInt("upkeep.charge-hour", 4);
        java.time.ZonedDateTime moment =
                java.time.Instant.ofEpochMilli(from).atZone(zone);
        java.time.ZonedDateTime candidate = moment
                .withHour(Math.floorMod(hour, 24)).withMinute(0).withSecond(0).withNano(0);
        if (!candidate.isAfter(moment)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant().toEpochMilli();
    }

    public boolean enabled() {
        return configs.get(ConfigFile.CITIES).getBoolean("upkeep.enabled", true);
    }

    public int gracePeriodDays() {
        return configs.get(ConfigFile.CITIES).getInt("upkeep.grace-period-days", 3);
    }

    public boolean autoUnclaimEnabled() {
        return configs.get(ConfigFile.CITIES).getBoolean("upkeep.delinquent-auto-unclaim", true);
    }

    public int unclaimsPerDay() {
        return configs.get(ConfigFile.CITIES).getInt("upkeep.delinquent-unclaim-per-day", 3);
    }

    /** SPEC 17.3 case 31: at most this many missed cycles are charged after downtime. */
    public int maxCatchupCycles() {
        return configs.get(ConfigFile.CITIES).getInt("upkeep.max-catchup-cycles", 7);
    }
}
