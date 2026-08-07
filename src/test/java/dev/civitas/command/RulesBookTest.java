package dev.civitas.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Locale;

import dev.civitas.command.player.CivitasCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules book, SPEC 9.1's {@code /civitas rules}.
 *
 * <p>Two of these tests are not about presentation. SPEC names this book as the place where
 * two specific rules are written down, and both are rules a player only discovers by being
 * hurt by them:
 *
 * <ul>
 *   <li><b>SPEC 17.2 case 16</b> — claiming ground somebody else built on is allowed. "Builds
 *       do not confer ownership. Documented in the rules book."</li>
 *   <li><b>SPEC 11.7 and SPEC 17.4 case 44</b> — war restores every block but not items taken
 *       from a chest by hand. SPEC calls it "a deliberate, explicit exception" that "must be
 *       communicated clearly to players", and gives the sentence it wants them to leave with:
 *       <i>destroying storage is pointless, looting it is not.</i></li>
 * </ul>
 *
 * <p>Without a test, either could be trimmed out of a language file during a tidy-up and
 * nothing would notice until a player lost something and read the book to check.
 */
class RulesBookTest {

    private static FileConfiguration language(String code) {
        File file = new File("src/main/resources/lang/" + code + ".yml");
        assertTrue(file.isFile(), code + ".yml is missing");
        return YamlConfiguration.loadConfiguration(file);
    }

    /** Every page of the book, joined, lowercased, for a substring search. */
    private static String bookText(String code) {
        FileConfiguration language = language(code);
        StringBuilder text = new StringBuilder();
        for (String key : CivitasCommand.pageKeys()) {
            String page = language.getString(key);
            assertTrue(page != null && !page.isBlank(), code + ".yml has no " + key);
            text.append(page).append('\n');
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean mentionsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================================
    // The two rules SPEC requires this book to carry
    // ==================================================================================

    @Nested
    @DisplayName("the documented promises")
    class Promises {

        @Test
        @DisplayName("SPEC 17.2 case 16: building somewhere does not make it yours")
        void buildsDoNotConferOwnership() {
            // SPEC's own words: "Allowed. Builds do not confer ownership. Documented in the
            // rules book." A player who loses ground they built on and was never told will
            // read it as theft, and will be right to be angry about being surprised.
            assertTrue(mentionsAny(bookText("en"), "even if you built on it"),
                    "the English book must say that building somewhere does not claim it");
            assertTrue(mentionsAny(bookText("it"), "anche se ci hai costruito"),
                    "and so must the Italian one");
        }

        @Test
        @DisplayName("SPEC 11.7: blocks come back, hand-looted items do not")
        void lootAsymmetry() {
            String english = bookText("en");

            assertTrue(mentionsAny(english, "put back", "restored"),
                    "the rollback promise, SPEC 1.2");
            assertTrue(mentionsAny(english, "do not", "does not"),
                    "and the exception to it");
            assertTrue(english.contains("chest") || english.contains("storage"),
                    "which is specifically about containers");
        }

        @Test
        @DisplayName("SPEC 17.4 case 44: and says which way round it cuts")
        void asymmetryIsSpeltOut() {
            // SPEC does not merely ask that the rule be present, it gives the conclusion a
            // player should reach: "destroying storage is pointless, looting it is not". A
            // book that states the mechanic without the consequence has not communicated it.
            String english = bookText("en");

            assertTrue(mentionsAny(english, "breaking storage achieves nothing",
                            "destroying storage is pointless"),
                    "half the asymmetry: wrecking a chest gains nothing");
            assertTrue(mentionsAny(english, "looting it achieves", "looting it is not"),
                    "and the other half: taking from one gains a great deal");
        }

        @Test
        @DisplayName("and points at the vault, which is the way out SPEC 11.7 offers")
        void namesTheMitigation() {
            // SPEC 11.7's mitigation paragraph. Telling a player their loot is at risk and
            // not telling them what to do about it is worse than not telling them at all.
            assertTrue(mentionsAny(bookText("en"), "vault"));
            assertTrue(mentionsAny(bookText("it"), "caveau"));
        }
    }

    // ==================================================================================
    // The book itself
    // ==================================================================================

    @Nested
    @DisplayName("the book")
    class Structure {

        @Test
        @DisplayName("both languages have every page, a title and an author")
        void complete() {
            for (String code : List.of("en", "it")) {
                FileConfiguration language = language(code);
                assertTrue(language.isString("rules.title"), code + " has no title");
                assertTrue(language.isString("rules.author"), code + " has no author");
                for (String key : CivitasCommand.pageKeys()) {
                    assertTrue(language.isString(key), code + " has no " + key);
                }
            }
        }

        @Test
        @DisplayName("no page is long enough to overflow a written book")
        void pagesFit() {
            // A Minecraft book page holds roughly 256 characters of plain text; anything past
            // that is silently cut off by the client, which would take the loot rule with it.
            // Measured with the MiniMessage tags stripped, since those are not rendered.
            for (String code : List.of("en", "it")) {
                FileConfiguration language = language(code);
                for (String key : CivitasCommand.pageKeys()) {
                    String plain = language.getString(key, "").replaceAll("<[^>]*>", "");
                    assertTrue(plain.length() <= 340,
                            code + " " + key + " is " + plain.length()
                                    + " visible characters, which a book page will truncate");
                }
            }
        }

        @Test
        @DisplayName("the declared page list has no duplicates")
        void noDuplicatePages() {
            assertFalse(CivitasCommand.pageKeys().isEmpty());
            assertTrue(CivitasCommand.pageKeys().size()
                    == CivitasCommand.pageKeys().stream().distinct().count());
        }

        @Test
        @DisplayName("the page list is immutable")
        void immutable() {
            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> CivitasCommand.pageKeys().add("rules.page-extra"));
        }
    }
}
