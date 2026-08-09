package dev.civitas.core.defense;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

/**
 * The SPEC 12.2 catalogue, read from {@code defense.yml}.
 *
 * <p>Values are read by full path from the root configuration rather than through nested
 * sections. That is not a style choice: Bukkit resolves a nested section's <em>keys</em> from
 * the packaged defaults but its <em>values</em> against the on-disk file, so a unit added by a
 * plugin update would load with no mob and no health on a server whose {@code defense.yml}
 * predates it. M9 shipped that bug once; it is not shipping again.
 */
public final class DefenseCatalogue {

    private final ConfigManager configs;
    private final Logger logger;

    private final Map<String, DefenseUnitType> types = new LinkedHashMap<>();

    public DefenseCatalogue(ConfigManager configs, Logger logger) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** @return how many units the server offers */
    public int load() {
        types.clear();
        FileConfiguration defense = configs.get(ConfigFile.DEFENSE);
        ConfigurationSection section = defense.getConfigurationSection("units");
        if (section == null) {
            logger.warning("No units section in defense.yml; no defense units will be sold.");
            return 0;
        }

        for (String key : section.getKeys(false)) {
            try {
                types.put(key, parse(defense, "units." + key, key));
            } catch (IllegalArgumentException e) {
                // One bad entry costs that unit, not the catalogue.
                logger.warning(() -> "Defense unit '" + key + "' " + e.getMessage()
                        + "; it will not be sold.");
            }
        }
        return types.size();
    }

    private DefenseUnitType parse(FileConfiguration defense, String path, String key) {
        String mobName = defense.getString(path + ".mob", "");
        EntityType mob = parseEntity(mobName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "names an unknown mob '" + mobName + "'"));

        Map<DefenseUnitType.EquipmentSlotKey, Material> equipment =
                new EnumMap<>(DefenseUnitType.EquipmentSlotKey.class);
        for (DefenseUnitType.EquipmentSlotKey slot : DefenseUnitType.EquipmentSlotKey.values()) {
            String name = defense.getString(path + ".equipment." + slot.configKey());
            if (name == null || name.isBlank()) {
                continue;
            }
            Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (material == null) {
                logger.warning(() -> "Defense unit '" + key + "' names an unknown item '"
                        + name + "' for " + slot.configKey() + "; that slot is left empty.");
                continue;
            }
            equipment.put(slot, material);
        }

        Map<String, Integer> enchantments = new LinkedHashMap<>();
        ConfigurationSection enchantSection =
                defense.getConfigurationSection(path + ".equipment.main-hand-enchantments");
        if (enchantSection != null) {
            for (String enchant : enchantSection.getKeys(false)) {
                enchantments.put(enchant,
                        defense.getInt(path + ".equipment.main-hand-enchantments." + enchant));
            }
        }

        return new DefenseUnitType(key,
                defense.getString(path + ".display-name", key),
                mob,
                defense.getDouble(path + ".health"),
                defense.getDouble(path + ".damage"),
                defense.getDouble(path + ".speed"),
                new BigDecimal(defense.getString(path + ".cost", "0")),
                new BigDecimal(defense.getString(path + ".upkeep-per-day", "0")),
                equipment,
                enchantments,
                parseDebuff(defense, path));
    }

    private Optional<DefenseUnitType.Debuff> parseDebuff(FileConfiguration defense, String path) {
        String name = defense.getString(path + ".effect.type");
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        PotionEffectType type = io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        if (type == null) {
            logger.warning(() -> "Unknown potion effect '" + name + "' in defense.yml.");
            return Optional.empty();
        }
        return Optional.of(new DefenseUnitType.Debuff(type,
                defense.getInt(path + ".effect.amplifier", 0),
                defense.getInt(path + ".effect.duration-ticks", 100)));
    }

    private static Optional<EntityType> parseEntity(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    public Optional<DefenseUnitType> byKey(String key) {
        return key == null
                ? Optional.empty()
                : Optional.ofNullable(types.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    /** Every unit, cheapest first, which is the order a city buys them in. */
    public List<DefenseUnitType> all() {
        List<DefenseUnitType> sorted = new ArrayList<>(types.values());
        sorted.sort((left, right) -> left.cost().compareTo(right.cost()));
        return sorted;
    }

    public boolean isEmpty() {
        return types.isEmpty();
    }

    public int size() {
        return types.size();
    }

    // ==================================================================================
    // Server-wide settings
    // ==================================================================================

    public boolean enabled() {
        return configs.get(ConfigFile.DEFENSE).getBoolean("enabled", true);
    }

    /** SPEC 12.4: five, plus more per Fortification level. */
    public int baseMaxUnits() {
        return configs.get(ConfigFile.DEFENSE).getInt("placement.base-max-active-units", 5);
    }

    /**
     * SPEC 12.4: how many more units each Fortification level allows.
     *
     * <p>SPEC 5.7's upgrade table says one and SPEC 12.4 says two. Two is the one that is
     * arithmetically consistent: SPEC 12.4 states the range as "5 to 15", which is 5 + 2x5.
     * This is the only place the number is configured, so the two files can no longer
     * disagree with each other the way the specification does.
     */
    public int unitsPerFortificationLevel() {
        return configs.get(ConfigFile.DEFENSE)
                .getInt("placement.units-per-fortification-level", 2);
    }

    /** SPEC 12.4: no more than three in one chunk, so nobody stacks a death-blob. */
    public int maxUnitsPerChunk() {
        return configs.get(ConfigFile.DEFENSE).getInt("placement.max-units-per-chunk", 3);
    }

    /** SPEC 12.4: units bought during an active war cost double. */
    public double wartimeMultiplier() {
        return configs.get(ConfigFile.DEFENSE)
                .getDouble("placement.wartime-purchase-multiplier", 2.0);
    }

    /** SPEC 5.7: Fortification adds this much health per level. */
    public double healthBonusPercentPerLevel() {
        return configs.get(ConfigFile.DEFENSE)
                .getDouble("health-bonus-percent-per-fortification-level", 5.0);
    }

    public int warTargetRange() {
        return configs.get(ConfigFile.DEFENSE).getInt("behaviour.war-target-range", 24);
    }

    public int leashDistance() {
        return configs.get(ConfigFile.DEFENSE).getInt("behaviour.leash-distance-blocks", 8);
    }

    public int nameVisibleRange() {
        return configs.get(ConfigFile.DEFENSE).getInt("behaviour.name-visible-range", 16);
    }

}
