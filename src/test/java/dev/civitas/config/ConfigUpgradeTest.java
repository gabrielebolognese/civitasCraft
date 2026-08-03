package dev.civitas.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What happens to an operator's config file when a plugin update adds keys to it.
 *
 * <p>This exists because the first boot of M9 on a server whose {@code economy.yml} predated
 * it produced a quest pool with no metrics and no targets, and every quest was refused. The
 * cause was subtle: Bukkit's {@code copyDefaults} makes a nested key <em>read as set</em>
 * while still resolving its value against the on-disk tree, where it is absent. So a value
 * added inside a section that already existed arrives empty rather than defaulted.
 *
 * <p>The tests below are written against that failure rather than against the fix, so they
 * would have caught it: they load a deliberately outdated file and ask for the new value.
 */
class ConfigUpgradeTest {

    @TempDir
    Path directory;

    private static Logger quiet() {
        Logger logger = Logger.getLogger("config-upgrade-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private ConfigManager load() {
        ConfigManager configs = new ConfigManager(
                PluginResources.ofClasspath(directory.toFile(), quiet()));
        configs.loadAll();
        return configs;
    }

    /** Writes an economy.yml as it stood before the quest pool existed. */
    private void givenOutdatedEconomy() throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("economy.yml"), """
                max-balance: 1000000000000
                decimal-places: 2
                currency-symbol: "C"

                income:
                  starting-balance: 2000
                  quests:
                    per-day: 3
                """, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a value added inside a section the operator already has still resolves")
    void nestedAdditionResolves() throws Exception {
        givenOutdatedEconomy();

        ConfigManager configs = load();

        // income.quests existed; income.quests.pool did not. This is the exact shape that
        // shipped broken.
        assertEquals("HARVEST_CROPS", configs.get(ConfigFile.ECONOMY)
                .getString("income.quests.pool.harvest-wheat.metric"));
        assertEquals(256, configs.get(ConfigFile.ECONOMY)
                .getInt("income.quests.pool.harvest-wheat.target"));
        assertNotNull(configs.get(ConfigFile.ECONOMY)
                .getConfigurationSection("income.challenges.pool"));
    }

    @Test
    @DisplayName("the operator's own values are never overwritten by the packaged ones")
    void operatorValuesWin() throws Exception {
        givenOutdatedEconomy();

        ConfigManager configs = load();

        assertEquals(2000, configs.get(ConfigFile.ECONOMY).getInt("income.starting-balance"));
        assertEquals(3, configs.get(ConfigFile.ECONOMY).getInt("income.quests.per-day"));
    }

    @Test
    @DisplayName("a changed value the operator set is kept, not reset to the default")
    void changedValueIsKept() throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("economy.yml"), """
                income:
                  starting-balance: 99999
                """, StandardCharsets.UTF_8);

        ConfigManager configs = load();

        assertEquals(99999, configs.get(ConfigFile.ECONOMY).getInt("income.starting-balance"),
                "an upgrade must never undo a server's tuning");
    }

    @Test
    @DisplayName("the new keys are written back, so an operator can see and edit them")
    void newKeysAreWrittenToDisk() throws Exception {
        givenOutdatedEconomy();

        load();

        String onDisk = Files.readString(directory.resolve("economy.yml"),
                StandardCharsets.UTF_8);
        assertTrue(onDisk.contains("HARVEST_CROPS"),
                "the pool should now be in the operator's file");
    }

    @Test
    @DisplayName("a fresh install writes every packaged file out untouched")
    void freshInstall() {
        ConfigManager configs = load();

        for (ConfigFile file : ConfigFile.values()) {
            assertTrue(new File(directory.toFile(), file.fileName()).isFile(),
                    file.fileName() + " was not created");
        }
        assertEquals("HARVEST_CROPS", configs.get(ConfigFile.ECONOMY)
                .getString("income.quests.pool.harvest-wheat.metric"));
    }
}
