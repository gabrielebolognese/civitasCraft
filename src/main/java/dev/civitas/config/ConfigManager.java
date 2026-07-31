package dev.civitas.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Loads and reloads every {@link ConfigFile}.
 *
 * <p>Each file is copied out of the jar on first run. On every load the in-jar copy is
 * also installed as the defaults tree, so a key added by a plugin update resolves to its
 * documented default even when the operator's on-disk file predates it. That is what makes
 * "no hardcoded numbers" safe: a missing key degrades to the SPEC default, not to zero.
 */
public final class ConfigManager {

    private final Plugin plugin;
    private final Map<ConfigFile, FileConfiguration> loaded = new EnumMap<>(ConfigFile.class);

    public ConfigManager(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Loads every configuration file, creating any that are missing. */
    public void loadAll() {
        for (ConfigFile file : ConfigFile.values()) {
            load(file);
        }
    }

    /** Reloads every configuration file from disk. */
    public void reloadAll() {
        loaded.clear();
        loadAll();
    }

    /** Reloads a single configuration file from disk. */
    public void reload(ConfigFile file) {
        loaded.remove(file);
        load(file);
    }

    /**
     * @param file which configuration to read
     * @return the loaded configuration, never {@code null} once {@link #loadAll()} has run
     */
    public FileConfiguration get(ConfigFile file) {
        FileConfiguration config = loaded.get(file);
        if (config == null) {
            throw new IllegalStateException("Config " + file.fileName() + " was read before it was loaded");
        }
        return config;
    }

    private void load(ConfigFile file) {
        File onDisk = new File(plugin.getDataFolder(), file.fileName());
        if (!onDisk.exists()) {
            plugin.saveResource(file.fileName(), false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(onDisk);
        readDefaultsFromJar(file).ifPresent(defaults -> {
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
        });
        loaded.put(file, config);
    }

    private java.util.Optional<YamlConfiguration> readDefaultsFromJar(ConfigFile file) {
        try (InputStream in = plugin.getResource(file.fileName())) {
            if (in == null) {
                plugin.getLogger().log(Level.WARNING,
                        "No packaged defaults for {0}; missing keys will not resolve.", file.fileName());
                return java.util.Optional.empty();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return java.util.Optional.of(YamlConfiguration.loadConfiguration(reader));
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to read packaged defaults for " + file.fileName(), e);
            return java.util.Optional.empty();
        }
    }
}
