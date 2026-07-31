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
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
        });
        loaded.put(file, config);
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
