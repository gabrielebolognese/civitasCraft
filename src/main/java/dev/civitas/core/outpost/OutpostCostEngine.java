package dev.civitas.core.outpost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.SqlDialect;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * SPEC 39.3's outpost cost, and the centrepiece of the SPEC 39 rework.
 *
 * <pre>
 * cost(n, k, d) = base(n) * D(d) * F(k) / member_divisor
 *
 *   base(n) = 400 * n^1.25            n = the city's chunk count, outposts included
 *   D(d)    = 1 + 0.25 * sqrt(d/1000) d = block distance from the core chunk centre
 *   F(k)    = 1.50        if k == 1   the founding chunk of a new outpost
 *           = 1 + 0.25*(k-1) if k > 1 expansion chunks 2, 3, 4
 * </pre>
 *
 * <h2>Why a square root</h2>
 *
 * <p>SPEC 39.3 argues it directly: "Linear distance pricing makes a million-block outpost a
 * hundred times a ten-thousand-block one, which forbids the frontier outright. Logarithmic
 * pricing flattens so hard that distance stops mattering past about fifty thousand blocks. Square
 * root sits between: the premium keeps rising forever, but each additional order of magnitude
 * costs progressively less per block."
 *
 * <h2>The ambiguity in {@code n}, and how it was settled</h2>
 *
 * <p>SPEC 39.3 calls {@code n} "the city's TOTAL chunk count including all outpost chunks,
 * exactly as Part I 6.2 computes it", and Part I 6.2 indexes the chunk <b>being</b> claimed — the
 * ninth chunk has index 9. Those two readings differ by one, and by about 6% in money.
 *
 * <p>Settled against SPEC 39.4's published tables rather than by argument. A twenty-chunk city
 * founding an outpost at 1,000 blocks pays 31,721 in that table, and
 * {@code 400 * 20^1.25 * 1.25 * 1.5} is 31,721 where the {@code n = 21} reading gives 33,712. So
 * {@code n} is the count <b>before</b> this purchase. Verified against four more cells including
 * the four-chunk totals, and {@code OutpostCostEngineTest} asserts every cell of both tables —
 * because a formula that is nearly right passes any test written from the formula.
 */
public final class OutpostCostEngine {

    private final ConfigManager configs;

    public OutpostCostEngine(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    // ==================================================================================
    // The terms
    // ==================================================================================

    /**
     * {@code base(n)}, the same curve Part I 6.2 charges for city land.
     *
     * <p>Deliberately the same: SPEC 39.3 says outpost chunks count toward {@code n} so that
     * "expansion is expansion, and a city that sprawls should feel that in its next purchase
     * regardless of where it sprawled". It also closes an exploit — otherwise a city would buy
     * cheap outpost chunks to keep its city-chunk index down.
     *
     * <p>Note there is no starter-flat band here. SPEC 39.3 writes {@code base(n) = 400*n^1.25}
     * with no exception, and a city that can reach 32 chunks from its core is well past the
     * eight-chunk band anyway.
     */
    public BigDecimal base(int cityChunks) {
        FileConfiguration cities = configs.get(ConfigFile.CITIES);
        double formulaBase = cities.getDouble("claims.formula-base", 400.0);
        double exponent = cities.getDouble("claims.formula-exponent", 1.25);
        return BigDecimal.valueOf(formulaBase
                * Math.pow(Math.max(1, cityChunks), exponent));
    }

    /**
     * {@code D(d)}, the distance premium.
     *
     * <p>Used by the cost, the upkeep and the teleport fee alike, so a distant outpost is a
     * permanent commitment rather than a one-off purchase.
     */
    public double distanceMultiplier(double blocksFromCore) {
        FileConfiguration cities = configs.get(ConfigFile.CITIES);
        double constant = cities.getDouble("outposts.cost.distance-constant", 0.25);
        double reference = cities.getDouble("outposts.cost.distance-reference-blocks", 1000.0);
        if (reference <= 0) {
            return 1.0;
        }
        return 1.0 + constant * Math.sqrt(Math.max(0, blocksFromCore) / reference);
    }

    /**
     * {@code F(k)}, where {@code k} is which chunk of this outpost is being bought.
     *
     * <p>The founding surcharge is 1.50 because "establishing a new remote holding is a project,
     * while adding a chunk to one that already exists is not". Deliberately modest, per SPEC
     * 39.3: the distance multiplier already does the heavy work, and stacking two large
     * multipliers produced numbers no city could reach.
     */
    public double chunkFactor(int chunkNumber) {
        FileConfiguration cities = configs.get(ConfigFile.CITIES);
        if (chunkNumber <= 1) {
            return cities.getDouble("outposts.cost.founding-surcharge", 1.50);
        }
        double escalation = cities.getDouble("outposts.cost.expansion-escalation", 0.25);
        return 1.0 + escalation * (chunkNumber - 1);
    }

    /** The SPEC 6.2 member divisor, unchanged: distant expansion is a group project too. */
    public double memberDivisor(int activeMembers) {
        FileConfiguration cities = configs.get(ConfigFile.CITIES);
        if (!cities.getBoolean("outposts.cost.apply-member-divisor", true)) {
            return 1.0;
        }
        double perMember = cities.getDouble("claims.member-divisor-per-member", 0.18);
        return 1.0 + perMember * (Math.max(1, activeMembers) - 1);
    }

    // ==================================================================================
    // The price
    // ==================================================================================

    /**
     * What one outpost chunk costs.
     *
     * @param cityChunks    the city's chunk count before this purchase, outposts included
     * @param chunkNumber   which chunk of this outpost, 1 for the founding one
     * @param blocksFromCore horizontal distance from the core chunk centre
     * @param activeMembers  members counting toward the SPEC 6.2 divisor
     */
    public BigDecimal chunkCost(int cityChunks, int chunkNumber, double blocksFromCore,
                                int activeMembers) {
        return breakdown(cityChunks, chunkNumber, blocksFromCore, activeMembers).total();
    }

    /**
     * The price with its working shown.
     *
     * <p>Exists because SPEC 39.11 asks for {@code /city outpost cost}: "A formula with four
     * terms is opaque unless the game shows its work, and a player about to spend two million
     * coins deserves to see exactly why."
     */
    public Breakdown breakdown(int cityChunks, int chunkNumber, double blocksFromCore,
                               int activeMembers) {
        BigDecimal base = base(cityChunks);
        double distance = distanceMultiplier(blocksFromCore);
        double factor = chunkFactor(chunkNumber);
        double divisor = memberDivisor(activeMembers);

        BigDecimal total = base
                .multiply(BigDecimal.valueOf(distance))
                .multiply(BigDecimal.valueOf(factor))
                .divide(BigDecimal.valueOf(divisor), SqlDialect.MONEY_SCALE,
                        RoundingMode.HALF_UP);
        return new Breakdown(base, distance, factor, divisor, total);
    }

    /**
     * What a whole outpost of {@code chunks} chunks costs, founded now.
     *
     * <p>Each chunk raises {@code n} for the next, which is why the total is not the founding
     * price times four. SPEC 39.4's second table is this figure.
     */
    public BigDecimal totalFor(int cityChunks, int chunks, double blocksFromCore,
                               int activeMembers) {
        BigDecimal total = BigDecimal.ZERO;
        for (int k = 1; k <= chunks; k++) {
            total = total.add(chunkCost(cityChunks + k - 1, k, blocksFromCore, activeMembers));
        }
        return total.setScale(SqlDialect.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    // ==================================================================================
    // Upkeep and travel, SPEC 39.5
    // ==================================================================================

    /**
     * Daily upkeep for a whole outpost, {@code 1200 * D(d) * chunks}.
     *
     * <p>Scaled by distance for the same reason the price is: "an outpost far away is a permanent
     * commitment rather than a one-time purchase."
     */
    public BigDecimal upkeepPerDay(int chunks, double blocksFromCore) {
        FileConfiguration cities = configs.get(ConfigFile.CITIES);
        double perChunk = cities.getDouble("outposts.upkeep.base-per-chunk-per-day", 1200.0);
        double multiplier = cities.getBoolean("outposts.upkeep.scales-with-distance", true)
                ? distanceMultiplier(blocksFromCore)
                : 1.0;
        return BigDecimal.valueOf(perChunk * multiplier * Math.max(0, chunks))
                .setScale(SqlDialect.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** The teleport fee, {@code 100 * D(d)}. */
    public BigDecimal teleportCost(double blocksFromCore) {
        FileConfiguration cities = configs.get(ConfigFile.CITIES);
        double base = cities.getDouble("outposts.teleport.base-cost", 100.0);
        double multiplier = cities.getBoolean("outposts.teleport.scales-with-distance", true)
                ? distanceMultiplier(blocksFromCore)
                : 1.0;
        return BigDecimal.valueOf(base * multiplier)
                .setScale(SqlDialect.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Half of what was paid, to the treasury.
     *
     * <p>Reads {@code outposts.delete-refund-percent}, the key Part I already ships, rather than
     * SPEC 39.15's {@code unclaim-refund-percent}. Shipping both would be one concept under two
     * names, which is the twin the config sweep found three of; the rename happens when the
     * service that owns the rest of SPEC 39.15's block lands.
     */
    public BigDecimal refundFor(BigDecimal costPaid) {
        int percent = configs.get(ConfigFile.CITIES)
                .getInt("outposts.delete-refund-percent", 50);
        return costPaid.multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), SqlDialect.MONEY_SCALE, RoundingMode.DOWN);
    }

    // ==================================================================================
    // Shapes
    // ==================================================================================

    /**
     * The four terms and the answer, for {@code /city outpost cost}.
     *
     * @param base     {@code base(n)}
     * @param distance {@code D(d)}
     * @param factor   {@code F(k)}
     * @param divisor  the member divisor
     */
    public record Breakdown(BigDecimal base, double distance, double factor, double divisor,
                            BigDecimal total) {
    }
}
