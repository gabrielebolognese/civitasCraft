package dev.civitas.core.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.SqlDialect;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * The SPEC 6.2 claim cost formula.
 *
 * <pre>
 *   base            = 500                     for the first 8 chunks
 *                   = 400 * index ^ 1.25      after that
 *   distance_mult   = 1 + 0.05 * max(0, chebyshev_from_core - 4)
 *   member_divisor  = 1 + 0.18 * (active_members - 1)
 *   newcomer_mult   = 0.75 while the city is under 14 days old
 *   final           = base * distance_mult * newcomer_mult / member_divisor
 * </pre>
 *
 * <p>Polynomial, not exponential, and that is the whole point. Design pillar 1.1 identifies
 * exponential claim cost as the wall that makes existing plugins unplayable past ~50 chunks;
 * at {@code n^1.25} the five-hundredth chunk costs 1,890 times the first rather than 10^8
 * times it, so a large city is expensive but never impossible.
 *
 * <p>Pure arithmetic with no I/O, so the SPEC 18.1 reference table can be checked directly.
 * Every coefficient is a config key; nothing here is a constant.
 */
public final class ClaimCostEngine {

    private final ConfigManager configs;

    public ClaimCostEngine(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /**
     * The components of a price, so the SPEC 8.4 claims screen can show the breakdown rather
     * than a bare number a player cannot argue with.
     *
     * @param chunkIndex         which chunk this is, 1-based; the 9th chunk has index 9
     * @param base               before any modifier
     * @param distanceMultiplier from the Chebyshev distance to the core
     * @param newcomerMultiplier the young-city discount, or 1.0
     * @param memberDivisor      from the active member count
     * @param total              what the treasury is actually charged
     */
    public record Breakdown(
            int chunkIndex,
            BigDecimal base,
            double distanceMultiplier,
            double newcomerMultiplier,
            double memberDivisor,
            BigDecimal total) {
    }

    /**
     * Prices the next chunk.
     *
     * @param chunkIndex        the number of the chunk being bought, 1-based
     * @param distanceFromCore  Chebyshev distance in chunks from the core chunk
     * @param activeMembers     members who count toward the divisor, see {@link #activeMemberDivisor}
     * @param cityAgeMillis     how old the city is, for the newcomer discount
     */
    public Breakdown price(int chunkIndex, int distanceFromCore, int activeMembers,
                           long cityAgeMillis) {
        FileConfiguration cities = configs.get(ConfigFile.CITIES);

        BigDecimal base = baseCost(chunkIndex, cities);
        double distance = distanceMultiplier(distanceFromCore, cities);
        double newcomer = newcomerMultiplier(cityAgeMillis, cities);
        double divisor = activeMemberDivisor(activeMembers, cities);

        BigDecimal total = base
                .multiply(BigDecimal.valueOf(distance))
                .multiply(BigDecimal.valueOf(newcomer))
                .multiply(eventMultiplier())
                .divide(BigDecimal.valueOf(divisor), SqlDialect.MONEY_SCALE, RoundingMode.HALF_UP);

        return new Breakdown(chunkIndex, base, distance, newcomer, divisor, total);
    }

    /**
     * The SPEC 13.5 Founders' Week discount, once M16 has wired it.
     *
     * <p>Multiplied in alongside the SPEC 6.2 factors rather than applied afterwards, so the
     * breakdown a player is shown before they buy is the price they are actually charged.
     */
    private BigDecimal eventMultiplier() {
        return events == null ? BigDecimal.ONE : events.claimCostMultiplier();
    }

    private dev.civitas.core.events.EventEffects events;

    /** SPEC 13.5 Founders' Week. */
    public void useEvents(dev.civitas.core.events.EventEffects effects) {
        this.events = effects;
    }

    /** Convenience for callers that only need the number. */
    public BigDecimal totalFor(int chunkIndex, int distanceFromCore, int activeMembers,
                               long cityAgeMillis) {
        return price(chunkIndex, distanceFromCore, activeMembers, cityAgeMillis).total();
    }

    /**
     * The flat starter plot, then the polynomial curve.
     *
     * <p>The flat band exists so a brand-new city can lay out a usable footprint before the
     * curve starts to bite; without it the second chunk would already cost four times the
     * first.
     */
    public BigDecimal baseCost(int chunkIndex, FileConfiguration cities) {
        int flatCount = cities.getInt("claims.starter-flat-count", 8);
        if (chunkIndex <= flatCount) {
            return money(cities.getString("claims.starter-flat-cost", "500"));
        }

        double formulaBase = cities.getDouble("claims.formula-base", 400.0);
        double exponent = cities.getDouble("claims.formula-exponent", 1.25);
        return BigDecimal.valueOf(formulaBase * Math.pow(chunkIndex, exponent))
                .setScale(SqlDialect.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Land far from the core costs more, past a free radius.
     *
     * <p>This is what stops a city sprawling a one-chunk-wide tentacle across the map to
     * reach a diamond biome: distance is charged for, so reaching out is a decision.
     */
    public double distanceMultiplier(int distanceFromCore, FileConfiguration cities) {
        double perChunk = cities.getDouble("claims.distance-multiplier-per-chunk", 0.05);
        int freeRadius = cities.getInt("claims.distance-free-radius", 4);
        return 1.0 + perChunk * Math.max(0, distanceFromCore - freeRadius);
    }

    /**
     * Divides the price by the size of the city, SPEC 4.1.
     *
     * <p>The reason recruiting beats hoarding: ten members expand about 2.6 times cheaper per
     * chunk than a solo player, so the cheapest way to grow is to bring people in. A count
     * below one is treated as one, because dividing by zero or by a negative would hand out
     * free or negative land.
     */
    public double activeMemberDivisor(int activeMembers, FileConfiguration cities) {
        double perMember = cities.getDouble("claims.member-divisor-per-member", 0.18);
        return 1.0 + perMember * (Math.max(1, activeMembers) - 1);
    }

    /** The young-city discount, SPEC 15.1. */
    public double newcomerMultiplier(long cityAgeMillis, FileConfiguration cities) {
        long window = cities.getLong("claims.new-city-days", 14) * 86_400_000L;
        if (cityAgeMillis >= window) {
            return 1.0;
        }
        return cities.getDouble("claims.new-city-discount", 0.75);
    }

    /** The refund for giving a chunk back, SPEC 6.4. Always to the treasury, never a player. */
    public BigDecimal refundFor(BigDecimal costPaid) {
        double percent = configs.get(ConfigFile.CITIES)
                .getDouble("claims.unclaim-refund-percent", 50.0);
        return costPaid.multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), SqlDialect.MONEY_SCALE, RoundingMode.DOWN);
    }

    /**
     * Total invested in a city's land, the base the SPEC 4.3 daily upkeep is a percentage of.
     */
    public static BigDecimal landValue(Iterable<Claim> claims) {
        BigDecimal total = SqlDialect.zero();
        for (Claim claim : claims) {
            total = total.add(claim.costPaid());
        }
        return total;
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(SqlDialect.MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
