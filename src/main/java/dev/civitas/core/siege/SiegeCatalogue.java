package dev.civitas.core.siege;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/**
 * SPEC 29.3's roster, read from {@code defense.yml}.
 *
 * <p>Same shape as {@code DefenseCatalogue} and for the same reason: every number SPEC states is
 * a config key, so an operator retunes a siege without a rebuild, and the code has no figures of
 * its own to disagree with the file.
 */
public final class SiegeCatalogue {

    private final ConfigManager configs;
    private final Logger logger;

    private final Map<String, SiegeUnitType> byKey = new LinkedHashMap<>();

    public SiegeCatalogue(ConfigManager configs, Logger logger) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** @return how many unit types loaded */
    public int load() {
        byKey.clear();
        ConfigurationSection units = configs.get(ConfigFile.DEFENSE)
                .getConfigurationSection("siege.units");
        if (units == null) {
            logger.warning("No siege.units section in defense.yml; siege units are unavailable.");
            return 0;
        }

        for (String key : units.getKeys(false)) {
            ConfigurationSection entry = units.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            try {
                byKey.put(key, new SiegeUnitType(
                        key,
                        entry.getString("display-name", key),
                        EntityType.valueOf(entry.getString("mob", "PILLAGER")),
                        entry.getDouble("health", 50),
                        entry.getDouble("damage", 0),
                        entry.getInt("points", 1),
                        new BigDecimal(entry.getString("cost", "0")),
                        entry.getDouble("range", 16),
                        entry.getDouble("damage-vs-units", 1.0),
                        entry.getDouble("damage-vs-players", 1.0),
                        entry.getInt("buff-radius", 0)));
            } catch (IllegalArgumentException e) {
                // Named rather than swallowed: a siege unit missing from the roster is a unit a
                // city cannot buy, and an operator should learn that at boot rather than from a
                // player asking why the list is short.
                logger.warning("Skipping siege unit '" + key + "': " + e.getMessage());
            }
        }
        return byKey.size();
    }

    public Optional<SiegeUnitType> byKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public java.util.Collection<SiegeUnitType> all() {
        return byKey.values();
    }

    public int size() {
        return byKey.size();
    }
}
