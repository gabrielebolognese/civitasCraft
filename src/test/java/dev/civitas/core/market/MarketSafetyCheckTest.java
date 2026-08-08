package dev.civitas.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import dev.civitas.core.market.craft.CraftingEdges;
import dev.civitas.core.market.craft.RecipeGraph;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 21.10.1's startup validation and its refusal to open the market.
 *
 * <p>SPEC: "the market module runs these assertions and <b>refuses to enable</b> (logging
 * SEVERE with the specific failure) if any fails." Two halves worth testing separately — that
 * the assertion catches what SPEC 21.3 describes, and that catching it actually shuts the
 * market rather than logging a warning nobody reads.
 *
 * <p>The last test is the one that will fail on somebody one day: it runs the check against
 * the buy list this plugin actually ships, so adding a tradeable item that shares a recipe
 * with an existing one is a build failure rather than an economy failure.
 */
class MarketSafetyCheckTest {

    /** Collects what was logged, so "logging SEVERE with the specific failure" is assertable. */
    private static final class Captured extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        List<String> at(Level level) {
            return records.stream()
                    .filter(record -> record.getLevel().equals(level))
                    .map(LogRecord::getMessage)
                    .toList();
        }
    }

    private static Logger quietLogger(Captured captured) {
        Logger logger = Logger.getLogger("market-safety-test-" + System.identityHashCode(captured));
        logger.setUseParentHandlers(false);
        logger.addHandler(captured);
        return logger;
    }

    // ==================================================================================
    // The assertion
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 21.10.1's equivalence assertion")
    class Assertion {

        @Test
        @DisplayName("a buy list holding both sides of a recipe fails")
        void bothSidesOfARecipe() {
            // SPEC 21.3's worked example, as a buy list. Listing both is what makes the
            // arbitrage table's profitable columns reachable.
            MarketSafetyCheck check = new MarketSafetyCheck();

            check.checkEquivalenceClasses(List.of("IRON_INGOT", "IRON_BLOCK"),
                    CraftingEdges.baseGraph());

            assertFalse(check.passed());
            assertEquals(1, check.failures().size());
            assertTrue(check.failures().get(0).detail().contains("IRON_INGOT"));
            assertTrue(check.failures().get(0).detail().contains("IRON_BLOCK"));
        }

        @Test
        @DisplayName("and a multi-step pair fails too, not just an adjacent one")
        void multiStepPair() {
            // The case SPEC 21.10.2 singles out. Raw iron and an iron block are two
            // conversions apart, and a direct-edge check would list both happily.
            MarketSafetyCheck check = new MarketSafetyCheck();

            check.checkEquivalenceClasses(List.of("RAW_IRON", "IRON_BLOCK"),
                    CraftingEdges.baseGraph());

            assertFalse(check.passed());
            assertTrue(check.failures().get(0).detail().contains("IRON_INGOT"),
                    "and the message shows the intermediate step, or an operator cannot see "
                            + "why two unrelated-looking items were refused");
        }

        @Test
        @DisplayName("a clean buy list passes")
        void cleanListPasses() {
            MarketSafetyCheck check = new MarketSafetyCheck();

            check.checkEquivalenceClasses(List.of("WHEAT", "DIAMOND", "IRON_INGOT"),
                    CraftingEdges.baseGraph());

            assertTrue(check.passed());
            assertTrue(check.failures().isEmpty());
        }

        @Test
        @DisplayName("every offending pair is reported, not just the first")
        void reportsEveryPair() {
            // An operator who fixes the one pair they were told about and restarts, only to be
            // told about the next one, will do that as many times as there are pairs.
            MarketSafetyCheck check = new MarketSafetyCheck();

            check.checkEquivalenceClasses(
                    List.of("IRON_INGOT", "IRON_BLOCK", "DIAMOND", "DIAMOND_BLOCK"),
                    CraftingEdges.baseGraph());

            assertEquals(2, check.failures().size());
        }

        @Test
        @DisplayName("two items sharing a smelting output are allowed")
        void sharedOutputAllowed() {
            // Raw iron and iron ore both smelt to an ingot and neither converts into the
            // other, so there is no loop and refusing them would cost the market an item for
            // nothing. The check must not be so strict it becomes noise.
            MarketSafetyCheck check = new MarketSafetyCheck();

            check.checkEquivalenceClasses(List.of("RAW_IRON", "IRON_ORE"),
                    CraftingEdges.baseGraph());

            assertTrue(check.passed());
        }

        @Test
        @DisplayName("an empty buy list passes rather than being treated as unchecked")
        void emptyListPasses() {
            MarketSafetyCheck check = new MarketSafetyCheck();

            check.checkEquivalenceClasses(List.of(), CraftingEdges.baseGraph());

            assertTrue(check.passed(), "a market that trades nothing cannot be arbitraged");
        }
    }

    // ==================================================================================
    // The refusal
    // ==================================================================================

    @Nested
    @DisplayName("refusing to enable")
    class Refusal {

        @Test
        @DisplayName("nothing has been checked, so the market is not yet cleared")
        void unrunIsNotPassed() {
            // The default must be "not cleared" rather than "fine": a check that never ran
            // because of a wiring mistake would otherwise open the market silently.
            assertFalse(new MarketSafetyCheck().passed());
        }

        @Test
        @DisplayName("a failure is logged at SEVERE, naming the pair")
        void logsSevere() {
            // SPEC 21.10.1: "logging SEVERE with the specific failure". An operator reading
            // "market disabled" and nothing else has no idea what to change.
            Captured captured = new Captured();
            MarketSafetyCheck check = new MarketSafetyCheck();
            check.checkEquivalenceClasses(List.of("IRON_INGOT", "IRON_BLOCK"),
                    CraftingEdges.baseGraph());

            boolean open = check.report(quietLogger(captured));

            assertFalse(open);
            List<String> severe = captured.at(Level.SEVERE);
            assertFalse(severe.isEmpty(), "nothing was logged at SEVERE");
            assertTrue(severe.stream().anyMatch(line -> line.contains("IRON_BLOCK")),
                    "the log does not name the offending item: " + severe);
            assertTrue(severe.stream().anyMatch(line -> line.contains("21.10.4")),
                    "and does not say that no config key overrides it");
        }

        @Test
        @DisplayName("a pass logs nothing and clears the market")
        void passIsQuiet() {
            Captured captured = new Captured();
            MarketSafetyCheck check = new MarketSafetyCheck();
            check.checkEquivalenceClasses(List.of("WHEAT"), CraftingEdges.baseGraph());

            assertTrue(check.report(quietLogger(captured)));
            assertTrue(captured.at(Level.SEVERE).isEmpty());
        }

        @Test
        @DisplayName("another milestone's assertion can fail into the same latch")
        void otherAssertionsPlugIn() {
            // M6b adds the hard blacklist and the villager-disjointness check. They close the
            // market the same way rather than each inventing a refusal.
            MarketSafetyCheck check = new MarketSafetyCheck();
            check.checkEquivalenceClasses(List.of("WHEAT"), CraftingEdges.baseGraph());
            assertTrue(check.passed());

            check.fail("hard blacklist", "EMERALD is blacklisted and appears in the buy list");

            assertFalse(check.passed());
        }
    }

    // ==================================================================================
    // The list this plugin actually ships
    // ==================================================================================

    @Nested
    @DisplayName("the shipped buy list")
    class Shipped {

        private List<String> shippedBuyList() {
            File file = new File("src/main/resources/economy.yml");
            assertTrue(file.isFile(), "economy.yml is missing");
            ConfigurationSection items = YamlConfiguration.loadConfiguration(file)
                    .getConfigurationSection("market.buy");
            assertTrue(items != null, "economy.yml has no market.buy");
            return List.copyOf(items.getKeys(false));
        }

        @Test
        @DisplayName("passes SPEC 21.10.1's equivalence assertion")
        void shippedListIsSafe() {
            // The test that earns its place over time. SPEC 21.3's flaw is not visible by
            // reading a price table, so the only thing standing between a future edit and an
            // infinite money loop is this assertion failing in CI.
            List<String> buyList = shippedBuyList();
            assertTrue(buyList.size() >= 10, "only found " + buyList.size() + " buyable items");

            MarketSafetyCheck check = new MarketSafetyCheck();
            check.checkEquivalenceClasses(buyList, CraftingEdges.baseGraph());

            assertTrue(check.passed(),
                    "economy.yml's buy list contains both sides of a recipe:\n  "
                            + check.failures().stream().map(Object::toString)
                            .reduce((a, b) -> a + "\n  " + b).orElse(""));
        }

        @Test
        @DisplayName("and the graph actually knows the items it lists")
        void shippedItemsAreInTheGraph() {
            // A buy list of materials the graph has never heard of would pass the check for
            // the wrong reason. Not every tradeable item needs a recipe — carrots have no
            // conversion worth modelling — but most of this list should be reachable.
            RecipeGraph graph = CraftingEdges.baseGraph();
            long known = shippedBuyList().stream().filter(graph::knows).count();

            assertTrue(known >= 3,
                    "the graph knows only " + known + " of the traded materials, so the check "
                            + "is passing because it has no edges rather than because the list "
                            + "is clean");
        }
    }
}
