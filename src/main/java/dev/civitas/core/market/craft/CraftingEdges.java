package dev.civitas.core.market.craft;

import java.util.List;

/**
 * The conversions Bukkit's recipe iterator does not hand over, SPEC 21.10.2.
 *
 * <p>SPEC: "Built at startup by walking Bukkit's recipe iterator <b>plus a hardcoded smelting
 * and stonecutter table</b>." The iterator does include furnace and stonecutting recipes on
 * modern Paper, but relying on that alone would make the whole safety property depend on the
 * server handing over a complete recipe list at boot — and if it ever hands over a short one,
 * the market enables with a hole in it and says nothing.
 *
 * <h2>Why SPEC 21.3's pairs are hardcoded too</h2>
 *
 * <p>This goes further than SPEC describes, and deliberately. SPEC 21.3 names twenty-two
 * reversible pairs and the milestone requires a test proving each is detected — but a test
 * cannot get them from {@code Bukkit.recipeIterator()}, because MockBukkit ships no vanilla
 * recipes. Sourcing them only from the runtime iterator would leave the single most important
 * property in this milestone unverifiable in CI and true only by the grace of the server.
 *
 * <p>So the pairs SPEC names are a floor, written down here, and the iterator adds to them.
 * The failure mode that matters is a pair the graph <i>misses</i>, and a hardcoded floor
 * cannot miss the ones the specification lists.
 *
 * <p>The cost is that this table can go stale against a future Minecraft version. That is the
 * safe direction: a stale entry over-reports a relation and refuses to list an item, where a
 * missing entry re-opens SPEC 21.3's infinite money loop.
 */
public final class CraftingEdges {

    private CraftingEdges() {
    }

    /**
     * SPEC 21.3's reversible pairs, verbatim from the specification's list.
     *
     * <p>Every one is a 1:9 or 1:4 block pair: nine ingots make a block and the block makes
     * nine ingots back. These are the recipes the arbitrage in SPEC 21.3's table runs on.
     */
    private static final List<String[]> REVERSIBLE = List.of(
            new String[] {"IRON_INGOT", "IRON_BLOCK"},
            new String[] {"GOLD_INGOT", "GOLD_BLOCK"},
            new String[] {"DIAMOND", "DIAMOND_BLOCK"},
            new String[] {"EMERALD", "EMERALD_BLOCK"},
            new String[] {"LAPIS_LAZULI", "LAPIS_BLOCK"},
            new String[] {"REDSTONE", "REDSTONE_BLOCK"},
            new String[] {"COAL", "COAL_BLOCK"},
            new String[] {"COPPER_INGOT", "COPPER_BLOCK"},
            new String[] {"NETHERITE_INGOT", "NETHERITE_BLOCK"},
            new String[] {"SLIME_BALL", "SLIME_BLOCK"},
            new String[] {"BONE_MEAL", "BONE_BLOCK"},
            new String[] {"WHEAT", "HAY_BLOCK"},
            new String[] {"DRIED_KELP", "DRIED_KELP_BLOCK"},
            new String[] {"SNOWBALL", "SNOW_BLOCK"},
            new String[] {"AMETHYST_SHARD", "AMETHYST_BLOCK"},
            new String[] {"QUARTZ", "QUARTZ_BLOCK"},
            new String[] {"RAW_IRON", "RAW_IRON_BLOCK"},
            new String[] {"RAW_GOLD", "RAW_GOLD_BLOCK"},
            new String[] {"RAW_COPPER", "RAW_COPPER_BLOCK"},
            new String[] {"HONEYCOMB", "HONEYCOMB_BLOCK"},
            new String[] {"GLOWSTONE_DUST", "GLOWSTONE"},
            new String[] {"MELON_SLICE", "MELON"});

    /**
     * Smelting, SPEC 21.10.2's named table.
     *
     * <p>One-way, every one of them: nothing un-smelts. SPEC 21.3 calls these out separately
     * — "every irreversible recipe where both sides are traded (log to planks, sand to glass
     * via smelting, raw ore to ingot via smelting, cane to paper)" — because an irreversible
     * step still moves value between two priced items.
     */
    private static final List<String[]> SMELTING = List.of(
            new String[] {"RAW_IRON", "IRON_INGOT"},
            new String[] {"RAW_GOLD", "GOLD_INGOT"},
            new String[] {"RAW_COPPER", "COPPER_INGOT"},
            new String[] {"IRON_ORE", "IRON_INGOT"},
            new String[] {"GOLD_ORE", "GOLD_INGOT"},
            new String[] {"COPPER_ORE", "COPPER_INGOT"},
            new String[] {"DEEPSLATE_IRON_ORE", "IRON_INGOT"},
            new String[] {"DEEPSLATE_GOLD_ORE", "GOLD_INGOT"},
            new String[] {"DEEPSLATE_COPPER_ORE", "COPPER_INGOT"},
            new String[] {"ANCIENT_DEBRIS", "NETHERITE_SCRAP"},
            new String[] {"SAND", "GLASS"},
            new String[] {"RED_SAND", "GLASS"},
            new String[] {"COBBLESTONE", "STONE"},
            new String[] {"STONE", "SMOOTH_STONE"},
            new String[] {"CLAY_BALL", "BRICK"},
            new String[] {"NETHERRACK", "NETHER_BRICK"},
            new String[] {"KELP", "DRIED_KELP"},
            new String[] {"CACTUS", "GREEN_DYE"},
            new String[] {"SEA_PICKLE", "LIME_DYE"},
            new String[] {"WET_SPONGE", "SPONGE"},
            new String[] {"COBBLED_DEEPSLATE", "DEEPSLATE"},
            new String[] {"BEEF", "COOKED_BEEF"},
            new String[] {"PORKCHOP", "COOKED_PORKCHOP"},
            new String[] {"CHICKEN", "COOKED_CHICKEN"},
            new String[] {"MUTTON", "COOKED_MUTTON"},
            new String[] {"RABBIT", "COOKED_RABBIT"},
            new String[] {"COD", "COOKED_COD"},
            new String[] {"SALMON", "COOKED_SALMON"},
            new String[] {"POTATO", "BAKED_POTATO"});

    /**
     * Stonecutting, SPEC 21.10.2's other named table.
     *
     * <p>The stonecutter is the sharpest case of the flaw, because it converts one-to-one with
     * no loss: a player holding the cheaper side of a stone pair can convert their whole stack
     * with none of the friction a 9:1 recipe imposes.
     */
    private static final List<String[]> STONECUTTING = List.of(
            new String[] {"STONE", "STONE_BRICKS"},
            new String[] {"STONE", "STONE_STAIRS"},
            new String[] {"STONE", "STONE_SLAB"},
            new String[] {"STONE_BRICKS", "CHISELED_STONE_BRICKS"},
            new String[] {"COBBLESTONE", "COBBLESTONE_STAIRS"},
            new String[] {"COBBLESTONE", "COBBLESTONE_SLAB"},
            new String[] {"COBBLESTONE", "COBBLESTONE_WALL"},
            new String[] {"DEEPSLATE", "DEEPSLATE_BRICKS"},
            new String[] {"DEEPSLATE", "POLISHED_DEEPSLATE"},
            new String[] {"SANDSTONE", "SANDSTONE_STAIRS"},
            new String[] {"SANDSTONE", "SANDSTONE_SLAB"},
            new String[] {"SANDSTONE", "CUT_SANDSTONE"},
            new String[] {"QUARTZ_BLOCK", "QUARTZ_STAIRS"},
            new String[] {"QUARTZ_BLOCK", "QUARTZ_SLAB"},
            new String[] {"QUARTZ_BLOCK", "CHISELED_QUARTZ_BLOCK"},
            new String[] {"NETHER_BRICKS", "NETHER_BRICK_STAIRS"},
            new String[] {"BLACKSTONE", "POLISHED_BLACKSTONE"},
            new String[] {"COPPER_BLOCK", "CUT_COPPER"},
            new String[] {"AMETHYST_BLOCK", "AMETHYST_SHARD"});

    /**
     * Crafting conversions worth writing down because SPEC 21.3 names them and the market
     * catalogue touches both sides.
     *
     * <p>One-way. Planks do not craft back into logs, and paper does not craft back into cane.
     */
    private static final List<String[]> CRAFTING = List.of(
            new String[] {"OAK_LOG", "OAK_PLANKS"},
            new String[] {"SPRUCE_LOG", "SPRUCE_PLANKS"},
            new String[] {"BIRCH_LOG", "BIRCH_PLANKS"},
            new String[] {"JUNGLE_LOG", "JUNGLE_PLANKS"},
            new String[] {"ACACIA_LOG", "ACACIA_PLANKS"},
            new String[] {"DARK_OAK_LOG", "DARK_OAK_PLANKS"},
            new String[] {"MANGROVE_LOG", "MANGROVE_PLANKS"},
            new String[] {"CHERRY_LOG", "CHERRY_PLANKS"},
            new String[] {"SUGAR_CANE", "PAPER"},
            new String[] {"BAMBOO", "BAMBOO_PLANKS"},
            new String[] {"WHEAT", "BREAD"},
            new String[] {"COCOA_BEANS", "BROWN_DYE"},
            new String[] {"NETHER_WART", "NETHER_WART_BLOCK"},
            new String[] {"LEATHER", "LEATHER_HORSE_ARMOR"},
            new String[] {"NETHERITE_SCRAP", "NETHERITE_INGOT"},
            new String[] {"IRON_INGOT", "IRON_NUGGET"},
            new String[] {"GOLD_INGOT", "GOLD_NUGGET"},
            new String[] {"IRON_NUGGET", "IRON_INGOT"},
            new String[] {"GOLD_NUGGET", "GOLD_INGOT"});

    /** How many pairs SPEC 21.3 lists, so a test can assert none was dropped in transcription. */
    public static int reversiblePairCount() {
        return REVERSIBLE.size();
    }

    /** SPEC 21.3's pairs, for the test that proves each is detected. */
    public static List<String[]> reversiblePairs() {
        return REVERSIBLE;
    }

    /** Adds every hardcoded conversion to {@code graph}. */
    public static void addAllTo(RecipeGraph graph) {
        for (String[] pair : REVERSIBLE) {
            graph.addReversible(pair[0], pair[1]);
        }
        for (List<String[]> oneWay : List.of(SMELTING, STONECUTTING, CRAFTING)) {
            for (String[] edge : oneWay) {
                graph.addEdge(edge[0], edge[1]);
            }
        }
    }

    /** A graph of the hardcoded tables alone, which is what the tests run against. */
    public static RecipeGraph baseGraph() {
        RecipeGraph graph = new RecipeGraph();
        addAllTo(graph);
        return graph;
    }
}
