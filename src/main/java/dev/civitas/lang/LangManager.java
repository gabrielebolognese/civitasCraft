package dev.civitas.lang;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Resolves message keys to MiniMessage components, per SPEC.md Section 2.1.
 *
 * <p>Lookup order is active language, then English, then a visible
 * {@code general.missing-message} placeholder. A missing translation therefore degrades to
 * English rather than to silence, and a missing key is loud rather than invisible.
 */
public final class LangManager {

    /** Language files shipped inside the jar. */
    public static final List<String> BUNDLED_LANGUAGES = List.of("en", "it");

    /** The language always used as the fallback tier. */
    public static final String FALLBACK_LANGUAGE = "en";

    private static final String FOLDER = "lang";

    private final Plugin plugin;
    private final ConfigManager configs;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private FileConfiguration active = new YamlConfiguration();
    private FileConfiguration fallback = new YamlConfiguration();
    private String activeLanguage = FALLBACK_LANGUAGE;

    public LangManager(Plugin plugin, ConfigManager configs) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /** Copies the bundled language files out of the jar and loads the configured one. */
    public void load() {
        for (String language : BUNDLED_LANGUAGES) {
            File onDisk = new File(new File(plugin.getDataFolder(), FOLDER), language + ".yml");
            if (!onDisk.exists()) {
                plugin.saveResource(FOLDER + "/" + language + ".yml", false);
            }
        }

        activeLanguage = configs.get(ConfigFile.CONFIG).getString("language", FALLBACK_LANGUAGE);
        fallback = read(FALLBACK_LANGUAGE);
        active = FALLBACK_LANGUAGE.equals(activeLanguage) ? fallback : read(activeLanguage);
    }

    /** Reloads the language files from disk, honouring a changed {@code language} setting. */
    public void reload() {
        load();
    }

    public String activeLanguage() {
        return activeLanguage;
    }

    /**
     * @param key        a constant from {@link Msg}
     * @param resolvers  placeholder resolvers, typically built with {@link #placeholder}
     * @return the rendered component, never {@code null}
     */
    public Component get(String key, TagResolver... resolvers) {
        String raw = rawOrNull(key);
        if (raw == null) {
            plugin.getLogger().log(Level.WARNING, "Missing language key: {0}", key);
            String template = rawOrNull(Msg.GENERAL_MISSING_MESSAGE);
            if (template == null) {
                return Component.text("Missing message: " + key);
            }
            return miniMessage.deserialize(template, placeholder("key", key));
        }
        return miniMessage.deserialize(raw, resolvers);
    }

    /** The configured prefix, prepended by {@link #send}. */
    public Component prefix() {
        return get(Msg.PREFIX);
    }

    /** Sends a prefixed message. */
    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(prefix().append(get(key, resolvers)));
    }

    /** Sends a message with no prefix, for multi-line output such as help pages and maps. */
    public void sendRaw(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(get(key, resolvers));
    }

    /**
     * Builds a placeholder that inserts {@code value} as literal text.
     *
     * <p>Deliberately unparsed: player names, city names and MOTDs are attacker-controlled
     * and must never be interpreted as MiniMessage.
     */
    public static TagResolver placeholder(String name, String value) {
        return Placeholder.unparsed(name, value);
    }

    /** Convenience for building several literal placeholders at once. */
    public static TagResolver[] placeholders(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> placeholder(e.getKey(), e.getValue()))
                .toArray(TagResolver[]::new);
    }

    private String rawOrNull(String key) {
        String value = active.getString(key);
        return value != null ? value : fallback.getString(key);
    }

    private FileConfiguration read(String language) {
        File onDisk = new File(new File(plugin.getDataFolder(), FOLDER), language + ".yml");
        if (onDisk.exists()) {
            return YamlConfiguration.loadConfiguration(onDisk);
        }

        try (InputStream in = plugin.getResource(FOLDER + "/" + language + ".yml")) {
            if (in == null) {
                plugin.getLogger().log(Level.SEVERE,
                        "Language ''{0}'' has no file on disk and none in the jar; falling back to {1}.",
                        new Object[] {language, FALLBACK_LANGUAGE});
                return new YamlConfiguration();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to read language file " + language, e);
            return new YamlConfiguration();
        }
    }
}
