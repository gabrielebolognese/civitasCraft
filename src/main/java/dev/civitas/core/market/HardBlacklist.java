package dev.civitas.core.market;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * What the server may never buy, SPEC 21.8.
 *
 * <p>"Never purchasable by the server, at any price, under any config. This list is enforced
 * in code as a hardcoded blacklist that <b>config cannot override</b>, because a well-meaning
 * admin editing a yml is exactly how a server dies."
 *
 * <h2>A note on the word "buy"</h2>
 *
 * <p>SPEC's "buy list" is the list of things the <b>server</b> buys, which is what a
 * <b>player</b> sells. In this codebase {@code MarketService.sell} is the player-facing side
 * of that, and {@code MarketService.buy} is the player buying from the server, which this
 * blacklist does not touch at all. Selling stone to a builder is a money sink and carries no
 * exploit risk; buying stone from a player is a faucet attached to a cobblestone generator.
 *
 * <h2>What SPEC found</h2>
 *
 * <p>SPEC 21.8's own note: "Note what is missing from Part I's list that should have been
 * there: <b>iron and gold</b>, which Part I priced at 45 and 70. Those two entries alone would
 * have ended the server's economy within a week of launch." Both were in the shipped catalogue
 * until this milestone, alongside eleven others on this list.
 *
 * <h2>Tags where they exist, names where they do not</h2>
 *
 * <p>SPEC writes several entries as categories — "all raw and cooked meat", "all eggs", "ice
 * of all kinds", "all fishing loot and all fish", "rails", "carpet". Enumerating those by hand
 * stops protecting the moment Minecraft adds a wood type or a fish, and the whole point of a
 * hardcoded list is that it does not quietly go out of date. So they are read from Bukkit's
 * own tags where a tag exists, exactly as {@code BlockClassifier} does for protection, and
 * from an explicit list only where none does.
 */
public final class HardBlacklist {

    private HardBlacklist() {
    }

    /**
     * Where the tag-backed categories come from.
     *
     * <p>Swappable, so nothing that only wants to check the list needs a running server.
     * Bukkit's {@code Tag} constants resolve through {@code Bukkit.getServer()}, and making
     * that a hard requirement pulled MockBukkit — global static state shared by every test
     * class in the JVM — into a check that is otherwise pure data. That cost this milestone
     * several rounds of tests passing alone and failing together.
     */
    @FunctionalInterface
    public interface Categories {

        /** The materials in a named category, or empty if it cannot be read. */
        Set<String> of(String category);
    }

    /** SPEC 21.8's category entries, by the name this class knows them by. */
    private static final java.util.List<String> CATEGORY_NAMES =
            java.util.List.of("meat", "fish", "carpet", "rails", "ice");

    private static volatile Categories categories = HardBlacklist::fromBukkit;

    /** Replaces the category source. Used by tests so they need no server. */
    public static void useCategories(Categories source) {
        synchronized (HardBlacklist.class) {
            categories = source == null ? HardBlacklist::fromBukkit : source;
            reset();
        }
    }

    /** The default source: Bukkit's own tags, or empty when there is no server. */
    private static Set<String> fromBukkit(String category) {
        // The guard has to come BEFORE any mention of Tag. Catching the failure is not
        // enough: a class whose static initialiser threw is poisoned for the life of the
        // JVM, so one touch without a server makes every later Tag lookup fail even after a
        // server exists. That single fact was behind every cross-test failure in this
        // milestone.
        if (!serverPresent()) {
            return Set.of();
        }
        try {
            Tag<Material> tag = switch (category) {
                case "meat" -> Tag.ITEMS_MEAT;
                case "fish" -> Tag.ITEMS_FISHES;
                case "carpet" -> Tag.WOOL_CARPETS;
                case "rails" -> Tag.RAILS;
                case "ice" -> Tag.ICE;
                default -> null;
            };
            if (tag == null) {
                return Set.of();
            }
            Set<String> materials = new LinkedHashSet<>();
            tag.getValues().forEach(material -> materials.add(material.name()));
            return materials;
        } catch (RuntimeException | LinkageError e) {
            return Set.of();
        }
    }

    /** Named individually because no tag covers them. */
    private static final Set<String> NAMED = Set.of(
            // Emeralds and emerald blocks.
            "EMERALD", "EMERALD_BLOCK",

            // Iron and gold in every form. SPEC 21.8 calls these the two that would have
            // ended the economy on their own.
            "RAW_IRON", "RAW_IRON_BLOCK", "IRON_INGOT", "IRON_NUGGET", "IRON_BLOCK",
            "RAW_GOLD", "RAW_GOLD_BLOCK", "GOLD_INGOT", "GOLD_NUGGET", "GOLD_BLOCK",

            // Mob drops. Every one is produced by a farm that runs while nobody is present,
            // which SPEC 21.1 calls "the single most common cause of death for server
            // economies".
            "ROTTEN_FLESH", "BONE", "BONE_MEAL", "STRING", "SPIDER_EYE", "GUNPOWDER",
            "BLAZE_ROD", "BLAZE_POWDER", "ENDER_PEARL", "SLIME_BALL", "MAGMA_CREAM",
            "PHANTOM_MEMBRANE", "LEATHER", "FEATHER", "INK_SAC", "GLOW_INK_SAC",
            "WITHER_SKELETON_SKULL", "SHULKER_SHELL", "EGG", "TURTLE_EGG", "SNIFFER_EGG",

            // Crops and plants that grow on a timer with no player present.
            "SUGAR_CANE", "PAPER", "SUGAR", "BAMBOO", "CACTUS", "KELP", "DRIED_KELP",
            "DRIED_KELP_BLOCK", "SEA_PICKLE",
            "MELON", "MELON_SLICE", "PUMPKIN", "CARVED_PUMPKIN",
            "HONEY_BOTTLE", "HONEY_BLOCK", "HONEYCOMB", "HONEYCOMB_BLOCK",
            "COCOA_BEANS", "SWEET_BERRIES", "GLOW_BERRIES",
            "NETHER_WART", "NETHER_WART_BLOCK", "CHORUS_FRUIT", "POPPED_CHORUS_FRUIT",

            // Bulk stone and terrain. A cobblestone generator is the canonical infinite farm.
            "COBBLESTONE", "COBBLED_DEEPSLATE", "STONE", "DEEPSLATE", "SAND", "RED_SAND",
            "GRAVEL", "TNT", "SNOW", "SNOWBALL", "SNOW_BLOCK",

            // Fishing loot that is not a fish and so not covered by the tag below.
            "NAUTILUS_SHELL", "LILY_PAD", "BOWL", "FISHING_ROD", "NAME_TAG", "SADDLE",
            "TRIPWIRE_HOOK", "STICK", "LEAD");

    /**
     * Everything the server may never buy, resolved on first use.
     *
     * <p><b>Lazily</b>, and that is not a micro-optimisation. Bukkit's {@code Tag} constants
     * are resolved through the running server, so touching them at class-initialisation time
     * makes this class impossible to load before the server exists — and the failure is an
     * {@link ExceptionInInitializerError}, which poisons the class for the life of the JVM.
     * A safety check that cannot be loaded is a safety check that does not run.
     */
    private static volatile Set<String> all;

    /** False if the tag-backed categories could not be read. See {@link #tagsResolved}. */
    private static volatile boolean tagsResolved;

    /** Whether the cached list was built with the tags readable. */
    private static volatile boolean cachedWithTags;

    /**
     * The list, building it once and rebuilding only when that would change the answer.
     *
     * <p>The cache has to satisfy two things that pull against each other. A build that could
     * not read the tags must not be <b>sticky</b>, or whichever caller touched this class
     * before the server came up decides what the blacklist contains for the life of the JVM.
     * But it must still be a cache, because {@code MarketRegistry} asks per catalogue entry
     * and a rebuild scans every {@code Material} — not caching at all turned a three-minute
     * test suite into an eleven-minute one.
     *
     * <p>So every build is cached, and a cache built without tags is discarded the moment a
     * server exists to read them from. In production that means exactly one rebuild, on the
     * first call after startup.
     */
    private static Set<String> resolve() {
        Set<String> snapshot = all;
        if (snapshot != null && (cachedWithTags || !serverPresent())) {
            return snapshot;
        }
        synchronized (HardBlacklist.class) {
            if (all != null && (cachedWithTags || !serverPresent())) {
                return all;
            }
            all = build();
            cachedWithTags = tagsResolved;
            return all;
        }
    }

    /** Whether there is a server to read tags from, without throwing when there is not. */
    private static boolean serverPresent() {
        try {
            return org.bukkit.Bukkit.getServer() != null;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static Set<String> build() {
        Set<String> blacklist = new LinkedHashSet<>(NAMED);
        tagsResolved = true;

        // SPEC 21.8's categories: "all raw and cooked meat", "all fishing loot and all fish",
        // "carpet", "rails", "ice of all kinds". Read as categories so a food or a fish added
        // by Mojang is covered without an edit here.
        //
        // Saplings, leaves and flowers are deliberately absent. SPEC 21.8 does not list them,
        // and adding them on the reasoning that they grow without a player contradicts SPEC
        // 21.9, which prices sniffer-grown torchflower and pitcher plants at 300 as things a
        // player must go and find. A hardcoded safety list must say exactly what the
        // specification says: extending it looks harmless and quietly deletes a feature.
        for (String category : CATEGORY_NAMES) {
            Set<String> materials = categories.of(category);
            if (materials.isEmpty()) {
                tagsResolved = false;
                continue;
            }
            blacklist.addAll(materials);
        }
        return Set.copyOf(blacklist);
    }

    /** Whether SPEC 21.8 forbids the server from ever buying this material. */
    public static boolean forbids(String material) {
        return material != null && resolve().contains(material.trim().toUpperCase(Locale.ROOT));
    }

    /** The whole list, for the tests and for {@code /ca market} diagnostics. */
    public static Set<String> materials() {
        return resolve();
    }

    /** How many materials are forbidden, so a test can prove the tags actually resolved. */
    public static int size() {
        return resolve().size();
    }

    /**
     * Whether the tag-backed categories were readable.
     *
     * <p>False means SPEC 21.8's category entries — "all raw and cooked meat", "all fishing
     * loot and all fish", "carpet", "rails", "ice of all kinds" — are <b>not</b> being
     * enforced, and only the explicitly named materials are. The market must not open in that
     * state, because the list looks complete and is not.
     */
    public static boolean tagsResolved() {
        resolve();
        return tagsResolved;
    }

    /** Drops the cached list, so a test can resolve it again once a server exists. */
    public static void reset() {
        synchronized (HardBlacklist.class) {
            all = null;
            tagsResolved = false;
            cachedWithTags = false;
        }
    }
}
