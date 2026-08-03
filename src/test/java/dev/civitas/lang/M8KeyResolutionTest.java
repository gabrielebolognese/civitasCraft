package dev.civitas.lang;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A guard against a YAML shape that reads back differently from how it was written.
 *
 * <p>Bukkit's configuration treats a dot as a path separator, so a key written as
 * {@code overview.lore} nested inside {@code main:} is a different thing from a nested
 * {@code overview: { lore: ... }}. {@link LangKeysTest} walks {@code getKeys(true)}, which
 * joins nested keys with dots and so cannot tell the two apart. This asks the question the
 * plugin actually asks at runtime: does {@code getString(key)} return the text.
 */
class M8KeyResolutionTest {

    private static FileConfiguration language(String file) {
        File onDisk = new File("src/main/resources/lang/" + file);
        return YamlConfiguration.loadConfiguration(onDisk);
    }

    @ParameterizedTest(name = "{0} resolves in both languages")
    @ValueSource(strings = {
            "gui.main.title",
            "gui.main.overview",
            "gui.main.overview.lore",
            "gui.main.treasury.lore",
            "gui.claims.claim.base",
            "gui.claims.claim.total",
            "gui.claims.map.coords",
            "gui.treasury.upkeep.daily",
            "gui.treasury.deposit.lore",
            "gui.members.entry-online",
            "gui.member-actions.kick.confirm",
            "gui.permissions.cannot-grant",
            "gui.settings.disband.confirm",
            "gui.settings.motd.current",
            "gui.overview.motd-value",
            "city.hall.item-name",
            "city.hall.cannot-break",
            "city.spawn.warmup",
            "city.spawn.outside-claims"})
    @DisplayName("every M8 key resolves through getString, not merely through getKeys")
    void resolves(String key) {
        assertNotNull(language("en.yml").getString(key), "en.yml is missing " + key);
        assertNotNull(language("it.yml").getString(key), "it.yml is missing " + key);
    }

    @Test
    @DisplayName("a key written with a dot inside a section still reads back")
    void dottedKeysAreReachable() {
        // The shape this test exists to catch: if this ever returns null, the language files
        // have been written in a way the plugin cannot read, whatever the other tests say.
        assertNotNull(language("en.yml").getString("gui.claims.unclaim.refund"));
        assertNotNull(language("en.yml").getString("gui.rank-picker.weight"));
    }
}
