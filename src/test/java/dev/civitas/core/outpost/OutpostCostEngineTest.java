package dev.civitas.core.outpost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 39.3's formula, checked against SPEC 39.4's <b>published tables</b> rather than against
 * itself.
 *
 * <p>That distinction is the whole point of this class. PLAN.md asks for the engine to be tested
 * against "every value in the 39.4 tables, not just the formula", and the reason is that SPEC
 * 39.3 contains a genuine ambiguity: {@code n} is "the city's TOTAL chunk count including all
 * outpost chunks, exactly as Part I 6.2 computes it", and Part I 6.2 indexes the chunk being
 * claimed. The two readings differ by one chunk and by about 6% in money, and a test written from
 * the formula would pass under either.
 *
 * <p>The tables settle it: {@code n} is the count <b>before</b> the purchase.
 */
class OutpostCostEngineTest {

    @TempDir
    Path directory;

    private ConfigManager configs;
    private OutpostCostEngine costs;

    /** SPEC 39.4 rounds its published figures, so a cell is matched to within a coin or two. */
    private static final BigDecimal TOLERANCE = new BigDecimal("4");

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("outpost-cost-" + System.nanoTime());
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        configs = new ConfigManager(PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        costs = new OutpostCostEngine(configs);
    }

    private void assertNear(String expected, BigDecimal actual, String what) {
        BigDecimal want = new BigDecimal(expected);
        assertTrue(actual.subtract(want).abs().compareTo(TOLERANCE) <= 0,
                what + ": SPEC 39.4 says " + expected + ", the engine says "
                        + actual.toPlainString());
    }

    // ==================================================================================
    // SPEC 39.3's D(d) table
    // ==================================================================================

    @Nested
    @DisplayName("the distance multiplier, SPEC 39.3")
    class Distance {

        @Test
        @DisplayName("every row of SPEC 39.3's own table")
        void publishedMultipliers() {
            assertMultiplier(500, 1.18);
            assertMultiplier(1_000, 1.25);
            assertMultiplier(5_000, 1.56);
            assertMultiplier(15_000, 1.97);
            assertMultiplier(50_000, 2.77);
            assertMultiplier(100_000, 3.50);
            assertMultiplier(500_000, 6.59);
            assertMultiplier(1_000_000, 8.91);
            assertMultiplier(5_000_000, 18.68);
        }

        private void assertMultiplier(double blocks, double expected) {
            double actual = costs.distanceMultiplier(blocks);
            assertTrue(Math.abs(actual - expected) < 0.01,
                    "at " + blocks + " blocks SPEC 39.3 says " + expected + "x, got " + actual);
        }

        @Test
        @DisplayName("it keeps rising forever, which is the argument for a square root")
        void neverFlattens() {
            // SPEC 39.3 rejects a logarithm because it "flattens so hard that distance stops
            // mattering past about fifty thousand blocks".
            assertTrue(costs.distanceMultiplier(2_000_000)
                    > costs.distanceMultiplier(1_000_000));
            assertTrue(costs.distanceMultiplier(30_000_000)
                    > costs.distanceMultiplier(5_000_000));
        }

        @Test
        @DisplayName("and rises more slowly per block as it goes, which is the other half")
        void decelerates() {
            // Ten times the distance is nowhere near ten times the premium, which is what
            // makes the frontier reachable at all.
            double atTenK = costs.distanceMultiplier(10_000) - 1;
            double atHundredK = costs.distanceMultiplier(100_000) - 1;

            assertTrue(atHundredK < atTenK * 10,
                    "the premium scaled linearly, which SPEC 39.3 rejects outright");
        }

        @Test
        @DisplayName("at the core there is no premium at all")
        void atZero() {
            assertEquals(1.0, costs.distanceMultiplier(0));
            assertEquals(1.0, costs.distanceMultiplier(-500), "a negative reads as zero");
        }

        @Test
        @DisplayName("SPEC 39.14 case 144: the vanilla border is priced, not refused")
        void atTheCoordinateLimit() {
            // "At 29,999,984 blocks D(d) is roughly 44x, which is correct and intended."
            double atLimit = costs.distanceMultiplier(29_999_984);

            assertTrue(Math.abs(atLimit - 44.3) < 1.0,
                    "SPEC 39.14 case 144 expects roughly 44x, got " + atLimit);
        }
    }

    // ==================================================================================
    // SPEC 39.4's first table
    // ==================================================================================

    @Nested
    @DisplayName("the founding chunk, every cell of SPEC 39.4's first table")
    class FoundingChunk {

        @Test
        @DisplayName("a 20-chunk city")
        void twentyChunks() {
            assertFounding(20, 1_000, "31721");
            assertFounding(20, 15_000, "49948");
            assertFounding(20, 100_000, "88819");
            assertFounding(20, 1_000_000, "225999");
        }

        @Test
        @DisplayName("a 50-chunk city")
        void fiftyChunks() {
            assertFounding(50, 1_000, "99718");
            assertFounding(50, 15_000, "157016");
            assertFounding(50, 100_000, "279211");
            assertFounding(50, 1_000_000, "710447");
        }

        @Test
        @DisplayName("a 100-chunk city")
        void hundredChunks() {
            assertFounding(100, 1_000, "237171");
            assertFounding(100, 15_000, "373448");
            assertFounding(100, 100_000, "664078");
            assertFounding(100, 1_000_000, "1689737");
        }

        @Test
        @DisplayName("a 200-chunk city")
        void twoHundredChunks() {
            assertFounding(200, 1_000, "564090");
            assertFounding(200, 15_000, "888215");
            assertFounding(200, 100_000, "1579453");
            assertFounding(200, 1_000_000, "4018894");
        }

        @Test
        @DisplayName("a 400-chunk city")
        void fourHundredChunks() {
            assertFounding(400, 1_000, "1341641");
            assertFounding(400, 15_000, "2112543");
            assertFounding(400, 100_000, "3756594");
            assertFounding(400, 1_000_000, "9558594");
        }

        private void assertFounding(int cityChunks, double blocks, String expected) {
            assertNear(expected, costs.chunkCost(cityChunks, 1, blocks, 1),
                    cityChunks + " chunks at " + (long) blocks + " blocks");
        }
    }

    // ==================================================================================
    // SPEC 39.4's second table
    // ==================================================================================

    @Nested
    @DisplayName("a complete four-chunk outpost, every cell of SPEC 39.4's second table")
    class WholeOutpost {

        @Test
        @DisplayName("a 20-chunk city")
        void twentyChunks() {
            assertTotal(20, 1_000, "139625");
            assertTotal(20, 15_000, "219853");
            assertTotal(20, 100_000, "390950");
            assertTotal(20, 1_000_000, "994766");
        }

        @Test
        @DisplayName("a 50-chunk city")
        void fiftyChunks() {
            assertTotal(50, 1_000, "414755");
            assertTotal(50, 15_000, "653072");
            assertTotal(50, 100_000, "1161315");
            assertTotal(50, 1_000_000, "2954947");
        }

        @Test
        @DisplayName("a 100-chunk city")
        void hundredChunks() {
            assertTotal(100, 1_000, "967516");
            assertTotal(100, 15_000, "1523447");
            assertTotal(100, 100_000, "2709044");
            assertTotal(100, 1_000_000, "6893120");
        }

        @Test
        @DisplayName("a 200-chunk city")
        void twoHundredChunks() {
            assertTotal(200, 1_000, "2278724");
            assertTotal(200, 15_000, "3588071");
            assertTotal(200, 100_000, "6380428");
            assertTotal(200, 1_000_000, "16234896");
        }

        @Test
        @DisplayName("a 400-chunk city")
        void fourHundredChunks() {
            assertTotal(400, 1_000, "5393137");
            assertTotal(400, 15_000, "8492015");
            assertTotal(400, 100_000, "15100782");
            assertTotal(400, 1_000_000, "38423699");
        }

        /** The tolerance widens with the figure: SPEC rounds these to the nearest few coins. */
        private void assertTotal(int cityChunks, double blocks, String expected) {
            BigDecimal want = new BigDecimal(expected);
            BigDecimal actual = costs.totalFor(cityChunks, 4, blocks, 1);
            BigDecimal allowed = want.multiply(new BigDecimal("0.0001"))
                    .max(new BigDecimal("10"));

            assertTrue(actual.subtract(want).abs().compareTo(allowed) <= 0,
                    cityChunks + " chunks at " + (long) blocks + " blocks: SPEC 39.4 says "
                            + expected + ", the engine says " + actual.toPlainString());
        }

        @Test
        @DisplayName("the total is not four times the founding price, because n rises each time")
        void nRisesPerChunk() {
            BigDecimal founding = costs.chunkCost(100, 1, 1_000, 1);
            BigDecimal whole = costs.totalFor(100, 4, 1_000, 1);

            assertTrue(whole.compareTo(founding.multiply(new BigDecimal("4"))) != 0,
                    "the chunks were priced independently of each other");
        }
    }

    // ==================================================================================
    // The ambiguity this class exists to pin down
    // ==================================================================================

    @Nested
    @DisplayName("what n means, SPEC 39.3 against SPEC 39.4")
    class TheAmbiguity {

        @Test
        @DisplayName("n is the count BEFORE the purchase, which the tables settle")
        void nExcludesTheChunkBeingBought() {
            // The two readings, side by side. SPEC 39.4 says 31,721 for the first.
            BigDecimal beforeReading = costs.base(20)
                    .multiply(BigDecimal.valueOf(1.25))
                    .multiply(BigDecimal.valueOf(1.5));
            BigDecimal afterReading = costs.base(21)
                    .multiply(BigDecimal.valueOf(1.25))
                    .multiply(BigDecimal.valueOf(1.5));

            assertNear("31721", beforeReading, "n = 20, the count before");
            assertTrue(afterReading.subtract(new BigDecimal("31721")).abs()
                            .compareTo(new BigDecimal("1000")) > 0,
                    "n = 21 should be visibly wrong, or this test proves nothing");
            assertNear("31721", costs.chunkCost(20, 1, 1_000, 1), "and the engine agrees");
        }

        @Test
        @DisplayName("base(n) is the same curve Part I 6.2 charges for city land")
        void sharesTheCityCurve() {
            // SPEC 39.3: outpost chunks count toward n so "expansion is expansion". Part I 6.2's
            // reference table gives 16,918 for chunk 20 and 126,491 for chunk 100.
            assertNear("16918", costs.base(20), "base(20)");
            assertNear("126491", costs.base(100), "base(100)");
        }
    }

    // ==================================================================================
    // SPEC 39.3's F(k)
    // ==================================================================================

    @Nested
    @DisplayName("the chunk factor, SPEC 39.3")
    class ChunkFactor {

        @Test
        @DisplayName("founding is 1.50 and expansion escalates by a quarter")
        void published() {
            assertEquals(1.50, costs.chunkFactor(1));
            assertEquals(1.25, costs.chunkFactor(2));
            assertEquals(1.50, costs.chunkFactor(3));
            assertEquals(1.75, costs.chunkFactor(4));
        }

        @Test
        @DisplayName("the second chunk is the cheapest of the four")
        void secondIsCheapest() {
            // Worth asserting because it looks like a mistake: F(2) is 1.25, below F(1)'s 1.50.
            // SPEC 39.3 intends it — founding a remote holding is a project, adding to one is
            // not, and the escalation only catches up at the third chunk.
            assertTrue(costs.chunkFactor(2) < costs.chunkFactor(1));
            assertEquals(costs.chunkFactor(3), costs.chunkFactor(1));
        }
    }

    // ==================================================================================
    // SPEC 39.5
    // ==================================================================================

    @Nested
    @DisplayName("upkeep and teleport, SPEC 39.5's table")
    class UpkeepAndTravel {

        @Test
        @DisplayName("every row of SPEC 39.5's published table")
        void publishedTable() {
            assertUpkeep(1_000, "1500", "6000", "125");
            assertUpkeep(15_000, "2362", "9448", "197");
            assertUpkeep(100_000, "4200", "16800", "350");
            assertUpkeep(1_000_000, "10687", "42747", "891");
        }

        private void assertUpkeep(double blocks, String perChunk, String fourChunks,
                                  String teleport) {
            assertNear(perChunk, costs.upkeepPerDay(1, blocks),
                    "upkeep per chunk at " + (long) blocks);
            assertNear(fourChunks, costs.upkeepPerDay(4, blocks),
                    "upkeep for four chunks at " + (long) blocks);
            assertNear(teleport, costs.teleportCost(blocks),
                    "teleport at " + (long) blocks);
        }

        @Test
        @DisplayName("SPEC 39.5's calibration: a distant outpost is felt but sustainable")
        void calibration() {
            // "A four-chunk outpost at a million blocks costs 42,747 C per day, which is roughly
            // seventeen percent of a ten-member city's total daily income. Sustainable, and
            // felt." Ten members at the SPEC 21.5 quota earn about 250,000 a day.
            BigDecimal daily = costs.upkeepPerDay(4, 1_000_000);
            double share = daily.doubleValue() / 250_000.0;

            assertTrue(share > 0.15 && share < 0.20,
                    "SPEC 39.5 expects roughly 17% of a ten-member city's income, got "
                            + Math.round(share * 100) + "%");
        }

        @Test
        @DisplayName("zero chunks cost nothing, so a merged-away outpost stops charging")
        void noChunksNoUpkeep() {
            assertEquals(0, costs.upkeepPerDay(0, 100_000).signum());
        }
    }

    // ==================================================================================
    // Configurable, in the M22 shape
    // ==================================================================================

    @Nested
    @DisplayName("configurable")
    class Configurable {

        @Test
        @DisplayName("the distance constant flattens or steepens the curve")
        void distanceConstant() {
            configs.get(ConfigFile.CITIES).set("outposts.cost.distance-constant", 0.0);

            assertEquals(1.0, costs.distanceMultiplier(1_000_000),
                    "zero should switch distance pricing off entirely");
        }

        @Test
        @DisplayName("the founding surcharge and the escalation are both keys")
        void factors() {
            configs.get(ConfigFile.CITIES).set("outposts.cost.founding-surcharge", 1.0);
            configs.get(ConfigFile.CITIES).set("outposts.cost.expansion-escalation", 0.0);

            assertEquals(1.0, costs.chunkFactor(1));
            assertEquals(1.0, costs.chunkFactor(4));
        }

        @Test
        @DisplayName("the member divisor can be switched off for outposts alone")
        void divisorIsOptional() {
            assertTrue(costs.memberDivisor(10) > 1.0);

            configs.get(ConfigFile.CITIES).set("outposts.cost.apply-member-divisor", false);
            assertEquals(1.0, costs.memberDivisor(10));
        }

        @Test
        @DisplayName("upkeep and teleport can each stop scaling with distance")
        void scalingIsOptional() {
            configs.get(ConfigFile.CITIES).set("outposts.upkeep.scales-with-distance", false);
            configs.get(ConfigFile.CITIES).set("outposts.teleport.scales-with-distance", false);

            assertNear("1200", costs.upkeepPerDay(1, 1_000_000), "flat upkeep");
            assertNear("100", costs.teleportCost(1_000_000), "flat teleport");
        }
    }

    // ==================================================================================
    // The member divisor, SPEC 39.4's closing note
    // ==================================================================================

    @Nested
    @DisplayName("the member divisor")
    class Members {

        @Test
        @DisplayName("SPEC 39.4: a fifteen-member city pays the table divided by 3.52")
        void fifteenMembers() {
            BigDecimal solo = costs.chunkCost(100, 1, 100_000, 1);
            BigDecimal fifteen = costs.chunkCost(100, 1, 100_000, 15);
            double ratio = solo.doubleValue() / fifteen.doubleValue();

            assertTrue(Math.abs(ratio - 3.52) < 0.02,
                    "SPEC 39.4 says the divisor is 3.52 at fifteen members, got " + ratio);
        }

        @Test
        @DisplayName("a solo city is never charged more than the table")
        void soloIsTheTable() {
            assertEquals(1.0, costs.memberDivisor(1));
            assertEquals(1.0, costs.memberDivisor(0), "and zero cannot divide by less than one");
        }
    }
}
