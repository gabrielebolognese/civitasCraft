package dev.civitas.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    private final PluginResources resources;
    private final Map<ConfigFile, FileConfiguration> loaded = new EnumMap<>(ConfigFile.class);

    public ConfigManager(PluginResources resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public ConfigManager(Plugin plugin) {
        this(PluginResources.of(plugin));
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
        File onDisk = new File(resources.dataFolder(), file.fileName());
        if (!onDisk.exists()) {
            copyResource(file.fileName(), onDisk);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(onDisk);
        readDefaultsFromJar(file).ifPresent(defaults -> {
            // Fill in anything the operator's file has never heard of, before the defaults
            // are attached: once they are, every key reads as set whether it is or not.
            writeMissingKeys(config, defaults, onDisk);
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
        });
        loaded.put(file, config);
    }

    /**
     * Copies keys the operator's file has never heard of into it, and saves.
     *
     * <p>{@code copyDefaults} alone is not enough, and the way it falls short is subtle
     * enough to have shipped a broken quest pool: a value nested under a section that
     * <em>does</em> exist on disk resolves its keys from the defaults but its values against
     * the on-disk tree, where they are absent. Reading such a key gives the fallback rather
     * than the packaged default, so a plugin update that adds a block inside an existing one
     * arrives empty.
     *
     * <p>Writing the missing keys out fixes it once, for every config, and has the side
     * benefit that an operator can see and edit what a new version added.
     */
    private void writeMissingKeys(FileConfiguration config, YamlConfiguration defaults,
                                  File onDisk) {
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || config.contains(key, true)) {
                continue;
            }
            config.set(key, defaults.get(key));
            changed = true;
        }
        if (!changed) {
            return;
        }
        try {
            config.save(onDisk);
        } catch (IOException e) {
            // Not fatal: the values are correct in memory for this run, and the operator's
            // file is simply not updated.
            resources.logger().log(Level.WARNING,
                    "Could not write new configuration keys into " + onDisk, e);
        }
    }

    /**
     * Writes a packaged default out to disk.
     *
     * <p>Done here rather than through {@code Plugin.saveResource} so that config loading
     * depends only on {@link PluginResources}.
     */
    private void copyResource(String resourcePath, File target) {
        try (InputStream in = resources.resource(resourcePath)) {
            if (in == null) {
                resources.logger().log(Level.WARNING,
                        "No packaged copy of {0}; it will not be created on disk.", resourcePath);
                return;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create " + parent);
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            resources.logger().log(Level.SEVERE, "Failed to write " + target, e);
        }
    }

    private Optional<YamlConfiguration> readDefaultsFromJar(ConfigFile file) {
        try (InputStream in = resources.resource(file.fileName())) {
            if (in == null) {
                resources.logger().log(Level.WARNING,
                        "No packaged defaults for {0}; missing keys will not resolve.", file.fileName());
                return Optional.empty();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return Optional.of(YamlConfiguration.loadConfiguration(reader));
            }
        } catch (IOException e) {
            resources.logger().log(Level.SEVERE,
                    "Failed to read packaged defaults for " + file.fileName(), e);
            return Optional.empty();
        }
    }
}
