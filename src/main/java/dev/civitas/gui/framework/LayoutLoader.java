package dev.civitas.gui.framework;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import dev.civitas.config.PluginResources;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reads the {@code gui/*.yml} layout files, SPEC 8.
 *
 * <h2>A bad file costs a button, never a screen</h2>
 * An operator editing YAML by hand will eventually write {@code DIMAOND}, or put two buttons
 * on slot 22, or ask for slot 60 of a 54-slot menu. Each of those is logged with the file and
 * the key that caused it and then skipped, and the screen opens without that button. Refusing
 * to open the menu at all would turn one typo into "the plugin is broken", and falling back
 * silently would leave them wondering why their edit did nothing.
 */
public final class LayoutLoader {

    private final PluginResources resources;
    private final Map<String, MenuLayout> cache = new ConcurrentHashMap<>();

    public LayoutLoader(PluginResources resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    /**
     * Loads a layout, copying the packaged copy out to disk on first run.
     *
     * @param name        the file name under {@code gui/}, such as {@code main.yml}
     * @param defaultSize the size to use if the file does not say
     */
    public MenuLayout load(String name, String fallbackTitleKey, int defaultSize) {
        return cache.computeIfAbsent(name, key -> read(key, fallbackTitleKey, defaultSize));
    }

    /** Drops the cache, so {@code /ca reload gui} picks up edits. */
    public void reload() {
        cache.clear();
    }

    private MenuLayout read(String name, String fallbackTitleKey, int defaultSize) {
        String resourcePath = "gui/" + name;
        File onDisk = new File(resources.dataFolder(), resourcePath);
        if (!onDisk.exists()) {
            copyResource(resourcePath, onDisk);
        }
        if (!onDisk.isFile()) {
            resources.logger().log(Level.WARNING,
                    "No layout file {0}; that screen will use its built-in defaults.", resourcePath);
            return MenuLayout.empty(fallbackTitleKey, defaultSize);
        }

        FileConfiguration file = YamlConfiguration.loadConfiguration(onDisk);
        readDefaults(resourcePath).ifPresent(defaults -> {
            file.setDefaults(defaults);
            file.options().copyDefaults(true);
        });

        return parse(resourcePath, file, fallbackTitleKey, defaultSize);
    }

    /** Visible for tests: parse a layout straight from a configuration. */
    public MenuLayout parse(String source, ConfigurationSection file, String fallbackTitleKey,
                            int defaultSize) {
        String titleKey = file.getString("title", fallbackTitleKey);
        int size = file.getInt("size", defaultSize);
        boolean bordered = file.getBoolean("border", true);

        if (size % 9 != 0 || size < 9 || size > 54) {
            resources.logger().log(Level.WARNING,
                    "{0}: size {1} is not a whole number of rows between 9 and 54; using {2}.",
                    new Object[] {source, size, defaultSize});
            size = defaultSize;
        }

        Map<String, MenuLayout.Entry> entries = new LinkedHashMap<>();
        Map<Integer, String> claimed = new LinkedHashMap<>();

        ConfigurationSection buttons = file.getConfigurationSection("buttons");
        if (buttons != null) {
            for (String key : buttons.getKeys(false)) {
                ConfigurationSection entry = buttons.getConfigurationSection(key);
                if (entry == null) {
                    warn(source, key, "is not a section");
                    continue;
                }
                parseEntry(source, key, entry, size, claimed).ifPresent(parsed -> {
                    entries.put(parsed.key(), parsed);
                    claimed.put(parsed.slot(), parsed.key());
                });
            }
        }

        return new MenuLayout(titleKey, size, bordered, entries);
    }

    private java.util.Optional<MenuLayout.Entry> parseEntry(String source, String key,
                                                            ConfigurationSection entry, int size,
                                                            Map<Integer, String> claimed) {
        if (!entry.isInt("slot")) {
            warn(source, key, "has no slot");
            return java.util.Optional.empty();
        }
        int slot = entry.getInt("slot");
        if (slot < 0 || slot >= size) {
            warn(source, key, "sits on slot " + slot + ", outside a " + size + "-slot menu");
            return java.util.Optional.empty();
        }
        String taken = claimed.get(slot);
        if (taken != null) {
            warn(source, key, "wants slot " + slot + ", which " + taken + " already has");
            return java.util.Optional.empty();
        }

        String materialName = entry.getString("material", "");
        Material material = Material.matchMaterial(materialName.toUpperCase(Locale.ROOT));
        if (material == null) {
            warn(source, key, "names an unknown material '" + materialName + "'");
            return java.util.Optional.empty();
        }
        if (!material.isItem()) {
            warn(source, key, "uses " + material + ", which cannot be shown in an inventory");
            return java.util.Optional.empty();
        }

        String labelKey = entry.getString("label");
        if (labelKey == null || labelKey.isBlank()) {
            warn(source, key, "has no label");
            return java.util.Optional.empty();
        }

        List<String> lore = entry.getStringList("lore");
        return java.util.Optional.of(
                new MenuLayout.Entry(key, slot, material, labelKey, lore));
    }

    private void warn(String source, String key, String problem) {
        resources.logger().log(Level.WARNING,
                "{0}: button ''{1}'' {2}; it will not be shown.",
                new Object[] {source, key, problem});
    }

    private void copyResource(String resourcePath, File target) {
        try (InputStream in = resources.resource(resourcePath)) {
            if (in == null) {
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

    private java.util.Optional<YamlConfiguration> readDefaults(String resourcePath) {
        try (InputStream in = resources.resource(resourcePath)) {
            if (in == null) {
                return java.util.Optional.empty();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return java.util.Optional.of(YamlConfiguration.loadConfiguration(reader));
            }
        } catch (IOException e) {
            resources.logger().log(Level.SEVERE,
                    "Failed to read packaged layout " + resourcePath, e);
            return java.util.Optional.empty();
        }
    }
}
