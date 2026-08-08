package dev.civitas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 9.1's {@code /city help}.
 *
 * <p>The tests worth having here are not about paging. They are the ones that make the help
 * unable to go quietly out of date, which is the only failure mode a help page really has: a
 * stale one is indistinguishable from a current one until a player follows it and finds the
 * command does not exist.
 *
 * <p>So {@link Coverage} asserts in both directions — every declared entry names a real
 * command, and every root command has an entry. A milestone that adds a command and forgets
 * the help line fails the build rather than shipping.
 */
class HelpPagesTest {

    private static FileConfiguration language(String code) {
        File file = new File("src/main/resources/lang/" + code + ".yml");
        assertTrue(file.isFile(), code + ".yml is missing");
        return YamlConfiguration.loadConfiguration(file);
    }

    /** Everything, which is what an operator with every node sees. */
    private static final Predicate<String> ALL = permission -> true;

    /** An ordinary player: SPEC 10's {@code default: true} nodes, and nothing under admin. */
    private static final Predicate<String> PLAYER =
            permission -> !permission.startsWith("civitas.admin");

    /**
     * The plugin's root commands.
     *
     * <p>A declared list rather than a started server: this is plain JUnit, and the check that
     * guards against drift should not also be the slowest test in the suite.
     * {@code CommandRegistryTest} is what ties this list to what actually registers.
     */
    /**
     * The root commands, from {@link HelpPages#ROOT_COMMANDS} rather than a literal here.
     *
     * <p>This was a hardcoded list of nineteen names, and both directions of the coverage check
     * below were measured against it — so a command the list had never heard of could ship
     * undocumented and pass. {@code /quota} and {@code /toggle} both did.
     */
    private static Set<String> rootCommands() {
        return new LinkedHashSet<>(HelpPages.ROOT_COMMANDS);
    }

    // ==================================================================================
    // Coverage, the reason this class exists
    // ==================================================================================

    @Nested
    @DisplayName("coverage")
    class Coverage {

        @Test
        @DisplayName("every entry's message key exists in both languages")
        void keysExist() {
            FileConfiguration english = language("en");
            FileConfiguration italian = language("it");

            for (HelpPages.Entry entry : HelpPages.entries()) {
                assertTrue(english.isString(entry.key()), "en.yml has no " + entry.key());
                assertTrue(italian.isString(entry.key()), "it.yml has no " + entry.key());
            }
            for (String category : HelpPages.categories()) {
                assertTrue(english.isString(category), "en.yml has no " + category);
                assertTrue(italian.isString(category), "it.yml has no " + category);
            }
        }

        @Test
        @DisplayName("every entry documents a command that exists")
        void noEntryNamesAGhost() {
            // Catches a command being renamed or removed while its help line stays behind,
            // pointing players at something that no longer works.
            Set<String> roots = rootCommands();

            for (HelpPages.Entry entry : HelpPages.entries()) {
                assertFalse(entry.command().isEmpty(), entry.key() + " names no command");
                assertTrue(roots.contains(entry.command().get(0)),
                        entry.key() + " documents /" + entry.command().get(0)
                                + ", which is not a root command");
            }
        }

        @Test
        @DisplayName("every root command has at least one entry")
        void noCommandIsUndocumented() {
            // The direction that matters more: a command nobody can discover may as well not
            // exist. This is what fails when a milestone adds a command and forgets the help.
            Set<String> documented = new LinkedHashSet<>();
            HelpPages.entries().forEach(entry -> documented.add(entry.command().get(0)));

            List<String> missing = new ArrayList<>();
            for (String root : rootCommands()) {
                if (!documented.contains(root)) {
                    missing.add(root);
                }
            }
            assertTrue(missing.isEmpty(), "no /city help entry for: " + missing);
        }

        @Test
        @DisplayName("no two entries share a message key")
        void keysAreUnique() {
            Set<String> seen = new LinkedHashSet<>();
            for (HelpPages.Entry entry : HelpPages.entries()) {
                assertTrue(seen.add(entry.key()), entry.key() + " is declared twice");
            }
        }

        @Test
        @DisplayName("every entry's key sits under help. so the lang tests reach it")
        void keysAreNamespaced() {
            for (HelpPages.Entry entry : HelpPages.entries()) {
                assertTrue(entry.key().startsWith("help."), entry.key());
            }
        }
    }

    // ==================================================================================
    // Permission filtering
    // ==================================================================================

    @Nested
    @DisplayName("filtering")
    class Filtering {

        @Test
        @DisplayName("an ordinary player is not offered the admin tree")
        void adminIsHidden() {
            assertTrue(HelpPages.visibleTo(PLAYER).stream()
                    .noneMatch(entry -> entry.category().equals(HelpPages.ADMIN)));
            assertTrue(HelpPages.visibleTo(ALL).stream()
                            .anyMatch(entry -> entry.category().equals(HelpPages.ADMIN)),
                    "and an admin still is");
        }

        @Test
        @DisplayName("a command with its own node is hidden without that node")
        void perCommandNodes() {
            // /city create is civitas.city.create, not civitas.use. A server that revokes it
            // should stop advertising it.
            Predicate<String> noFounding = permission -> !permission.equals("civitas.city.create");

            assertTrue(HelpPages.visibleTo(noFounding).stream()
                    .noneMatch(entry -> entry.key().equals("help.city-create")));
            assertTrue(HelpPages.visibleTo(ALL).stream()
                    .anyMatch(entry -> entry.key().equals("help.city-create")));
        }

        @Test
        @DisplayName("city commands are shown whatever the player's rank")
        void cityCommandsAreNotRankFiltered() {
            // Deliberate, and the opposite of what SPEC 8.2 does for GUI buttons. A Recruit
            // reading what a Mayor can do is how they learn what to aim at, and SPEC 5.4 lets
            // a city grant any flag to any rank, so filtering on the rank someone holds now
            // would hide commands they may be given in the next minute.
            List<HelpPages.Entry> visible = HelpPages.visibleTo(PLAYER);

            assertTrue(visible.stream().anyMatch(e -> e.key().equals("help.city-disband")));
            assertTrue(visible.stream().anyMatch(e -> e.key().equals("help.city-claim")));
        }

        @Test
        @DisplayName("a sender with nothing still gets a page rather than an error")
        void emptyIsStillOnePage() {
            Predicate<String> nothing = permission -> false;

            assertTrue(HelpPages.visibleTo(nothing).isEmpty());
            assertEquals(1, HelpPages.pageCount(nothing, 10));
            assertTrue(HelpPages.page(nothing, 1, 10).isEmpty());
        }
    }

    // ==================================================================================
    // Paging
    // ==================================================================================

    @Nested
    @DisplayName("paging")
    class Paging {

        @Test
        @DisplayName("the pages together are exactly the visible entries, in order")
        void pagesCoverEverythingOnce() {
            // The property that actually matters: nothing falls between two pages and nothing
            // appears on both. An off-by-one in the slice breaks one or the other.
            List<HelpPages.Entry> rebuilt = new ArrayList<>();
            for (int page = 1; page <= HelpPages.pageCount(ALL, 7); page++) {
                rebuilt.addAll(HelpPages.page(ALL, page, 7));
            }

            assertEquals(HelpPages.visibleTo(ALL), rebuilt);
        }

        @Test
        @DisplayName("and still does at a page size of one")
        void pageSizeOne() {
            assertEquals(HelpPages.entries().size(), HelpPages.pageCount(ALL, 1));
            assertEquals(1, HelpPages.page(ALL, 3, 1).size());
            assertEquals(HelpPages.entries().get(2), HelpPages.page(ALL, 3, 1).get(0));
        }

        @Test
        @DisplayName("a page past the end shows the last page")
        void clampsHigh() {
            int last = HelpPages.pageCount(ALL, 7);

            assertEquals(HelpPages.page(ALL, last, 7), HelpPages.page(ALL, 9999, 7));
            assertFalse(HelpPages.page(ALL, 9999, 7).isEmpty());
        }

        @Test
        @DisplayName("page counts are exact at a boundary")
        void exactBoundary() {
            int total = HelpPages.entries().size();

            assertEquals(1, HelpPages.pageCount(ALL, total),
                    "a page size equal to the total is one page, not two");
            assertEquals(2, HelpPages.pageCount(ALL, total - 1), "one fewer is two");
        }

        @Test
        @DisplayName("clamp keeps a page number inside the range")
        void clampIsInclusive() {
            assertEquals(1, HelpPages.clamp(0, 5));
            assertEquals(1, HelpPages.clamp(-3, 5));
            assertEquals(5, HelpPages.clamp(5, 5));
            assertEquals(5, HelpPages.clamp(6, 5));
            assertEquals(1, HelpPages.clamp(3, 1), "a single page swallows everything");
        }

        @Test
        @DisplayName("a page size below one is refused rather than dividing by zero")
        void rejectsSillyPageSize() {
            assertThrows(IllegalArgumentException.class, () -> HelpPages.pageCount(ALL, 0));
            assertThrows(IllegalArgumentException.class, () -> HelpPages.pageCount(ALL, -1));
        }
    }

    // ==================================================================================
    // The entries themselves
    // ==================================================================================

    @Nested
    @DisplayName("wording")
    class Wording {

        @Test
        @DisplayName("usage lines document their arguments in angle brackets")
        void usageArgumentsSurvive() {
            // The trap HelpPages' javadoc describes. A line reading "/city info <name>"
            // renders literally only because send() passes no resolvers. If a future change
            // starts formatting these lines with placeholders, every argument name in the
            // help vanishes and the help silently starts lying about the syntax.
            FileConfiguration english = language("en");
            List<String> withArguments = new ArrayList<>();

            for (HelpPages.Entry entry : HelpPages.entries()) {
                String line = english.getString(entry.key(), "");
                if (line.contains("<name>") || line.contains("<player>")
                        || line.contains("<amount>") || line.contains("<city>")) {
                    withArguments.add(entry.key());
                }
            }

            assertTrue(withArguments.size() > 5,
                    "several usage lines should document an argument; found " + withArguments);
        }

        @Test
        @DisplayName("the entry list is immutable")
        void immutable() {
            List<HelpPages.Entry> entries = HelpPages.entries();

            assertThrows(UnsupportedOperationException.class, () -> entries.add(null));
            assertSame(entries, HelpPages.entries());
        }

        @Test
        @DisplayName("categories are in declaration order and complete")
        void categories() {
            assertEquals(List.of(HelpPages.GENERAL, HelpPages.CITY, HelpPages.WAR,
                    HelpPages.ADMIN), List.copyOf(HelpPages.categories()));
        }

        @Test
        @DisplayName("entries are grouped, so a category is never split across the list")
        void categoriesAreContiguous() {
            // The renderer prints a category header when the category changes. If the entries
            // were interleaved it would print the same header several times per page.
            Set<String> finished = new LinkedHashSet<>();
            String current = null;

            for (HelpPages.Entry entry : HelpPages.entries()) {
                if (!entry.category().equals(current)) {
                    assertTrue(finished.add(entry.category()),
                            entry.category() + " appears in two separate runs");
                    current = entry.category();
                }
            }
            assertNotNull(current);
        }

        @Test
        @DisplayName("an entry keeps its own copy of the command path")
        void commandPathIsCopied() {
            List<String> mutable = new ArrayList<>(List.of("city", "claim"));
            HelpPages.Entry entry =
                    new HelpPages.Entry("help.x", "civitas.use", HelpPages.CITY, mutable);
            mutable.clear();

            assertEquals(List.of("city", "claim"), entry.command());
        }
    }
}
