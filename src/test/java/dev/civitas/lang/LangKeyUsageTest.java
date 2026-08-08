package dev.civitas.lang;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every message key the Java code asks for must exist in the language files.
 *
 * <p>{@link LangKeysTest} proves the two shipped languages agree with each other. This proves
 * they agree with the code: a typo in a key is otherwise invisible until a player triggers
 * that exact branch and sees "Missing message" instead of an explanation.
 *
 * <p>Only two call shapes are scanned, deliberately. A blanket search for dotted string
 * literals would sweep up config paths such as {@code creation.cost} and fail on them.
 */
class LangKeyUsageTest {

    /** {@code lang.send(audience, "key")}, {@code lang.sendRaw(...)}, {@code lang.get("key")}. */
    private static final Pattern LANG_CALL = Pattern.compile(
            "\\blang\\.(?:send|sendRaw|get)\\s*\\(\\s*(?:[A-Za-z_][\\w.()]*\\s*,\\s*)?\"([^\"]+)\"");

    /** {@code Result.failure("REASON", "key")}, including the three-argument form. */
    private static final Pattern RESULT_FAILURE = Pattern.compile(
            "Result\\.(?:<[^>]+>)?failure\\s*\\(\\s*\"[A-Z0-9_]+\"\\s*,\\s*\"([^\"]+)\"");

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    private static FileConfiguration english() {
        return YamlConfiguration.loadConfiguration(languageFile("en"));
    }

    private static File languageFile(String language) {
        File file = new File("src/main/resources/lang/" + language + ".yml");
        assertTrue(file.isFile(), language + ".yml is missing");
        return file;
    }

    /** Any string literal in the source that has the shape of a message key. */
    private static final Pattern KEY_SHAPED_LITERAL =
            Pattern.compile("\"([a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)+)\"");

    /**
     * Keys reached through the two call shapes the patterns above recognise.
     *
     * <p>Used for the "does it exist" direction, where a false positive would break the
     * build over a config path that merely looks like a key.
     */
    private static Set<String> keysUsedInSource() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        forEachSourceFile(source -> {
            collect(LANG_CALL.matcher(source), keys);
            collect(RESULT_FAILURE.matcher(source), keys);
        });
        return keys;
    }

    /**
     * Every key-shaped literal anywhere in the source.
     *
     * <p>Used for the "is it orphaned" direction, where over-matching is harmless: a literal
     * only counts if it is also a key in {@code en.yml}, so config paths simply never match.
     * This catches keys reached through a ternary or passed to a helper, which the narrow
     * patterns cannot see.
     */
    private static Set<String> keyShapedLiterals() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        forEachSourceFile(source -> collect(KEY_SHAPED_LITERAL.matcher(source), keys));
        return keys;
    }

    private static void forEachSourceFile(java.util.function.Consumer<String> action)
            throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                action.accept(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
    }

    private static void collect(Matcher matcher, Set<String> into) {
        while (matcher.find()) {
            String key = matcher.group(1);
            // Skip anything that is plainly not a key, such as a format string.
            if (key.matches("[a-z][a-z0-9-]*(\\.[a-z0-9-]+)+")) {
                into.add(key);
            }
        }
    }

    @Test
    @DisplayName("every key the code asks for exists in en.yml")
    void everyUsedKeyExists() throws IOException {
        FileConfiguration english = english();
        Set<String> used = keysUsedInSource();

        assertTrue(used.size() > 20,
                "the scanner found only " + used.size() + " keys, which means it stopped matching");

        Set<String> missing = new TreeSet<>();
        for (String key : used) {
            String value = english.getString(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }

        assertTrue(missing.isEmpty(), "keys used in Java but absent from en.yml: " + missing);
    }

    @Test
    @DisplayName("every key declared in Msg exists too")
    void declaredConstantsExist() {
        FileConfiguration english = english();

        Set<String> missing = new TreeSet<>();
        for (String key : Msg.ALL) {
            if (english.getString(key) == null) {
                missing.add(key);
            }
        }

        assertTrue(missing.isEmpty(), "Msg constants absent from en.yml: " + missing);
    }

    /**
     * A key written with a dot in it silently destroys its own parent.
     *
     * <p>Bukkit treats {@code .} as a path separator when it loads YAML, so a file holding
     * both {@code treasury: "Treasury"} and {@code treasury.lore: "..."} ends up with a
     * <em>section</em> at {@code treasury}, and {@code getString} on it returns the section's
     * {@code toString}. The label a player sees becomes
     * {@code MemorySection[path='gui.main.treasury', root='YamlConfiguration']}.
     *
     * <p>Nothing else catches this: the key exists, the lookup returns a non-empty string, and
     * every other test passes. It is only visible to somebody looking at the screen. Fifty
     * labels were broken this way before this test existed, so the convention is now a hyphen:
     * {@code treasury-lore}, never {@code treasury.lore}.
     */
    @Test
    @DisplayName("no key the code asks for resolves to a section")
    void noRequestedKeyIsASection() throws IOException {
        Set<String> requested = keysUsedInSource();
        requested.addAll(Msg.ALL);

        for (String language : List.of("en", "it")) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(
                    languageFile(language));

            Set<String> broken = new TreeSet<>();
            for (String key : requested) {
                if (config.isConfigurationSection(key)) {
                    broken.add(language + ".yml: " + key);
                }
            }

            assertTrue(broken.isEmpty(),
                    "keys that would render as a MemorySection: " + broken);
        }
    }

    @Test
    @DisplayName("no message in en.yml is orphaned, so dead text does not accumulate")
    void noOrphanedMessages() throws IOException {
        FileConfiguration english = english();
        Set<String> used = keyShapedLiterals();
        used.addAll(Msg.ALL);
        // "prefix" has no dot, so it does not have the shape the scanner looks for; it is
        // referenced through Msg.PREFIX, which the line above already covers.

        Set<String> orphaned = new TreeSet<>();
        for (String key : english.getKeys(true)) {
            if (english.isConfigurationSection(key) || used.contains(key)) {
                continue;
            }
            if (isBuiltAtRuntime(key)) {
                continue;
            }
            orphaned.add(key);
        }

        assertTrue(orphaned.isEmpty(),
                "en.yml declares messages nothing asks for: " + orphaned);
    }

    /**
     * Families whose keys are assembled from data rather than written as literals.
     *
     * <p>A quest's description key is {@code "quest." + id} where the id comes from
     * {@code economy.yml}, so no literal for it exists anywhere in the source and the scanner
     * cannot see it. The prefix is listed instead. This is a deliberate hole in the orphan
     * check and the reason it is kept narrow: only families the code demonstrably builds by
     * concatenation belong here.
     */
    private static boolean isBuiltAtRuntime(String key) {
        return key.startsWith("quest.")
                || key.startsWith("challenge.")
                // A prefix's full and compact forms are built from Prefix.messageKey and
                // .compactMessageKey. Note "prefixes." with an s: the M0 "prefix" key is a
                // string, and nesting under it would make Bukkit read it as a section.
                || key.startsWith("prefixes.")
                // A notification category's description is built from
                // ToggleCategory.messageKey. The rest of the toggle.* keys are literals in
                // ToggleCommand and stay covered by the scanner.
                || key.startsWith("toggle.category-")
                // An upgrade's description key is built from its enum, UpgradeType.messageKey.
                || key.startsWith("upgrade.")
                // A unit's description key is built from its catalogue entry,
                // DefenseUnitType.messageKey.
                || key.startsWith("defense.unit.")
                // A relation's name key is built from its enum, Relation.messageKey.
                || key.startsWith("diplomacy.relation.")
                // A board's name and metric keys are built from its enum,
                // LeaderboardType.nameKey and .metricKey.
                || key.startsWith("leaderboard.type.")
                // A contest phase name is built from ContestState.messageKey, and a scoring
                // axis from VoteAxis.messageKey.
                || key.startsWith("contest.state.")
                || key.startsWith("contest.axis.")
                // An event's name and description are built from ServerEventType.nameKey
                // and .descriptionKey. The keys that are not per-event, such as
                // event.boss-bar, are literals and stay covered by the scanner.
                || isPerEventKey(key)
                // A war phase's name is built from WarState.messageKey.
                || key.startsWith("war.state.");
    }

    /**
     * {@code event.<one of the eight SPEC 13.5 keys>.name} and {@code .description}.
     *
     * <p>Matched against the enum rather than by prefix, so a typo in a key still fails: only
     * the two suffixes of an event that really exists are excused.
     */
    private static boolean isPerEventKey(String key) {
        for (dev.civitas.core.events.ServerEventType type
                : dev.civitas.core.events.ServerEventType.all()) {
            String prefix = "event." + type.key() + ".";
            if (key.equals(prefix + "name") || key.equals(prefix + "description")) {
                return true;
            }
        }
        return false;
    }
}
