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
import dev.civitas.config.PluginResources;
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

    private final PluginResources resources;
    private final ConfigManager configs;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private FileConfiguration active = new YamlConfiguration();
    private FileConfiguration fallback = new YamlConfiguration();
    private String activeLanguage = FALLBACK_LANGUAGE;

    public LangManager(PluginResources resources, ConfigManager configs) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    public LangManager(Plugin plugin, ConfigManager configs) {
        this(PluginResources.of(plugin), configs);
    }

    /** Copies the bundled language files out of the jar and loads the configured one. */
    public void load() {
        for (String language : BUNDLED_LANGUAGES) {
            File onDisk = new File(new File(resources.dataFolder(), FOLDER), language + ".yml");
            if (!onDisk.exists()) {
                copyResource(FOLDER + "/" + language + ".yml", onDisk);
            }
        }

        activeLanguage = configs.get(ConfigFile.CONFIG).getString("language", FALLBACK_LANGUAGE);
        fallback = read(FALLBACK_LANGUAGE);
        active = FALLBACK_LANGUAGE.equals(activeLanguage) ? fallback : read(activeLanguage);
        validatePlaceholders();
    }

    /**
     * SPEC 23.7's startup placeholder check.
     *
     * <p>"Placeholders use {@code {name}} and are validated at startup. A missing or misspelled
     * placeholder logs a warning with the key name, and the message falls back to English rather
     * than rendering broken text."
     *
     * <p>Two things are checked, and the second is the one that catches real damage. A message
     * in the active language that names a placeholder English does not is almost always a
     * translator inventing one, which renders as literal {@code <thing>} to the player. A message
     * that is missing one English has is worse: the value simply vanishes, so an amount, a name
     * or a countdown is silently absent and the sentence still reads as a sentence.
     *
     * <p>This runs against the <b>shipped</b> pair, so an operator who edits their own copy is
     * told about it on the next start rather than by a player.
     */
    private void validatePlaceholders() {
        if (active == fallback) {
            return;
        }
        for (String key : fallback.getKeys(true)) {
            String english = fallback.getString(key);
            String translated = active.getString(key);
            if (english == null || translated == null) {
                // Missing keys are LangKeysTest's business, and the fallback already covers
                // them at runtime.
                continue;
            }
            java.util.Set<String> expected = placeholderNames(english);
            java.util.Set<String> found = placeholderNames(translated);

            java.util.Set<String> invented = new java.util.TreeSet<>(found);
            invented.removeAll(expected);
            if (!invented.isEmpty()) {
                resources.logger().log(Level.WARNING,
                        "{0}.yml key \"{1}\" uses placeholders English does not provide: {2}. "
                                + "They will render as literal text.",
                        new Object[] {activeLanguage, key, invented});
            }

            java.util.Set<String> dropped = new java.util.TreeSet<>(expected);
            dropped.removeAll(found);
            if (!dropped.isEmpty()) {
                resources.logger().log(Level.WARNING,
                        "{0}.yml key \"{1}\" drops placeholders English shows: {2}. The value "
                                + "will be missing from the message rather than wrong.",
                        new Object[] {activeLanguage, key, dropped});
            }
        }
    }

    /**
     * The placeholder names a message uses.
     *
     * <p>SPEC 23.7 writes them as {@code {name}}; this plugin renders through MiniMessage, where
     * a placeholder is {@code <name>}. Both are accepted, because an operator following SPEC's
     * own notation should be told their key is fine rather than warned about nothing.
     *
     * <p>MiniMessage's own tags are excluded by requiring a lowercase, dash-or-underscore name
     * that is not one of the palette or format tags — a warning about {@code <red>} would train
     * an operator to ignore the warnings.
     */
    static java.util.Set<String> placeholderNames(String message) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        java.util.regex.Matcher matcher = PLACEHOLDER.matcher(message);
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (name != null && !KNOWN_TAGS.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("<([a-z][a-z0-9_-]*)>|\\{([a-z][a-z0-9_-]*)}");

    /**
     * Tags that are styling rather than substitution.
     *
     * <p>The MiniMessage colours and decorations the language files already use, plus the SPEC
     * 23.2 palette tokens. Anything else inside angle brackets is a value somebody expects to
     * be filled in.
     */
    private static final java.util.Set<String> KNOWN_TAGS = java.util.Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white", "reset", "bold", "italic", "underlined", "strikethrough",
            "obfuscated", "newline", "br",
            "pos", "neg", "money", "subject", "body", "dim", "city", "land", "war", "quest",
            "ally", "admin", "link");

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
            resources.logger().log(Level.WARNING, "Missing language key: {0}", key);
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
    /**
     * A message rendered to plain text, for use as another message's placeholder value.
     *
     * <p>Plain rather than a component, so it goes through {@link #placeholder} and is inserted
     * unparsed. A translated fragment is still text somebody wrote into a yml, and the rule that
     * nothing substituted is ever re-parsed as MiniMessage has no exceptions.
     */
    public String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(get(key));
    }

    /**
     * A placeholder whose value is an already-rendered component.
     *
     * <p>For the narrow case where the value carries its own styling and came from
     * {@link #get} rather than from a player. Never use this for anything a player typed.
     */
    public static TagResolver component(String name, net.kyori.adventure.text.Component value) {
        return net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
                .component(name, value);
    }

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
            java.nio.file.Files.copy(in, target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            resources.logger().log(Level.SEVERE, "Failed to write " + target, e);
        }
    }

    private FileConfiguration read(String language) {
        File onDisk = new File(new File(resources.dataFolder(), FOLDER), language + ".yml");
        if (onDisk.exists()) {
            return YamlConfiguration.loadConfiguration(onDisk);
        }

        try (InputStream in = resources.resource(FOLDER + "/" + language + ".yml")) {
            if (in == null) {
                resources.logger().log(Level.SEVERE,
                        "Language ''{0}'' has no file on disk and none in the jar; falling back to {1}.",
                        new Object[] {language, FALLBACK_LANGUAGE});
                return new YamlConfiguration();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            resources.logger().log(Level.SEVERE, "Failed to read language file " + language, e);
            return new YamlConfiguration();
        }
    }
}
