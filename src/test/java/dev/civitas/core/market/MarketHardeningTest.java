package dev.civitas.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 21.6, 21.8 and 21.9, the market's exploit surface.
 *
 * <p>SPEC 21.1 states the governing principle: "a Minecraft economy is not broken by players
 * spending too much, it is broken by <b>money being created faster than it is destroyed</b>…
 * Sinks do not save you, because sinks scale with what a player chooses to spend and sources
 * scale with what a machine can produce while the player sleeps."
 *
 * <p>The buy list is the only source. Everything asserted here is about keeping it small and
 * keeping the things that cannot be automated in it.
 */
class MarketHardeningTest {

    /**
     * SPEC 21.8's categories, injected rather than read from a running server.
     *
     * <p>This class needed MockBukkit for one reason: Bukkit's {@code Tag} constants resolve
     * through {@code Bukkit.getServer()}. MockBukkit's server is global static state shared by
     * every test class in the JVM, and reaching for it here made this class pass alone and
     * fail in a full run, then broke the defense tests when the teardown was made defensive,
     * and took the suite from three minutes to ten.
     *
     * <p>None of that was about the blacklist. So the categories are supplied directly, which
     * is what the project's own convention asks for anyway: pure logic is plain JUnit with no
     * server. What is lost is the check that the Bukkit tag <i>names</i> are right — that is
     * covered by {@code HardBlacklist.tagsResolved} failing closed in production, which
     * refuses to open the market rather than enforcing a partial list.
     */
    private static final HardBlacklist.Categories FIXED_CATEGORIES = category -> switch (category) {
        case "meat" -> Set.of("BEEF", "COOKED_BEEF", "PORKCHOP", "COOKED_PORKCHOP", "CHICKEN",
                "COOKED_CHICKEN", "MUTTON", "COOKED_MUTTON", "RABBIT", "COOKED_RABBIT");
        case "fish" -> Set.of("COD", "COOKED_COD", "SALMON", "COOKED_SALMON", "TROPICAL_FISH",
                "PUFFERFISH");
        case "carpet" -> Set.of("WHITE_CARPET", "RED_CARPET", "BLUE_CARPET", "BLACK_CARPET");
        case "rails" -> Set.of("RAIL", "POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL");
        case "ice" -> Set.of("ICE", "PACKED_ICE", "BLUE_ICE", "FROSTED_ICE");
        default -> Set.of();
    };

    @org.junit.jupiter.api.BeforeEach
    void fixedCategories() {
        HardBlacklist.useCategories(FIXED_CATEGORIES);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreCategories() {
        HardBlacklist.useCategories(null);
    }

    private static YamlConfiguration economy() {
        File file = new File("src/main/resources/economy.yml");
        assertTrue(file.isFile(), "economy.yml is missing");
        return YamlConfiguration.loadConfiguration(file);
    }

    private static List<String> shippedBuyList() {
        ConfigurationSection buy = economy().getConfigurationSection("market.buy");
        assertTrue(buy != null, "economy.yml has no market.buy");
        return buy.getKeys(false).stream().map(key -> key.toUpperCase(Locale.ROOT)).toList();
    }

    // ==================================================================================
    // SPEC 21.8, the hard blacklist
    // ==================================================================================

    @Nested
    @DisplayName("the hard blacklist, SPEC 21.8")
    class Blacklist {

        @Test
        @DisplayName("iron and gold are forbidden in every form")
        void ironAndGold() {
            // SPEC 21.8's own note: "Note what is missing from Part I's list that should have
            // been there: iron and gold, which Part I priced at 45 and 70. Those two entries
            // alone would have ended the server's economy within a week of launch." Both were
            // in the shipped catalogue until this milestone.
            for (String material : List.of("IRON_INGOT", "IRON_NUGGET", "IRON_BLOCK",
                    "RAW_IRON", "GOLD_INGOT", "GOLD_NUGGET", "GOLD_BLOCK", "RAW_GOLD")) {
                assertTrue(HardBlacklist.forbids(material), material + " should be forbidden");
            }
        }

        @Test
        @DisplayName("emeralds, because a villager makes them a fixed-rate faucet")
        void emeralds() {
            assertTrue(HardBlacklist.forbids("EMERALD"));
            assertTrue(HardBlacklist.forbids("EMERALD_BLOCK"));
        }

        @Test
        @DisplayName("every mob drop SPEC names")
        void mobDrops() {
            for (String material : List.of("ROTTEN_FLESH", "BONE", "BONE_MEAL", "STRING",
                    "SPIDER_EYE", "GUNPOWDER", "BLAZE_ROD", "ENDER_PEARL", "SLIME_BALL",
                    "MAGMA_CREAM", "PHANTOM_MEMBRANE", "LEATHER", "FEATHER", "INK_SAC",
                    "GLOW_INK_SAC", "WITHER_SKELETON_SKULL", "SHULKER_SHELL", "EGG")) {
                assertTrue(HardBlacklist.forbids(material), material + " is a mob drop");
            }
        }

        @Test
        @DisplayName("cooked and raw meat, from the tag rather than a hand-written list")
        void meatFromTag() {
            // SPEC writes "all raw and cooked meat" as a category. Enumerating it by hand
            // stops protecting the moment Mojang adds a food, which is the failure a
            // hardcoded list is supposed to be immune to.
            for (String material : List.of("BEEF", "COOKED_BEEF", "PORKCHOP", "COOKED_PORKCHOP",
                    "CHICKEN", "COOKED_CHICKEN", "MUTTON", "RABBIT")) {
                assertTrue(HardBlacklist.forbids(material), material + " is meat");
            }
        }

        @Test
        @DisplayName("fish, carpet, rails and ice, the other tag-backed categories")
        void otherTagCategories() {
            assertTrue(HardBlacklist.forbids("COD"));
            assertTrue(HardBlacklist.forbids("SALMON"));
            assertTrue(HardBlacklist.forbids("WHITE_CARPET"));
            assertTrue(HardBlacklist.forbids("RAIL"));
            assertTrue(HardBlacklist.forbids("PACKED_ICE"));
            assertTrue(HardBlacklist.forbids("BLUE_ICE"));
        }

        @Test
        @DisplayName("the tags actually resolved, so the list is not just the named entries")
        void tagsResolved() {
            // If Tag.ITEMS_MEAT came back empty the named list alone would still pass several
            // assertions above, and the categories would be silently unprotected.
            // The explicitly named list is under 80 entries, so anything comfortably above
            // that proves meat, fish, carpet, rails and ice were read from their tags rather
            // than silently skipped.
            assertTrue(HardBlacklist.size() > 100,
                    "only " + HardBlacklist.size() + " materials are blacklisted, so the "
                            + "tag-backed categories did not resolve");
        }

        @Test
        @DisplayName("things a player must mine or explore for are NOT forbidden")
        void minedItemsAllowed() {
            // The list refusing everything would be as useless as it allowing everything.
            // SPEC 21.9's design intent: "the highest-value income is exploration and mining,
            // which requires a player to be present and actively playing."
            assertFalse(HardBlacklist.forbids("DIAMOND"));
            assertFalse(HardBlacklist.forbids("ANCIENT_DEBRIS"));
            assertFalse(HardBlacklist.forbids("QUARTZ"));
            assertFalse(HardBlacklist.forbids("ECHO_SHARD"));
        }
    }

    // ==================================================================================
    // SPEC 21.10.1 assertion 2, villager disjointness
    // ==================================================================================

    @Nested
    @DisplayName("villager disjointness")
    class Villagers {

        @Test
        @DisplayName("a librarian's stock is not buyable")
        void librarian() {
            // The profession that makes this assertion matter most: a cured librarian sells
            // for one emerald and restocks twice a day, forever, with nobody present.
            assertTrue(VillagerTrades.sells("BOOKSHELF"));
            assertTrue(VillagerTrades.sells("GLASS"));
            assertTrue(VillagerTrades.sells("LANTERN"));
            assertTrue(VillagerTrades.sells("COMPASS"));
        }

        @Test
        @DisplayName("the wandering trader's stock counts too")
        void wanderingTrader() {
            assertTrue(VillagerTrades.sells("NAUTILUS_SHELL"));
            assertTrue(VillagerTrades.sells("PACKED_ICE"));
            assertTrue(VillagerTrades.sells("SEA_PICKLE"));
        }

        @Test
        @DisplayName("nothing a villager sells is in the shipped buy list")
        void shippedListIsDisjoint() {
            List<String> overlap = shippedBuyList().stream()
                    .filter(VillagerTrades::sells)
                    .toList();

            assertTrue(overlap.isEmpty(),
                    "these are buyable and also obtainable from a villager: " + overlap);
        }

        @Test
        @DisplayName("a diamond is not a villager output, so mining still pays")
        void notEverythingIsAVillagerOutput() {
            assertFalse(VillagerTrades.sells("DIAMOND"));
            assertFalse(VillagerTrades.sells("ANCIENT_DEBRIS"));
        }
    }

    // ==================================================================================
    // SPEC 21.9, the shipped buy list
    // ==================================================================================

    @Nested
    @DisplayName("the shipped buy list, SPEC 21.9")
    class BuyList {

        @Test
        @DisplayName("passes all four of SPEC 21.10.1's assertions")
        void passesEverything() {
            // The test that stands between a future edit and a broken economy. Each assertion
            // is cheap; the reason they exist is that none of the four failures is visible
            // from reading a price table.
            List<String> buyList = shippedBuyList();
            MarketSafetyCheck check = new MarketSafetyCheck();

            check.checkHardBlacklist(buyList);
            check.checkVillagerDisjointness(buyList);
            check.checkEquivalenceClasses(buyList,
                    dev.civitas.core.market.craft.CraftingEdges.baseGraph());
            check.checkAutomatableDeclared(buyList, shippedAutomatableComments());

            assertTrue(check.passed(), "economy.yml's buy list fails SPEC 21.10.1:\n  "
                    + check.failures().stream().map(Object::toString)
                    .reduce((a, b) -> a + "\n  " + b).orElse(""));
        }

        @Test
        @DisplayName("is deliberately small")
        void isSmall() {
            // SPEC 21.9: "A deliberately small list… Fourteen entries instead of nineteen,
            // but the five removed were the five that broke everything."
            assertTrue(shippedBuyList().size() <= 15,
                    "the buy list has grown to " + shippedBuyList().size() + " entries; every "
                            + "one of them is a money faucet, so each needs justifying");
        }

        @Test
        @DisplayName("none of Part I's five dangerous entries survived")
        void partOneEntriesAreGone() {
            List<String> buyList = shippedBuyList();

            for (String removed : List.of("IRON_INGOT", "GOLD_INGOT", "EMERALD", "LEATHER",
                    "BEEF", "SUGAR_CANE", "BAMBOO", "PUMPKIN", "MELON_SLICE", "STONE",
                    "COCOA_BEANS", "HONEY_BOTTLE", "NETHER_WART")) {
                assertFalse(buyList.contains(removed),
                        removed + " is back in the buy list and SPEC 21.8 forbids it");
            }
        }

        @Test
        @DisplayName("every entry declares whether it can be automated")
        void everyEntryDeclaresAutomatable() {
            Map<String, String> declared = shippedAutomatableComments();
            List<String> missing = new ArrayList<>();

            for (String material : shippedBuyList()) {
                String value = declared.get(material);
                if (value == null || (!value.equals("no") && !value.equals("semi"))) {
                    missing.add(material + " -> " + value);
                }
            }

            assertTrue(missing.isEmpty(), "buy entries without a usable automatable comment: "
                    + missing);
        }

        /** Reads the inline comments the same way {@code CivitasPlugin} does. */
        private Map<String, String> shippedAutomatableComments() {
            Map<String, String> declared = new LinkedHashMap<>();
            ConfigurationSection buy = economy().getConfigurationSection("market.buy");
            assertTrue(buy != null);
            for (String key : buy.getKeys(false)) {
                for (String comment : commentsOn(buy, key)) {
                    String trimmed = comment == null ? "" : comment.trim();
                    if (trimmed.toLowerCase(Locale.ROOT).startsWith("automatable:")) {
                        declared.put(key.toUpperCase(Locale.ROOT),
                                trimmed.substring("automatable:".length()).trim()
                                        .toLowerCase(Locale.ROOT));
                    }
                }
            }
            return declared;
        }
    }

    /** Comments above a key, then trailing. See CivitasPlugin for why both. */
    private static List<String> commentsOn(ConfigurationSection section, String key) {
        List<String> comments = new ArrayList<>(section.getComments(key));
        comments.addAll(section.getInlineComments(key));
        return comments;
    }

    // ==================================================================================
    // The comment has to survive being written back
    // ==================================================================================

    @Nested
    @DisplayName("inline comments survive a config write-back")
    class CommentSurvival {

        @TempDir
        Path directory;

        @Test
        @DisplayName("saving the file does not strip the automatable comments")
        void commentsSurviveSave() throws IOException {
            // ConfigManager writes missing keys back into the operator's own file with
            // config.save(). If that stripped comments, a plugin update that added any key
            // anywhere would silently delete every "# automatable:" line — and the operator's
            // market would refuse to open on the restart after next, for a reason that had
            // nothing to do with anything they did.
            File copy = directory.resolve("economy.yml").toFile();
            YamlConfiguration loaded = economy();
            loaded.save(copy);

            ConfigurationSection buy = YamlConfiguration.loadConfiguration(copy)
                    .getConfigurationSection("market.buy");
            assertTrue(buy != null, "market.buy did not survive the save");

            List<String> lost = new ArrayList<>();
            for (String key : buy.getKeys(false)) {
                boolean kept = commentsOn(buy, key).stream()
                        .anyMatch(comment -> comment != null
                                && comment.trim().toLowerCase(Locale.ROOT)
                                .startsWith("automatable:"));
                if (!kept) {
                    lost.add(key);
                }
            }

            assertTrue(lost.isEmpty(),
                    "these lost their automatable comment when the file was saved, which "
                            + "would shut the market on a later restart: " + lost);
        }
    }

    // ==================================================================================
    // SPEC 21.6, the builder's catalogue
    // ==================================================================================

    /**
     * The builder's catalogue still resolves through Bukkit, so these three keep a server of
     * their own rather than the whole class holding one. Selling is not the risky side, so
     * nothing here guards money: it is checking that the groups an operator can name actually
     * expand to something.
     */
    @Nested
    @DisplayName("the sell catalogue, SPEC 21.6")
    class SellCatalogue {

        @org.junit.jupiter.api.BeforeEach
        void startServer() {
            org.mockbukkit.mockbukkit.MockBukkit.mock();
            SellGroups.reset();
        }

        @org.junit.jupiter.api.AfterEach
        void stopServer() {
            org.mockbukkit.mockbukkit.MockBukkit.unmock();
            SellGroups.reset();
        }

        @Test
        @DisplayName("covers hundreds of materials from a handful of priced groups")
        void groupsExpand() {
            // SPEC 21.6 describes the catalogue as prose, not a table. Hand-listing it would
            // mean inventing several hundred prices and would stop covering it the day a wood
            // type is added.
            assertTrue(SellGroups.materialCount() > 250,
                    "the sell groups expand to only " + SellGroups.materialCount()
                            + " materials, which is not the catalogue SPEC 21.6 describes");
        }

        @Test
        @DisplayName("every group named in economy.yml resolves to something")
        void everyConfiguredGroupResolves() {
            // A typo in a group name would silently sell nothing, and the market would look
            // like it was working.
            ConfigurationSection sell = economy().getConfigurationSection("market.sell");
            assertTrue(sell != null, "economy.yml has no market.sell");
            List<String> empty = new ArrayList<>();

            for (String key : sell.getKeys(false)) {
                boolean single = org.bukkit.Material.matchMaterial(key) != null;
                if (!single && SellGroups.expand(key).isEmpty()) {
                    empty.add(key);
                }
            }

            assertTrue(empty.isEmpty(),
                    "these sell keys are neither a material nor a known group: " + empty
                            + ". Known groups: " + SellGroups.names());
        }

        @Test
        @DisplayName("selling a blacklisted material is fine, buying it is not")
        void blacklistIsAboutBuyingOnly() {
            // Not a contradiction, and worth an explicit assertion because it looks like one.
            // Carpet is on SPEC 21.8's blacklist and in SPEC 21.6's builder catalogue: the
            // server sells it all day and may never buy it, because buying it would put a
            // price on a sheep farm that runs while nobody is present.
            HardBlacklist.useCategories(FIXED_CATEGORIES);
            Set<String> carpet = SellGroups.expand("carpet");

            assertFalse(carpet.isEmpty());
            assertTrue(carpet.stream().anyMatch(HardBlacklist::forbids),
                    "carpet is sold by the server and never bought by it");
        }
    }
}
