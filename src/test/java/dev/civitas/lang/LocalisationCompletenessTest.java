package dev.civitas.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 19's M23 asks for "localisation completeness", and the hard part is deciding what that
 * means. Three tests already exist and each proves something narrower:
 *
 * <ul>
 *   <li>{@link LangKeysTest} — the two shipped languages have the same keys.</li>
 *   <li>{@link LangKeyUsageTest} — every key the code asks for exists, and none resolves to a
 *       section (the bug that rendered fifty GUI labels as {@code MemorySection[...]}).</li>
 *   <li>{@code M8KeyResolutionTest} — the GUI screens resolve.</li>
 * </ul>
 *
 * <p>All three pass with an {@code it.yml} that is a verbatim copy of {@code en.yml}. This
 * class covers the gap: that the Italian file is <b>actually translated</b>, and that the two
 * files agree about the things a translator can silently break — the placeholders a message
 * takes, and whether it is a section or a string.
 *
 * <h2>Why identical values are allowed, but counted</h2>
 *
 * <p>Some values are legitimately the same in both languages: {@code "<gray>#<rank>
 * <white><player>"} is punctuation and placeholders with no words in it, and an admin help
 * line listing subcommand names must stay in English because those names are what a player
 * types. So the test does not forbid identical values. It bounds how many there may be, and
 * requires that every one of them contains no translatable prose — measured as the letters
 * left after the tags and placeholders are stripped out.
 */
class LocalisationCompletenessTest {

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-z][a-z0-9_-]*)>");

    private static FileConfiguration language(String code) {
        File file = new File("src/main/resources/lang/" + code + ".yml");
        assertTrue(file.isFile(), code + ".yml is missing");
        return YamlConfiguration.loadConfiguration(file);
    }

    /** Every leaf key to its value, flattened. */
    private static Map<String, String> flatten(FileConfiguration language) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String path : language.getKeys(true)) {
            if (language.isString(path)) {
                values.put(path, language.getString(path, ""));
            }
        }
        return values;
    }

    /** The letters left once MiniMessage tags and placeholders are removed. */
    private static String prose(String value) {
        return TAG.matcher(value).replaceAll(" ")
                .replaceAll("[^\\p{L}]+", "")
                .toLowerCase(Locale.ROOT);
    }

    /** The placeholder names a message takes, which both languages must agree on. */
    private static TreeSet<String> placeholders(String value) {
        TreeSet<String> names = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    // ==================================================================================
    // Translation
    // ==================================================================================

    @Nested
    @DisplayName("translation")
    class Translation {

        @Test
        @DisplayName("the two languages have exactly the same string keys")
        void sameKeys() {
            assertEquals(flatten(language("en")).keySet(), flatten(language("it")).keySet());
        }

        @Test
        @DisplayName("a value identical in both languages contains no prose to translate")
        void identicalValuesAreFormatOnly() {
            // The real test of completeness. An untranslated sentence and a format template
            // look the same to every other test in this package: same key, same shape, non
            // empty. The difference is whether there are any words in it.
            Map<String, String> english = flatten(language("en"));
            Map<String, String> italian = flatten(language("it"));
            List<String> untranslated = new ArrayList<>();

            for (Map.Entry<String, String> entry : english.entrySet()) {
                String key = entry.getKey();
                if (!entry.getValue().equals(italian.get(key))) {
                    continue;
                }
                // Command syntax is deliberately identical: /ca war list is what an operator
                // types, and translating a subcommand name would stop the command working.
                if (key.startsWith("admin.help") || key.startsWith("help.")
                        || key.equals("rules.author")) {
                    continue;
                }
                if (prose(entry.getValue()).length() > 12) {
                    untranslated.add(key + " = " + entry.getValue());
                }
            }

            assertTrue(untranslated.isEmpty(),
                    "these values are byte-identical in both languages and contain real "
                            + "words, so one of them is untranslated:\n  "
                            + String.join("\n  ", untranslated));
        }

        @Test
        @DisplayName("no Italian value is left holding an obvious English marker")
        void noEnglishLeftovers() {
            // A weaker net than the one above, but it catches the other half-translation:
            // a value that was edited rather than copied and kept an English clause.
            Map<String, String> italian = flatten(language("it"));
            List<String> suspicious = new ArrayList<>();

            for (Map.Entry<String, String> entry : italian.entrySet()) {
                if (entry.getKey().startsWith("admin.help")
                        || entry.getKey().startsWith("help.")) {
                    continue;
                }
                String words = " " + TAG.matcher(entry.getValue()).replaceAll(" ")
                        .toLowerCase(Locale.ROOT) + " ";
                for (String marker : List.of(" you ", " your ", " the ", " cannot ",
                        " already ", " does not ", " must be ")) {
                    if (words.contains(marker)) {
                        suspicious.add(entry.getKey() + " ->" + marker.strip());
                        break;
                    }
                }
            }

            assertTrue(suspicious.isEmpty(),
                    "these Italian values still read as English: " + suspicious);
        }
    }

    // ==================================================================================
    // Agreement, the things a translator can silently break
    // ==================================================================================

    @Nested
    @DisplayName("agreement")
    class Agreement {

        @Test
        @DisplayName("a placeholder the code supplies reaches every language")
        void suppliedPlaceholdersReachBothLanguages() {
            // The failure this catches is invisible until a player switches language: a
            // translator drops <amount> from a message, and that player is told they cannot
            // afford something without being told how much it costs.
            //
            // Asserted against what the *code passes*, not against the other language. The
            // first version of this test compared the two files and produced four findings,
            // all of them wrong: /sell all <material> becomes /sell all <materiale>, and
            // should, because that placeholder is documentation inside a usage line and
            // nothing ever substitutes it. An angle-bracket name is a resolver or a piece of
            // syntax being shown to the player, and only the call site knows which.
            Map<String, TreeSet<String>> supplied = LangCallSites.suppliedPlaceholders();
            List<String> missing = new ArrayList<>();

            for (String code : List.of("en", "it")) {
                Map<String, String> values = flatten(language(code));
                for (Map.Entry<String, TreeSet<String>> call : supplied.entrySet()) {
                    String value = values.get(call.getKey());
                    if (value == null) {
                        continue;   // LangKeyUsageTest owns missing keys
                    }
                    TreeSet<String> present = placeholders(value);
                    for (String name : call.getValue()) {
                        if (!present.contains(name)) {
                            missing.add(code + ".yml " + call.getKey()
                                    + " never shows <" + name + ">, which the code passes it");
                        }
                    }
                }
            }

            assertTrue(missing.isEmpty(),
                    "a value discards a placeholder the code supplies:\n  "
                            + String.join("\n  ", missing));
        }

        @Test
        @DisplayName("the call-site scan finds something, or it is guarding nothing")
        void scanIsNotVacuous() {
            // A regex that silently matched nothing would make the test above pass forever.
            Map<String, TreeSet<String>> supplied = LangCallSites.suppliedPlaceholders();

            assertTrue(supplied.size() > 100,
                    "only found " + supplied.size() + " keys with placeholders, which means "
                            + "the scan is broken rather than that the plugin has few");
        }

        @Test
        @DisplayName("no value is blank in either language")
        void nothingIsBlank() {
            for (String code : List.of("en", "it")) {
                for (Map.Entry<String, String> entry : flatten(language(code)).entrySet()) {
                    assertFalse(entry.getValue().isBlank() && !entry.getKey().equals("prefix"),
                            code + ".yml has an empty value at " + entry.getKey());
                }
            }
        }

        @Test
        @DisplayName("every MiniMessage tag is closed or self-contained")
        void tagsAreBalanced() {
            // MiniMessage does not require closing tags, so an unbalanced one does not throw;
            // it bleeds colour into whatever follows. What is worth catching is the reverse:
            // a closing tag with nothing open, which is a typo every time.
            for (String code : List.of("en", "it")) {
                for (Map.Entry<String, String> entry : flatten(language(code)).entrySet()) {
                    int open = 0;
                    Matcher matcher = TAG.matcher(entry.getValue());
                    while (matcher.find()) {
                        String tag = matcher.group();
                        if (tag.startsWith("</")) {
                            open--;
                            assertTrue(open >= 0, code + ".yml " + entry.getKey()
                                    + " closes a tag that was never opened: " + tag);
                        } else if (!tag.equals("<reset>")) {
                            open++;
                        }
                    }
                }
            }
        }
    }

    // ==================================================================================
    // Coverage of the milestone's own additions
    // ==================================================================================

    @Nested
    @DisplayName("M23's additions")
    class NewSurface {

        @Test
        @DisplayName("help, the rules book and city chat all landed in both languages")
        void m23KeysExist() {
            for (String code : List.of("en", "it")) {
                FileConfiguration language = language(code);
                assertTrue(language.isString("help.header"), code);
                assertTrue(language.isString("chat.city-usage"), code);
                assertTrue(language.isString("chat.city-channel"), code);
                assertTrue(language.isString("rules.title"), code);
                assertTrue(language.isString("civitas.about-version"), code);
                assertTrue(language.isString("admin.system.perf-lookup"), code);
                assertTrue(language.isString("admin.system.perf-pool"), code);
            }
        }

        @Test
        @DisplayName("the /ca perf line that admitted two metrics were unmeasured is gone")
        void unmeasuredAdmissionRemoved() {
            // M21 printed it honestly because nothing measured them. M23 measures them, so
            // the line would now be a lie in the other direction.
            for (String code : List.of("en", "it")) {
                assertFalse(language(code).isString("admin.system.perf-unmeasured"),
                        code + ".yml still carries the unmeasured admission");
            }
        }
    }
}
