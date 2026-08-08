package dev.civitas.core.waystation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.SqlDialect;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * SPEC 39.10's waystation pricing.
 *
 * <pre>
 * chunk 1   = 60,000 * W(d)
 * chunk 2   = 90,000 * W(d)
 * upkeep    =  1,500 * W(d) per chunk per day
 * teleport  =    200          flat
 *
 *   W(d) = 1 + 0.10 * sqrt(d / 1000)
 *   d    = block distance from THAT WORLD'S SPAWN, not from the city core
 * </pre>
 *
 * <h2>Three deliberate differences from an outpost</h2>
 *
 * <p><b>The anchor.</b> An outpost is priced from the city core (SPEC 39.3). A waystation cannot
 * be: the core is in {@code world} and the waystation is in {@code resource}, so there is no
 * distance between them to measure. SPEC 39.10 anchors it to the resource world's own spawn
 * instead, which is also the point every player arrives at.
 *
 * <p><b>A gentler curve.</b> 0.10 against the outpost's 0.25, which SPEC 39.10 argues for
 * directly: "the resource worlds exist to be travelled deep into, and penalising that would
 * defeat their purpose." At 100,000 blocks a waystation pays 2.00x where an outpost pays 3.50x.
 *
 * <p><b>Flat chunk costs.</b> An outpost is priced on the city's own size, {@code 400 * n^1.25},
 * so it grows dearer as the city does. A waystation is 60,000 and then 90,000 whoever buys it.
 * It is infrastructure for reaching the mines, not territory, and SPEC 39.10 prices it as a
 * facility rather than as land.
 */
public final class WaystationCostEngine {

    private final ConfigManager configs;

    public WaystationCostEngine(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /**
     * {@code W(d) = 1 + 0.10 * sqrt(d / 1000)}.
     *
     * <p>SPEC 39.10 publishes two points on this curve, 1.39x at 15,000 blocks and 2.00x at
     * 100,000, and {@code WaystationCostEngineTest} asserts both — a constant that is nearly
     * right passes any test written from the formula.
     */
    public double distanceMultiplier(double blocksFromSpawn) {
        FileConfiguration cities = configs.get(ConfigFile.CITIES);
        double constant = cities.getDouble("waystations.distance-constant", 0.10);
        double reference = cities.getDouble("waystations.distance-reference-blocks", 1000.0);
        if (reference <= 0) {
            return 1.0;
        }
        return 1.0 + constant * Math.sqrt(Math.max(0.0, blocksFromSpawn) / reference);
    }

    /**
     * What the {@code chunkNumber}-th chunk of a waystation costs.
     *
     * @param chunkNumber 1 for the founding chunk, 2 for the expansion
     */
    public BigDecimal chunkCost(int chunkNumber, double blocksFromSpawn) {
        FileConfiguration cities = configs.get(ConfigFile.CITIES);
        double base = chunkNumber <= 1
                ? cities.getDouble("waystations.chunk-1-cost", 60000.0)
                : cities.getDouble("waystations.chunk-2-cost", 90000.0);
        return BigDecimal.valueOf(base * distanceMultiplier(blocksFromSpawn))
                .setScale(SqlDialect.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Daily upkeep for a whole waystation, {@code 1500 * W(d) * chunks}. */
    public BigDecimal upkeepPerDay(int chunks, double blocksFromSpawn) {
        double perChunk = configs.get(ConfigFile.CITIES)
                .getDouble("waystations.base-upkeep-per-chunk-per-day", 1500.0);
        return BigDecimal.valueOf(perChunk * distanceMultiplier(blocksFromSpawn)
                        * Math.max(0, chunks))
                .setScale(SqlDialect.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The teleport fee, SPEC 39.10.
     *
     * <p>Flat, unlike SPEC 39.5's outpost fare. SPEC 39.10 gives one number and no multiplier,
     * and the reasoning it gives for the gentler distance curve applies with more force here:
     * a fee that rose with depth would tax exactly the deep mining the resource worlds exist
     * for, every single trip.
     */
    public BigDecimal teleportCost() {
        return BigDecimal.valueOf(configs.get(ConfigFile.CITIES)
                        .getDouble("waystations.teleport-cost", 200.0))
                .setScale(SqlDialect.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Half of what was paid, to the treasury, matching an outpost's refund. */
    public BigDecimal refundFor(BigDecimal costPaid) {
        int percent = configs.get(ConfigFile.CITIES)
                .getInt("waystations.refund-percent", 50);
        return costPaid.multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), SqlDialect.MONEY_SCALE, RoundingMode.DOWN);
    }

    // ==================================================================================
    // Limits, SPEC 39.10
    // ==================================================================================

    public boolean enabled() {
        return configs.get(ConfigFile.CITIES).getBoolean("waystations.enabled", true);
    }

    /** One per resource world per city, from a pool separate from the outpost limit. */
    public int maxPerWorld() {
        return configs.get(ConfigFile.CITIES).getInt("waystations.max-per-world-per-city", 1);
    }

    public int maxChunks() {
        return configs.get(ConfigFile.CITIES).getInt("waystations.max-chunks", 2);
    }
}
