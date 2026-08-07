package dev.civitas.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every key this plugin ships must be read by something.
 *
 * <p>The counterpart to {@link dev.civitas.lang.LangKeyUsageTest}, and written for the same
 * reason a milestone later. {@code LangKeysTest} proves a message key the code asks for
 * exists; nothing proved the reverse for configuration, and the reverse is where the damage
 * is: <b>a key with nothing behind it is indistinguishable from a working feature.</b> An
 * operator reads the file, sees the setting with SPEC's own comment beside it, changes it, and
 * nothing happens. They have no way to tell that from having typed it wrong.
 *
 * <h2>What this caught when it was written</h2>
 *
 * <p>It is not a hypothetical. SPEC 17.1's inactivity rules hid behind exactly this for
 * twenty-one milestones — {@code cities.yml} shipped all four of their numbers, commented with
 * the SPEC case they implemented, and no code read any of them. Writing this test found
 * eighteen more, of which the worst two were not dead keys but <b>mismatched pairs</b>: the
 * file shipped {@code scoring.city-hall-hold-seconds} while the code read
 * {@code scoring.city-hall-reach-seconds}, so the operator's key did nothing and the code's
 * key did not exist, leaving the value permanently at its hardcoded default. That failure is
 * invisible from either side on its own.
 *
 * <h2>How "read" is decided</h2>
 *
 * <p>A key counts as read if any suffix of its path appears as a string literal in
 * {@code src/main/java}. Suffixes, because paths are routinely built —
 * {@code defense.getDouble(path + ".speed")} for a unit, {@code section.getInt("weight")} for
 * an iterated rank. That is deliberately generous: this test exists to catch a key whose name
 * appears <em>nowhere</em>, which is the strongest cheap signal and the one all nineteen real
 * findings tripped. It cannot catch a dead key whose leaf name coincides with a live one
 * elsewhere, and does not claim to.
 */
class ConfigKeyUsageTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path RESOURCES = Path.of("src/main/resources");

    /** The operator-facing files. {@code plugin.yml} is Bukkit's, and gui/lang have own tests. */
    private static final List<String> SHIPPED = List.of(
            "cities.yml", "config.yml", "defense.yml", "economy.yml", "events.yml", "war.yml");

    /**
     * Keys that are data rather than settings, so no code names them.
     *
     * <p>Every entry is a value inside a section the code iterates with
     * {@code getKeys(false)}: the enchantment is looked up by whatever name the operator wrote,
     * so adding SHARPNESS to a unit works without a code change and naming POWER in Java would
     * defeat the point. Kept short on purpose — an allow-list is where a test like this goes
     * to die, and anything added to it should be a value an operator invents, never a setting
     * somebody forgot to wire.
     */
    private static final Set<String> DATA_NOT_SETTINGS = new TreeSet<>(Set.of(
            "defense.yml:units.archer.equipment.main-hand-enchantments.POWER",
            "defense.yml:units.sharpshooter.equipment.main-hand-enchantments.POWER"));

    private static String mainSource() {
        StringBuilder source = new StringBuilder();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                source.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return source.toString();
    }

    /** Every leaf path in a configuration, dot-separated. */
    private static List<String> leafKeys(ConfigurationSection section) {
        List<String> keys = new ArrayList<>();
        for (String path : section.getKeys(true)) {
            if (!section.isConfigurationSection(path)) {
                keys.add(path);
            }
        }
        return keys;
    }

    private static boolean isRead(String key, String source) {
        String[] parts = key.split("\\.");
        for (int from = 0; from < parts.length; from++) {
            String suffix = String.join(".", java.util.Arrays.copyOfRange(parts, from, parts.length));
            if (source.contains("\"" + suffix + "\"") || source.contains("\"." + suffix + "\"")) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("every shipped config key is read by something")
    void everyKeyHasAReader() {
        String source = mainSource();
        List<String> dead = new ArrayList<>();
        int total = 0;

        for (String file : SHIPPED) {
            Path path = RESOURCES.resolve(file);
            assertTrue(Files.isRegularFile(path), file + " is missing");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());

            for (String key : leafKeys(yaml)) {
                total++;
                if (DATA_NOT_SETTINGS.contains(file + ":" + key)) {
                    continue;
                }
                if (!isRead(key, source)) {
                    dead.add(file + ": " + key);
                }
            }
        }

        assertTrue(total > 400, "only found " + total + " keys, so the scan is broken");
        assertTrue(dead.isEmpty(),
                "these keys are shipped to operators and read by nothing. Wire each one, or "
                        + "delete it — a setting that silently does nothing is worse than an "
                        + "absent one, because the operator cannot tell it from a typo:\n  "
                        + String.join("\n  ", dead));
    }

    @Test
    @DisplayName("no two files ship the same setting under different names")
    void noDeadTwins() {
        // The other half of the same failure. A key can have a reader and still be a lie, if
        // the value the code actually uses lives somewhere else: war.yml shipped
        // allies.wager-percent-of-primary beside rewards.ally-wager-percent, and only the
        // second was read. An operator who found the first — the one with SPEC 11.10's comment
        // on it — and changed it got no change at all.
        Map<String, String> byLeaf = new java.util.LinkedHashMap<>();
        List<String> twins = new ArrayList<>();

        for (String file : SHIPPED) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    RESOURCES.resolve(file).toFile());
            for (String key : leafKeys(yaml)) {
                String leaf = key.substring(key.lastIndexOf('.') + 1);
                if (!SUSPICIOUS_LEAVES.contains(leaf)) {
                    continue;
                }
                String previous = byLeaf.put(leaf, file + ": " + key);
                if (previous != null) {
                    twins.add(leaf + " appears as " + previous + " and " + file + ": " + key);
                }
            }
        }

        assertTrue(twins.isEmpty(), "the same setting is shipped twice:\n  "
                + String.join("\n  ", twins));
    }

    /**
     * Leaf names specific enough that two of them means a duplicated setting.
     *
     * <p>Not every repeated leaf is a twin — {@code enabled} and {@code cost} appear all over
     * and mean different things in each place. These are the ones that named one concept.
     */
    private static final Set<String> SUSPICIOUS_LEAVES = new LinkedHashSet<>(List.of(
            "restore-window-days", "winner-market-bonus-percent", "winner-market-bonus-days",
            "ally-wager-percent", "wager-percent-of-primary", "peace-forfeit-percent",
            "forfeit-percent", "city-hall-hold-seconds", "city-hall-reach-seconds"));

    @Test
    @DisplayName("the allow-list only excuses values an operator invents")
    void allowListStaysHonest() {
        // An allow-list is where a test like this goes to die. Every entry must still exist,
        // so an excuse cannot outlive the key it was written for.
        for (String entry : DATA_NOT_SETTINGS) {
            String[] split = entry.split(":", 2);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    RESOURCES.resolve(split[0]).toFile());
            assertTrue(yaml.contains(split[1]),
                    "the allow-list excuses " + entry + ", which no longer exists");
        }
    }
}
