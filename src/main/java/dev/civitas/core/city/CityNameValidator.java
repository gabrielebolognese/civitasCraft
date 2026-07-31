package dev.civitas.core.city;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.util.Result;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Validates a proposed city name against SPEC 5.1 preconditions 4 and 5.
 *
 * <p>Length, pattern and blocked names are all config-driven, so an operator can widen or
 * narrow what is acceptable without a code change.
 */
public final class CityNameValidator {

    private final ConfigManager configs;

    public CityNameValidator(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /**
     * @param name the proposed name, exactly as the player typed it
     * @return the name on success, or a failure naming the rule it broke
     */
    public Result<String> validate(String name) {
        if (name == null || name.isBlank()) {
            return Result.failure("NAME_EMPTY", "city.create.name-invalid");
        }

        FileConfiguration cities = configs.get(ConfigFile.CITIES);
        int min = cities.getInt("creation.name-min-length", 3);
        int max = cities.getInt("creation.name-max-length", 24);

        if (name.length() < min || name.length() > max) {
            return Result.failure("NAME_LENGTH", "city.create.name-length",
                    Map.of("min", String.valueOf(min), "max", String.valueOf(max)));
        }

        String pattern = cities.getString("creation.name-pattern", "^[A-Za-z0-9_]+$");
        if (!matches(pattern, name)) {
            return Result.failure("NAME_PATTERN", "city.create.name-invalid");
        }

        String lowered = name.toLowerCase(Locale.ROOT);
        List<String> blocked = cities.getStringList("creation.blocked-names");
        for (String blockedName : blocked) {
            if (lowered.equals(blockedName.toLowerCase(Locale.ROOT))) {
                return Result.failure("NAME_BLOCKED", "city.create.name-blocked");
            }
        }

        return Result.success(name);
    }

    /**
     * A misconfigured pattern must not let every name through, so a syntax error is treated
     * as a rejection and reported rather than swallowed.
     */
    private boolean matches(String pattern, String name) {
        try {
            return Pattern.compile(pattern).matcher(name).matches();
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException(
                    "cities.yml creation.name-pattern is not a valid regex: " + pattern, e);
        }
    }

    /**
     * The tag auto-derived from a name when the founder supplies none: the first characters
     * of the name, upper-cased, trimmed to the {@code cities.tag} column width.
     */
    public static String deriveTag(String name) {
        String letters = name.replaceAll("[^A-Za-z0-9]", "");
        String source = letters.isEmpty() ? name : letters;
        return source.substring(0, Math.min(4, source.length())).toUpperCase(Locale.ROOT);
    }
}
