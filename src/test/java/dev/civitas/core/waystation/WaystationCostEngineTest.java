package dev.civitas.core.waystation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;

import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.outpost.OutpostCostEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 39.10's waystation pricing.
 *
 * <p>The two multipliers SPEC publishes are the substance. A distance constant that is nearly
 * right — 0.1 against 0.01, or a reference of 100 against 1,000 — produces a curve of the right
 * shape and the wrong numbers, and passes any test written from the formula rather than from
 * SPEC's own figures. Same reasoning as {@code OutpostCostEngineTest}, which asserts every cell
 * of SPEC 39.4's tables for the same reason.
 */
class WaystationCostEngineTest {

    @TempDir
    Path directory;

    private ConfigManager configs;
    private WaystationCostEngine costs;

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("waystation-cost-" + System.nanoTime());
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        configs = new ConfigManager(PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        costs = new WaystationCostEngine(configs);
    }

    @Nested
    @DisplayName("W(d), SPEC 39.10")
    class DistanceCurve {

        @Test
        @DisplayName("SPEC's own two published multipliers")
        void publishedMultipliers() {
            // "At 15,000 blocks the multiplier is 1.39x, at 100,000 it is 2.00x."
            assertEquals(1.39, costs.distanceMultiplier(15_000), 0.005);
            assertEquals(2.00, costs.distanceMultiplier(100_000), 0.005);
        }

        @Test
        @DisplayName("at the world spawn there is no premium at all")
        void noPremiumAtSpawn() {
            assertEquals(1.0, costs.distanceMultiplier(0), 1e-9);
        }

        @Test
        @DisplayName("it keeps rising forever, but ever more slowly")
        void risesForeverAndFlattens() {
            // The square-root property SPEC 39.3 argues for and 39.10 inherits: no cap, but
            // each order of magnitude costs less per block than the last.
            double first = costs.distanceMultiplier(100_000) - costs.distanceMultiplier(10_000);
            double second = costs.distanceMultiplier(1_000_000)
                    - costs.distanceMultiplier(100_000);

            assertTrue(costs.distanceMultiplier(5_000_000)
                    > costs.distanceMultiplier(1_000_000), "never caps");
            assertTrue(second > first, "and each decade still costs more than the last in total");
            assertTrue(second / 900_000 < first / 90_000, "but less per block");
        }

        @Test
        @DisplayName("a negative distance cannot discount a waystation")
        void negativeDistanceIsNotADiscount() {
            assertEquals(1.0, costs.distanceMultiplier(-50_000), 1e-9);
        }

        @Test
        @DisplayName("SPEC 39.10: gentler than an outpost's, deliberately")
        void gentlerThanAnOutpost() {
            // "The resource worlds exist to be travelled deep into, and penalising that would
            // defeat their purpose." 0.10 against 0.25.
            OutpostCostEngine outposts = new OutpostCostEngine(configs);

            assertTrue(costs.distanceMultiplier(100_000)
                    < outposts.distanceMultiplier(100_000));
            assertEquals(3.50, outposts.distanceMultiplier(100_000), 0.005,
                    "an outpost pays 3.50x where a waystation pays 2.00x");
        }
    }

    @Nested
    @DisplayName("what it costs, SPEC 39.10")
    class Prices {

        @Test
        @DisplayName("60,000 and 90,000 at the world spawn")
        void baseCosts() {
            assertEquals(0, costs.chunkCost(1, 0).compareTo(new BigDecimal("60000.00")));
            assertEquals(0, costs.chunkCost(2, 0).compareTo(new BigDecimal("90000.00")));
        }

        @Test
        @DisplayName("both scale by W(d)")
        void bothScaleWithDistance() {
            // 60,000 * 2.00 and 90,000 * 2.00 at a hundred thousand blocks.
            assertEquals(120_000.0, costs.chunkCost(1, 100_000).doubleValue(), 300.0);
            assertEquals(180_000.0, costs.chunkCost(2, 100_000).doubleValue(), 450.0);
        }

        @Test
        @DisplayName("the price does not depend on how large the city is")
        void flatWhoeverBuysIt() {
            // Unlike an outpost, which is priced on 400 * n^1.25 and grows dearer as the city
            // does. A waystation is a facility for reaching the mines, not territory.
            assertEquals(0, costs.chunkCost(1, 5_000)
                    .compareTo(costs.chunkCost(1, 5_000)));
            assertEquals(0, new BigDecimal("60000.00")
                    .compareTo(costs.chunkCost(1, 0)),
                    "no city-size term appears in the signature at all");
        }
    }

    @Nested
    @DisplayName("upkeep and travel, SPEC 39.10")
    class Running {

        @Test
        @DisplayName("1,500 per chunk per day at the spawn, doubling at 100k blocks")
        void upkeep() {
            assertEquals(0, costs.upkeepPerDay(1, 0).compareTo(new BigDecimal("1500.00")));
            assertEquals(0, costs.upkeepPerDay(2, 0).compareTo(new BigDecimal("3000.00")));
            assertEquals(3000.0, costs.upkeepPerDay(1, 100_000).doubleValue(), 10.0);
        }

        @Test
        @DisplayName("the fare is flat, unlike an outpost's")
        void teleportIsFlat() {
            // SPEC 39.10 gives one number and no multiplier. A fee that rose with depth would
            // tax the deep mining these worlds exist for, on every single trip.
            assertEquals(0, costs.teleportCost().compareTo(new BigDecimal("200.00")));
        }

        @Test
        @DisplayName("half of what was paid comes back")
        void refund() {
            assertEquals(0, costs.refundFor(new BigDecimal("60000.00"))
                    .compareTo(new BigDecimal("30000.00")));
        }
    }

    @Nested
    @DisplayName("the limits, SPEC 39.10")
    class Limits {

        @Test
        @DisplayName("one per world, two chunks, and a pool of its own")
        void shippedLimits() {
            assertTrue(costs.enabled());
            assertEquals(1, costs.maxPerWorld());
            assertEquals(2, costs.maxChunks());
        }

        @Test
        @DisplayName("every figure is configurable, per the hard rule on hardcoded numbers")
        void configurable() {
            configs.get(ConfigFile.CITIES).set("waystations.chunk-1-cost", 12345.0);
            configs.get(ConfigFile.CITIES).set("waystations.distance-constant", 0.5);
            configs.get(ConfigFile.CITIES).set("waystations.max-chunks", 4);

            assertEquals(0, costs.chunkCost(1, 0).compareTo(new BigDecimal("12345.00")));
            assertEquals(1.5, costs.distanceMultiplier(1000), 1e-9);
            assertEquals(4, costs.maxChunks());
        }
    }
}
