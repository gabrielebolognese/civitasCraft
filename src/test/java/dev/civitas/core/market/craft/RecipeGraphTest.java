package dev.civitas.core.market.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 21.3's crafting arbitrage, and the graph that makes it impossible by construction.
 *
 * <p>The flaw is worth restating, because it is the reason every assertion here is worth
 * having. The 1.35x spread stops a player wash-trading the <i>same</i> item. It does nothing
 * across a recipe, because the two sides carry independent prices that move independently —
 * so once one side is dumped to its floor, buying nine ingots, crafting a block and selling it
 * pays, and keeps paying until the prices converge. SPEC's own table shows three of four
 * market states containing an infinite money loop, and a player can <b>create</b> the
 * profitable state deliberately by dumping one side first.
 *
 * <p>SPEC 21.10.2's requirement, and the one test that matters most here: "The check is
 * <b>transitive reachability, not direct-edge</b>, so multi-step laundering (A to B to C) is
 * caught."
 */
class RecipeGraphTest {

    // ==================================================================================
    // The property the whole milestone exists for
    // ==================================================================================

    @Nested
    @DisplayName("transitive reachability")
    class Transitivity {

        @Test
        @DisplayName("A to B to C is caught, which a direct-edge check would miss")
        void multiStepLaundering() {
            // SPEC 21.10.2 names this case explicitly. A player who cannot trade A for C in
            // one step but can craft A into B and B into C has exactly the same loop with an
            // extra step in it, and the profit is identical.
            RecipeGraph graph = new RecipeGraph();
            graph.addEdge("A", "B");
            graph.addEdge("B", "C");

            assertTrue(graph.related("A", "C"), "two steps is still a loop");
            assertFalse(graph.directlyFrom("A").contains("C"),
                    "and there is no direct edge, so a direct-edge check would have passed it");
        }

        @Test
        @DisplayName("and so is a chain of five")
        void longChain() {
            RecipeGraph graph = new RecipeGraph();
            for (String step : List.of("AB", "BC", "CD", "DE", "EF")) {
                graph.addEdge(step.substring(0, 1), step.substring(1));
            }

            assertTrue(graph.related("A", "F"));
            assertEquals(List.of("A", "B", "C", "D", "E", "F"), graph.pathBetween("A", "F"));
        }

        @Test
        @DisplayName("a cycle does not hang the search")
        void cyclesTerminate() {
            // Reversible recipes are cycles by construction, and most of the graph is
            // reversible pairs. A naive depth-first walk would not come back.
            RecipeGraph graph = new RecipeGraph();
            graph.addReversible("A", "B");
            graph.addReversible("B", "C");

            assertTrue(graph.related("A", "C"));
            assertTrue(graph.reachableFrom("A").contains("A"), "the cycle returns to itself");
        }
    }

    // ==================================================================================
    // Direction, which is where an over-strict reading would do damage
    // ==================================================================================

    @Nested
    @DisplayName("direction")
    class Direction {

        @Test
        @DisplayName("a one-way recipe still relates its two ends")
        void oneWayIsStillARelation() {
            // SPEC 21.3: "And to every irreversible recipe where both sides are traded (log to
            // planks, sand to glass via smelting, raw ore to ingot via smelting, cane to
            // paper)." Value moves one way, but it still moves.
            RecipeGraph graph = new RecipeGraph();
            graph.addEdge("SAND", "GLASS");

            assertTrue(graph.related("SAND", "GLASS"));
            assertTrue(graph.related("GLASS", "SAND"), "the relation is symmetric even though "
                    + "the recipe is not: SPEC 21.10.2 says either reachable from the other");
        }

        @Test
        @DisplayName("two things that smelt into the same item are NOT related")
        void sharedOutputIsNotARelation() {
            // The case that makes this a directed graph rather than an undirected one. Raw
            // iron and iron ore both smelt to an ingot, and neither converts into the other,
            // so there is no loop between them and both may be listed. Reading the graph as
            // undirected would refuse a safe pair — a false positive that costs the market a
            // tradeable item for no reason.
            RecipeGraph graph = new RecipeGraph();
            graph.addEdge("RAW_IRON", "IRON_INGOT");
            graph.addEdge("IRON_ORE", "IRON_INGOT");

            assertFalse(graph.related("RAW_IRON", "IRON_ORE"));
            assertTrue(graph.related("RAW_IRON", "IRON_INGOT"), "but each relates to the ingot");
        }

        @Test
        @DisplayName("an item is never related to itself")
        void selfIsNotALoop() {
            // One item traded against itself is what the 1.35x spread already covers, and
            // reporting it would make every buy list fail its own check.
            RecipeGraph graph = CraftingEdges.baseGraph();

            assertFalse(graph.related("IRON_INGOT", "IRON_INGOT"));
        }

        @Test
        @DisplayName("a self-edge in the recipe list is ignored rather than accepted")
        void selfEdgesDropped() {
            RecipeGraph graph = new RecipeGraph();
            graph.addEdge("A", "A");

            assertFalse(graph.directlyFrom("A").contains("A"));
        }
    }

    // ==================================================================================
    // SPEC 21.3's list, every pair
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 21.3's reversible pairs")
    class ReversiblePairs {

        @Test
        @DisplayName("every pair the specification lists is detected")
        void allPairsDetected() {
            // The milestone's stated test: "Unit tests proving the reversible pairs in 21.3
            // are detected." Run against the hardcoded tables rather than the server's recipe
            // list, because MockBukkit ships no vanilla recipes — see CraftingEdges for why
            // that makes hardcoding them the conservative choice rather than a shortcut.
            RecipeGraph graph = CraftingEdges.baseGraph();
            List<String> missed = new ArrayList<>();

            for (String[] pair : CraftingEdges.reversiblePairs()) {
                if (!graph.related(pair[0], pair[1])) {
                    missed.add(pair[0] + " <-> " + pair[1]);
                }
            }

            assertTrue(missed.isEmpty(), "SPEC 21.3 pairs the graph does not relate: " + missed);
        }

        @Test
        @DisplayName("all twenty-two of them, so none was dropped in transcription")
        void noPairWasLostCopyingTheList() {
            // SPEC 21.3's block prints 22 pairs. A test over "every pair in the table" passes
            // trivially if the table is short, so the count is asserted separately.
            assertEquals(22, CraftingEdges.reversiblePairCount());
        }

        @Test
        @DisplayName("both directions, because the arbitrage runs either way round")
        void bothDirections() {
            // SPEC 21.3's table has two profitable columns: "buy 9 ingots, craft block, sell"
            // and "buy block, craft 9 ingots, sell". Whichever side is cheap is the side the
            // player starts from.
            RecipeGraph graph = CraftingEdges.baseGraph();

            assertTrue(graph.reaches("IRON_INGOT", "IRON_BLOCK"));
            assertTrue(graph.reaches("IRON_BLOCK", "IRON_INGOT"));
        }

        @Test
        @DisplayName("the worked example from SPEC 21.3 in full")
        void ironIngotAndBlock() {
            RecipeGraph graph = CraftingEdges.baseGraph();

            assertTrue(graph.related("IRON_INGOT", "IRON_BLOCK"),
                    "the pair SPEC's arbitrage table is built on");
            assertEquals(List.of("IRON_INGOT", "IRON_BLOCK"),
                    graph.pathBetween("IRON_INGOT", "IRON_BLOCK"));
        }
    }

    // ==================================================================================
    // Real chains through the hardcoded tables
    // ==================================================================================

    @Nested
    @DisplayName("chains across the vanilla tables")
    class RealChains {

        @Test
        @DisplayName("raw iron reaches an iron block through smelting and then crafting")
        void rawIronToBlock() {
            // Two different tables and two different mechanics, which is the multi-step case
            // in its real form rather than as A to B to C.
            RecipeGraph graph = CraftingEdges.baseGraph();

            assertTrue(graph.related("RAW_IRON", "IRON_BLOCK"));
            assertEquals(List.of("RAW_IRON", "IRON_INGOT", "IRON_BLOCK"),
                    graph.pathBetween("RAW_IRON", "IRON_BLOCK"));
        }

        @Test
        @DisplayName("ancient debris reaches a netherite block")
        void debrisToNetheriteBlock() {
            RecipeGraph graph = CraftingEdges.baseGraph();

            assertTrue(graph.related("ANCIENT_DEBRIS", "NETHERITE_BLOCK"),
                    "debris smelts to scrap, scrap crafts to ingot, ingot crafts to block");
        }

        @Test
        @DisplayName("cobblestone reaches smooth stone and its stonecut forms")
        void stoneChain() {
            RecipeGraph graph = CraftingEdges.baseGraph();

            assertTrue(graph.related("COBBLESTONE", "SMOOTH_STONE"));
            assertTrue(graph.related("COBBLESTONE", "STONE_BRICKS"),
                    "cobblestone smelts to stone, and the stonecutter takes it from there");
        }

        @Test
        @DisplayName("a log reaches its planks, and wheat reaches bread and a hay bale")
        void oneWayCraftingChains() {
            RecipeGraph graph = CraftingEdges.baseGraph();

            assertTrue(graph.related("OAK_LOG", "OAK_PLANKS"));
            assertTrue(graph.related("WHEAT", "BREAD"));
            assertTrue(graph.related("WHEAT", "HAY_BLOCK"));
            assertTrue(graph.related("SUGAR_CANE", "PAPER"));
        }

        @Test
        @DisplayName("unrelated materials stay unrelated")
        void noFalsePositives() {
            // The check refusing everything would be as useless as it accepting everything,
            // and would look like it was working. Wheat and diamonds share no chain.
            RecipeGraph graph = CraftingEdges.baseGraph();

            assertFalse(graph.related("WHEAT", "DIAMOND"));
            assertFalse(graph.related("DIAMOND", "OAK_LOG"));
            assertFalse(graph.related("NETHER_WART", "IRON_INGOT"));
        }
    }

    // ==================================================================================
    // Housekeeping
    // ==================================================================================

    @Nested
    @DisplayName("the graph itself")
    class Housekeeping {

        @Test
        @DisplayName("names are compared case-insensitively")
        void caseInsensitive() {
            // The buy list is operator-written yaml and Bukkit's material names are uppercase.
            // A case mismatch would silently split one item into two nodes, and the pair it
            // was supposed to catch would go unrelated.
            RecipeGraph graph = new RecipeGraph();
            graph.addReversible("iron_ingot", "IRON_BLOCK");

            assertTrue(graph.related("IRON_INGOT", "iron_block"));
            assertTrue(graph.knows(" Iron_Ingot "), "and whitespace is trimmed");
        }

        @Test
        @DisplayName("an unknown material relates to nothing rather than throwing")
        void unknownMaterial() {
            // Buy lists get typos, and a NoSuchElement in the middle of startup validation
            // would be a worse failure than the one being checked for.
            RecipeGraph graph = CraftingEdges.baseGraph();

            assertFalse(graph.related("NOT_A_REAL_ITEM", "IRON_INGOT"));
            assertTrue(graph.reachableFrom("NOT_A_REAL_ITEM").isEmpty());
        }

        @Test
        @DisplayName("pathBetween is empty for an unrelated pair")
        void noPathWhenUnrelated() {
            assertTrue(CraftingEdges.baseGraph().pathBetween("WHEAT", "DIAMOND").isEmpty());
        }

        @Test
        @DisplayName("the base graph is not trivially small")
        void baseGraphIsPopulated() {
            // A table that silently failed to load would make every check pass.
            assertTrue(CraftingEdges.baseGraph().size() > 100,
                    "the hardcoded tables should cover well over a hundred materials");
        }
    }
}
