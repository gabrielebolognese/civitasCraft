package dev.civitas.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SPEC 2.1: no hardcoded player-facing strings, and every string exists in every language.
 *
 * <p>Translation drift is the failure this catches: a key added to {@code en.yml} but not to
 * {@code it.yml} silently serves English to Italian players, which nobody notices until a
 * player complains. Here it fails the build.
 */
class LangKeysTest {

    private static final File LANG_DIR = new File("src/main/resources/lang");

    /**
     * No language file contains mojibake.
     *
     * <p>Written at M7a after {@code Citt\u00c3 } went into {@code it.yml}, and widened at M3b
     * after {@code pu\u00c3\u00b2} went in the same way and this test <b>did not catch it</b>. The
     * first version looked for U+FFFD and U+00A0, which are the two characters that particular
     * accident happened to produce. That is not the class of defect, it is one instance of it.
     *
     * <h2>The actual signature</h2>
     *
     * <p>A UTF-8 two-byte sequence is a lead byte in {@code C2..DF} followed by a continuation
     * byte in {@code 80..BF}. Read as Latin-1, that becomes a character in U+00C2..U+00DF
     * followed by one in U+0080..U+00BF — so {@code \u00e0} becomes {@code \u00c3 } and
     * {@code \u00f2} becomes {@code \u00c3\u00b2}. Looking for the <b>pair</b> catches every
     * accented character rather than the ones somebody already tripped over.
     *
     * <p>No false positives to worry about: real prose in either shipped language never puts a
     * capital A-with-diacritic immediately before a Latin-1 punctuation or control character.
     */
    @Test
    @DisplayName("no language file contains mojibake")
    void noMojibake() {
        for (String language : LangManager.BUNDLED_LANGUAGES) {
            File file = new File(LANG_DIR, language + ".yml");
            String text;
            try {
                text = java.nio.file.Files.readString(file.toPath(),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new AssertionError(language + ".yml is not readable as UTF-8", e);
            }

            List<String> offenders = new ArrayList<>();
            String[] lines = text.split("\\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                for (int c = 0; c < line.length(); c++) {
                    char here = line.charAt(c);
                    // What a decoder writes when it gives up.
                    if (here == '\uFFFD') {
                        offenders.add(language + ".yml:" + (i + 1)
                                + " contains a replacement character");
                        break;
                    }
                    // A UTF-8 lead byte followed by a continuation byte, both read as Latin-1.
                    if (here >= '\u00C2' && here <= '\u00DF'
                            && c + 1 < line.length()) {
                        char next = line.charAt(c + 1);
                        if (next >= '\u0080' && next <= '\u00BF') {
                            offenders.add(language + ".yml:" + (i + 1) + " has U+"
                                    + String.format("%04X", (int) here) + " U+"
                                    + String.format("%04X", (int) next)
                                    + ", which is a UTF-8 pair read as Latin-1");
                            break;
                        }
                    }
                }
            }
            assertTrue(offenders.isEmpty(),
                    "these lines look like a string that went through the wrong codec: "
                            + offenders);
        }
    }

    private static FileConfiguration load(String language) {
        File onDisk = new File(LANG_DIR, language + ".yml");
        assertTrue(onDisk.isFile(), "Missing language file: " + language + ".yml");
        return YamlConfiguration.loadConfiguration(onDisk);
    }

    /** Leaf keys only; a section such as {@code command} is not itself a message. */
    private static Set<String> leafKeys(FileConfiguration config) {
        Set<String> leaves = new TreeSet<>();
        for (String key : config.getKeys(true)) {
            if (!config.isConfigurationSection(key)) {
                leaves.add(key);
            }
        }
        return leaves;
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "it"})
    @DisplayName("every key referenced by Java exists in every shipped language")
    void everyDeclaredKeyExists(String language) {
        FileConfiguration config = load(language);

        Set<String> missing = new LinkedHashSet<>();
        for (String key : Msg.ALL) {
            String value = config.getString(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }

        assertTrue(missing.isEmpty(), language + ".yml is missing message keys: " + missing);
    }

    @Test
    @DisplayName("the shipped languages define exactly the same set of keys")
    void languagesAgreeOnKeys() {
        assertEquals(leafKeys(load("en")), leafKeys(load("it")),
                "en.yml and it.yml have diverged; every message must exist in both");
    }

    @Test
    @DisplayName("LangManager ships every language it declares as bundled")
    void bundledLanguagesArePackaged() {
        for (String language : LangManager.BUNDLED_LANGUAGES) {
            assertTrue(new File(LANG_DIR, language + ".yml").isFile(),
                    "LangManager declares '" + language + "' but no such file is packaged");
        }
        assertTrue(LangManager.BUNDLED_LANGUAGES.contains(LangManager.FALLBACK_LANGUAGE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "it"})
    @DisplayName("every message parses as MiniMessage and uses no legacy colour codes")
    void messagesAreValidMiniMessage(String language) {
        FileConfiguration config = load(language);
        MiniMessage miniMessage = MiniMessage.miniMessage();

        for (String key : leafKeys(config)) {
            String raw = config.getString(key);
            assertNotNull(raw, key);
            assertTrue(raw.indexOf('§') < 0 && !raw.matches(".*&[0-9a-fk-orA-FK-OR].*"),
                    language + ".yml key '" + key + "' uses a legacy colour code; MiniMessage only");

            Component rendered = miniMessage.deserialize(raw);
            assertNotNull(rendered, "MiniMessage returned null for " + key);
        }
    }

    @Test
    @DisplayName("placeholders in a message survive rendering as literal, uninterpreted text")
    void placeholdersRenderLiterally() {
        FileConfiguration config = load("en");
        String template = config.getString(Msg.COMMAND_NOT_IMPLEMENTED);
        assertNotNull(template);

        // A city name is attacker-controlled. It must never be parsed as MiniMessage.
        Component rendered = MiniMessage.miniMessage().deserialize(template,
                LangManager.placeholder("command", "<red>injected"),
                LangManager.placeholder("milestone", "M2"));

        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);
        assertTrue(plain.contains("<red>injected"),
                "placeholder values must render literally, got: " + plain);
        assertTrue(plain.contains("M2"));
    }
}
