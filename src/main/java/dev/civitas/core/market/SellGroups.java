package dev.civitas.core.market;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * The builder's catalogue, SPEC 21.6.
 *
 * <p>"The server market is primarily a <b>shop for builders</b>. It sells decorative and
 * building blocks that are tedious or impossible to gather in quantity, in exchange for money…
 * A builder buying 20,000 quartz blocks removes a large amount of money from circulation in
 * one transaction, which is exactly what a sink should do."
 *
 * <h2>Groups, because SPEC 21.6 is a paragraph rather than a table</h2>
 *
 * <p>SPEC lists the catalogue as prose — "all stone and deepslate variants, all wood types and
 * processed wood, terracotta and glazed terracotta, all concrete and powder, all wool and
 * carpet… and every decorative block added in 1.20 and 1.21" — with no per-item prices, only
 * "1.5x to 4x the value of their raw inputs". That is several hundred materials.
 *
 * <p>Hand-enumerating them would mean inventing several hundred prices and would stop covering
 * the catalogue the day Mojang adds a wood type. So {@code economy.yml} prices a small number
 * of <b>groups</b>, and each group resolves here to the materials it contains — from Bukkit's
 * own tags where one exists, and from an explicit list where none does.
 *
 * <p>The consequence worth knowing: a group is priced as a whole, so every stair costs the
 * same whatever it is made of. For a sink that is the right trade — the price is set by what
 * removing money from circulation is worth, not by the raw inputs of one variant — and an
 * operator who wants a specific material priced differently can still name it directly in
 * {@code market.sell} alongside the groups.
 *
 * <h2>Selling is not the risky direction</h2>
 *
 * <p>Nothing here needs the SPEC 21.10.1 assertions that guard the buy list. SPEC 21.6: "every
 * item the server buys is a potential money faucet. Every item the server <i>sells</i> is a
 * money sink and carries no exploit risk at all." Several of these materials are on
 * {@link HardBlacklist} — wool, carpet, flowers, stone — and that is not a contradiction: the
 * server may sell them and may never buy them.
 */
public final class SellGroups {

    private SellGroups() {
    }

    /**
     * Group name as written in {@code economy.yml}, to the materials it covers.
     *
     * <p>Resolved on first use for the reason {@link HardBlacklist} explains: Bukkit's tags
     * come from the running server, and reading them at class-initialisation time makes the
     * class unloadable before one exists.
     */
    private static volatile Map<String, Set<String>> groups;

    /**
     * The groups, cached, and rebuilt only when a server has appeared since.
     *
     * <p>Same balance {@code HardBlacklist.resolve} describes: an empty build means no server
     * was available and must not be sticky, but every build scans the whole {@code Material}
     * enum several times and {@code MarketRegistry} asks once per catalogue entry.
     */
    private static Map<String, Set<String>> groups() {
        Map<String, Set<String>> snapshot = groups;
        if (snapshot != null && (!snapshot.isEmpty() || !serverPresent())) {
            return snapshot;
        }
        synchronized (SellGroups.class) {
            if (groups != null && (!groups.isEmpty() || !serverPresent())) {
                return groups;
            }
            groups = build();
            return groups;
        }
    }

    private static boolean serverPresent() {
        try {
            return org.bukkit.Bukkit.getServer() != null;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Drops the cached groups, so a test can resolve them again once a server exists. */
    public static void reset() {
        synchronized (SellGroups.class) {
            groups = null;
        }
    }

    private static Map<String, Set<String>> build() {
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        if (!serverPresent()) {
            // Nothing below may mention Tag. A class whose static initialiser threw stays
            // unusable for the life of the JVM, so touching it once without a server breaks
            // every later lookup even after one exists. See HardBlacklist.fromBukkit.
            return Map.of();
        }

        // Shapes, which cut across every material and are the bulk of any build.
        put(groups, "stairs", () -> Tag.STAIRS);
        put(groups, "slabs", () -> Tag.SLABS);
        put(groups, "walls", () -> Tag.WALLS);
        put(groups, "fences", () -> Tag.FENCES);

        // Colour, the other half of decorating. Sold, never bought: a wool farm is automatic.
        put(groups, "wool", () -> Tag.WOOL);
        put(groups, "carpet", () -> Tag.WOOL_CARPETS);
        put(groups, "banners", () -> Tag.BANNERS);
        put(groups, "candles", () -> Tag.CANDLES);
        put(groups, "terracotta", () -> Tag.TERRACOTTA);

        // Processed wood. Logs are bought (semi-automatable, quota-bounded); planks, doors and
        // trapdoors are sold, and M6a's graph is why both sides are never bought.
        put(groups, "planks", () -> Tag.PLANKS);
        put(groups, "doors", () -> Tag.DOORS);
        put(groups, "trapdoors", () -> Tag.TRAPDOORS);

        // Categories with no Bukkit tag, named explicitly.
        groups.put("concrete", bySuffix("_CONCRETE"));
        groups.put("concrete-powder", bySuffix("_CONCRETE_POWDER"));
        groups.put("glazed-terracotta", bySuffix("_GLAZED_TERRACOTTA"));
        groups.put("stained-glass", bySuffix("_STAINED_GLASS"));
        groups.put("glass-panes", bySuffix("GLASS_PANE"));
        groups.put("dyes", bySuffix("_DYE"));

        groups.put("stone-variants", named(
                "STONE_BRICKS", "MOSSY_STONE_BRICKS", "CRACKED_STONE_BRICKS",
                "CHISELED_STONE_BRICKS", "SMOOTH_STONE", "ANDESITE", "POLISHED_ANDESITE",
                "DIORITE", "POLISHED_DIORITE", "GRANITE", "POLISHED_GRANITE",
                "COBBLESTONE", "MOSSY_COBBLESTONE", "STONE", "BRICKS", "TUFF",
                "POLISHED_TUFF", "TUFF_BRICKS", "CHISELED_TUFF", "CHISELED_TUFF_BRICKS"));
        groups.put("deepslate-variants", named(
                "DEEPSLATE", "COBBLED_DEEPSLATE", "POLISHED_DEEPSLATE", "DEEPSLATE_BRICKS",
                "CRACKED_DEEPSLATE_BRICKS", "DEEPSLATE_TILES", "CRACKED_DEEPSLATE_TILES",
                "CHISELED_DEEPSLATE", "REINFORCED_DEEPSLATE"));
        groups.put("quartz-variants", named(
                "QUARTZ_BLOCK", "CHISELED_QUARTZ_BLOCK", "QUARTZ_PILLAR", "QUARTZ_BRICKS",
                "SMOOTH_QUARTZ"));
        groups.put("prismarine", named(
                "PRISMARINE", "PRISMARINE_BRICKS", "DARK_PRISMARINE", "SEA_LANTERN"));
        groups.put("purpur", named(
                "PURPUR_BLOCK", "PURPUR_PILLAR", "END_STONE", "END_STONE_BRICKS"));
        groups.put("copper", named(
                "COPPER_BLOCK", "EXPOSED_COPPER", "WEATHERED_COPPER", "OXIDIZED_COPPER",
                "CUT_COPPER", "EXPOSED_CUT_COPPER", "WEATHERED_CUT_COPPER",
                "OXIDIZED_CUT_COPPER", "WAXED_COPPER_BLOCK", "WAXED_EXPOSED_COPPER",
                "WAXED_WEATHERED_COPPER", "WAXED_OXIDIZED_COPPER", "CHISELED_COPPER",
                "COPPER_GRATE", "COPPER_BULB"));
        groups.put("mud-and-mangrove", named(
                "MUD", "PACKED_MUD", "MUD_BRICKS", "MANGROVE_PLANKS", "MANGROVE_ROOTS",
                "MUDDY_MANGROVE_ROOTS"));
        groups.put("amethyst", named("AMETHYST_BLOCK", "BUDDING_AMETHYST"));
        groups.put("nether-building", named(
                "NETHER_BRICKS", "RED_NETHER_BRICKS", "CHISELED_NETHER_BRICKS",
                "CRACKED_NETHER_BRICKS", "BLACKSTONE", "POLISHED_BLACKSTONE",
                "POLISHED_BLACKSTONE_BRICKS", "CHISELED_POLISHED_BLACKSTONE", "BASALT",
                "POLISHED_BASALT", "SMOOTH_BASALT"));
        groups.put("sandstone", named(
                "SANDSTONE", "CHISELED_SANDSTONE", "CUT_SANDSTONE", "SMOOTH_SANDSTONE",
                "RED_SANDSTONE", "CHISELED_RED_SANDSTONE", "CUT_RED_SANDSTONE",
                "SMOOTH_RED_SANDSTONE"));

        groups.values().removeIf(Set::isEmpty);
        return Map.copyOf(groups);
    }

    /** A supplier, for the reason {@code HardBlacklist.addTag} explains. */
    private static void put(Map<String, Set<String>> into, String name,
                            java.util.function.Supplier<Tag<Material>> source) {
        try {
            Tag<Material> tag = source.get();
            if (tag == null) {
                return;
            }
            Set<String> materials = new LinkedHashSet<>();
            tag.getValues().forEach(material -> materials.add(material.name()));
            into.put(name, Set.copyOf(materials));
        } catch (RuntimeException | LinkageError e) {
            // A group that will not resolve simply is not offered. Unlike the blacklist this
            // fails safe on its own: the server sells less, and SPEC 21.6 puts no exploit
            // risk on the selling side at all.
            into.remove(name);
        }
    }

    /**
     * Every material whose name ends this way.
     *
     * <p>Used where no tag exists but the naming is regular — all sixteen concrete colours
     * end in {@code _CONCRETE} — so a seventeenth dye colour would be covered without an edit.
     */
    private static Set<String> bySuffix(String suffix) {
        Set<String> materials = new LinkedHashSet<>();
        try {
            for (Material material : Material.values()) {
                if (!material.isLegacy() && material.name().endsWith(suffix)) {
                    materials.add(material.name());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return Set.of();
        }
        return Set.copyOf(materials);
    }

    private static Set<String> named(String... materials) {
        Set<String> present = new LinkedHashSet<>();
        try {
            for (String name : materials) {
                if (Material.matchMaterial(name) != null) {
                    present.add(name);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return Set.of();
        }
        return Set.copyOf(present);
    }

    /** Whether {@code key} names a group rather than a single material. */
    public static boolean isGroup(String key) {
        return groups().containsKey(key.trim().toLowerCase(Locale.ROOT));
    }

    /** The materials a group covers, or empty if it is not a group. */
    public static Set<String> expand(String key) {
        return groups().getOrDefault(key.trim().toLowerCase(Locale.ROOT), Set.of());
    }

    /** Every group name, for the tests and for a config error message. */
    public static Set<String> names() {
        return groups().keySet();
    }

    /** How many distinct materials the whole catalogue covers. */
    public static int materialCount() {
        Set<String> all = new LinkedHashSet<>();
        groups().values().forEach(all::addAll);
        return all.size();
    }
}
