package dev.civitas.core.market;

import java.util.Locale;
import java.util.Set;

/**
 * What a villager will hand a player, SPEC 21.10.1's second assertion.
 *
 * <p>"No item in the buy list is obtainable from any villager trade (validated against a
 * hardcoded trade-output list)."
 *
 * <h2>Why a villager trade is a faucet</h2>
 *
 * <p>A villager converts emeralds into goods at a fixed rate and restocks twice a day forever,
 * with no player present for the restock. If the server also buys one of those goods, the loop
 * closes: trade for it, sell it, trade again. Unlike the crafting arbitrage in SPEC 21.3 this
 * does not even need a favourable price swing — the villager's rate is fixed, so the loop
 * either pays or it does not, and if it pays it pays forever.
 *
 * <p>Worse, villager trading is the most automatable mechanic in the game. A trading hall is a
 * standard build, and zombifying and curing a villager drives its prices to one emerald.
 *
 * <h2>Why hardcoded rather than read from the server</h2>
 *
 * <p>SPEC says hardcoded, and it is right to. A villager's trade list is generated per
 * villager when it takes a profession, so there is no server-wide list to read — the plugin
 * would have to enumerate every villager currently loaded and would still miss every
 * profession nobody has spawned yet. The check has to run at startup against what the game
 * <i>can</i> offer, not what some villager happens to be offering.
 *
 * <p>The cost is the same as {@link HardBlacklist}'s: this can go stale against a future
 * Minecraft version. It fails in the safe direction, refusing to buy something that has become
 * untradeable rather than buying something that has become tradeable.
 */
public final class VillagerTrades {

    private VillagerTrades() {
    }

    /**
     * Every item a vanilla villager or wandering trader sells, by profession.
     *
     * <p>Outputs only. What villagers <i>buy</i> is irrelevant here: a villager buying wheat
     * is a sink for wheat, not a source of it.
     */
    private static final Set<String> OUTPUTS = Set.of(
            // Farmer
            "BREAD", "PUMPKIN_PIE", "APPLE", "COOKIE", "CAKE", "SUSPICIOUS_STEW",
            "GOLDEN_CARROT", "GLISTERING_MELON_SLICE",
            // Fisherman
            "COOKED_COD", "COOKED_SALMON", "CAMPFIRE", "FISHING_ROD",
            // Shepherd
            "SHEARS", "WHITE_WOOL", "PAINTING", "WHITE_BED", "WHITE_CARPET", "WHITE_BANNER",
            // Fletcher
            "ARROW", "BOW", "CROSSBOW", "TIPPED_ARROW",
            // Librarian, the profession that makes this assertion matter most
            "BOOKSHELF", "LANTERN", "GLASS", "CLOCK", "COMPASS", "NAME_TAG",
            "ENCHANTED_BOOK", "BOOK", "WRITABLE_BOOK", "INK_SAC",
            // Cartographer
            "MAP", "FILLED_MAP", "ITEM_FRAME", "GLASS_PANE", "WHITE_BANNER_PATTERN",
            // Cleric
            "REDSTONE", "LAPIS_LAZULI", "GLOWSTONE", "ENDER_PEARL", "BOTTLE_O_ENCHANTING",
            "EXPERIENCE_BOTTLE", "ROTTEN_FLESH",
            // Armourer, weaponsmith, toolsmith
            "IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS", "SHIELD",
            "CHAINMAIL_HELMET", "CHAINMAIL_CHESTPLATE", "CHAINMAIL_LEGGINGS",
            "CHAINMAIL_BOOTS", "DIAMOND_HELMET", "DIAMOND_CHESTPLATE", "DIAMOND_LEGGINGS",
            "DIAMOND_BOOTS", "IRON_AXE", "IRON_SWORD", "IRON_SHOVEL", "IRON_PICKAXE",
            "IRON_HOE", "DIAMOND_AXE", "DIAMOND_SWORD", "DIAMOND_SHOVEL", "DIAMOND_PICKAXE",
            "DIAMOND_HOE", "BELL", "FLINT",
            // Butcher
            "COOKED_PORKCHOP", "COOKED_CHICKEN", "COOKED_BEEF", "COOKED_MUTTON",
            "COOKED_RABBIT", "RABBIT_STEW", "DRIED_KELP_BLOCK",
            // Leatherworker
            "LEATHER_HORSE_ARMOR", "SADDLE", "LEATHER_HELMET", "LEATHER_CHESTPLATE",
            "LEATHER_LEGGINGS", "LEATHER_BOOTS",
            // Mason
            "BRICK", "TERRACOTTA", "CHISELED_STONE_BRICKS", "STONE_BRICKS", "POLISHED_ANDESITE",
            "POLISHED_DIORITE", "POLISHED_GRANITE", "QUARTZ_PILLAR", "QUARTZ_BLOCK",
            "WHITE_GLAZED_TERRACOTTA",
            // Wandering trader. Its stock is the widest and the least predictable, which is
            // why several ordinary-looking blocks appear here.
            "SEA_PICKLE", "SLIME_BALL", "GLOWSTONE_DUST", "NAUTILUS_SHELL", "FERN",
            "SUGAR_CANE", "PUMPKIN", "KELP", "CACTUS", "DANDELION", "POPPY", "BLUE_ORCHID",
            "ALLIUM", "RED_SAND", "SAND", "PODZOL", "PACKED_ICE", "BLUE_ICE", "GUNPOWDER",
            "TROPICAL_FISH", "PUFFERFISH", "BRAIN_CORAL_BLOCK", "VINE", "LILY_PAD",
            "SMALL_DRIPLEAF", "BIG_DRIPLEAF", "MOSS_BLOCK", "ROOTED_DIRT");

    /** Whether a villager or wandering trader can produce this material. */
    public static boolean sells(String material) {
        return material != null && OUTPUTS.contains(material.trim().toUpperCase(Locale.ROOT));
    }

    /** The whole list, for tests and diagnostics. */
    public static Set<String> outputs() {
        return OUTPUTS;
    }
}
