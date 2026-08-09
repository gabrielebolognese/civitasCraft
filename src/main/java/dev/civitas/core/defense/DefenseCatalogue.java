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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

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

    /**
     * SPEC 28's City Warden, which is a unit but is not on the roster.
     *
     * <p>SPEC 30.3 puts the {@code warden:} block at the top level of {@code defense.yml}, a
     * sibling of {@code units:} rather than an entry inside it, and that placement is the design:
     * the Warden is not something a city browses and buys, it is a prestige unlock behind three
     * gates. Held apart from {@link #types} so {@link #all()} — the shop — never lists it, and
     * findable through {@link #byKey} so materialisation, the leash and the upkeep sweep treat it
     * as the ordinary {@code defense_units} row it is.
     */
    private DefenseUnitType warden;

    public DefenseCatalogue(ConfigManager configs, Logger logger) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** @return how many units the server offers */
    public int load() {
        types.clear();
        FileConfiguration defense = configs.get(ConfigFile.DEFENSE);
        loadWarden(defense);
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

    /**
     * Parses SPEC 30.3's {@code warden:} block into a type, or leaves the server without one.
     *
     * <p>A missing or malformed block costs the Warden and nothing else, the same trade a bad
     * roster entry makes. It costs it in the safe direction too: a city with no Warden type
     * cannot buy one, where a half-parsed one could stand up at whatever health a missing key
     * defaulted to.
     */
    private void loadWarden(FileConfiguration defense) {
        warden = null;
        if (!defense.getBoolean("warden.enabled", true)) {
            return;
        }
        try {
            warden = parse(defense, "warden", CityWarden.TYPE_KEY);
        } catch (IllegalArgumentException e) {
            logger.warning(() -> "The City Warden " + e.getMessage()
                    + "; no city will be able to buy one.");
        }
    }

    private DefenseUnitType parse(FileConfiguration defense, String path, String key) {
        if (!defense.isSet(path + ".points")) {
            // SPEC 25.5 gives no rule for a missing price, and defaulting to zero would make
            // that unit free and unbounded -- which is the budget beaten by a typo. Refusing to
            // sell it is the same trade the mob check above makes: one bad entry costs that
            // unit, not the catalogue.
            throw new IllegalArgumentException("has no points value, so it cannot be budgeted");
        }
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

        // Only the abilities this unit actually has. An absent key stays absent rather than
        // defaulting to zero, so a caller can tell "the Colossus does not slam" from "it slams
        // for nothing", and every ability reader asks with the fallback it wants.
        Map<DefenseUnitType.Ability, Double> abilities =
                new EnumMap<>(DefenseUnitType.Ability.class);
        for (DefenseUnitType.Ability ability : DefenseUnitType.Ability.values()) {
            String abilityPath = path + "." + ability.configKey();
            if (defense.isSet(abilityPath)) {
                abilities.put(ability, defense.getDouble(abilityPath));
            }
        }

        return new DefenseUnitType(key,
                defense.getString(path + ".display-name", key),
                mob,
                defense.getDouble(path + ".health"),
                defense.getDouble(path + ".damage"),
                defense.getDouble(path + ".speed"),
                defense.getDouble(path + ".range", warTargetRange()),
                defense.getDouble(path + ".scale", 1.0),
                defense.getDouble(path + ".armor", 0.0),
                defense.getDouble(path + ".toughness", 0.0),
                defense.getDouble(path + ".knockback-resistance", 0.0),
                defense.getBoolean(path + ".invulnerable-outside-war", false),
                DefenseUnitType.priorityOf(defense.getString(path + ".target-priority"))
                        .orElse(DefenseUnitType.TargetPriority.NEAREST),
                new BigDecimal(defense.getString(path + ".cost", "0")),
                new BigDecimal(defense.getString(path + ".upkeep-per-day", "0")),
                defense.getInt(path + ".points"),
                parseIcon(defense, path, key),
                equipment,
                enchantments,
                abilities);
    }

    /**
     * The shop's icon, and the egg a purchase hands over.
     *
     * <p>They must be the same item. Before M12d the menu fell back to an iron golem egg and
     * the purchase to a zombie egg, so a unit whose mob has no spawn egg — the Watchtower
     * Keeper is an armour stand — was drawn as one thing and delivered as another.
     */
    private Optional<Material> parseIcon(FileConfiguration defense, String path, String key) {
        String name = defense.getString(path + ".icon");
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        if (material == null) {
            logger.warning(() -> "Defense unit '" + key + "' names an unknown icon '" + name
                    + "'; its mob's spawn egg will be used instead.");
        }
        return Optional.ofNullable(material);
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
        if (key == null) {
            return Optional.empty();
        }
        String normalised = key.trim().toLowerCase(Locale.ROOT);
        if (CityWarden.TYPE_KEY.equals(normalised)) {
            // Findable, but never listed. A placed Warden is a defense_units row and every path
            // that reads one -- materialisation, the leash, the upkeep sum, the death handler --
            // resolves its type through here.
            return Optional.ofNullable(warden);
        }
        return Optional.ofNullable(types.get(normalised));
    }

    /** SPEC 28's Warden as a unit type, or empty when this server does not offer one. */
    public Optional<DefenseUnitType> warden() {
        return Optional.ofNullable(warden);
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

    /**
     * SPEC 25.5's base Defense Capacity, in points.
     *
     * <p>This and {@link #capacityPerFortificationLevel()} replace Part I 12.4's unit count,
     * which SPEC 25.5 retires in one sentence: "A count permits fifteen Colossi. A points budget
     * does not." The keys follow SPEC 30.3's {@code defense.yml} rather than SPEC 25.5's prose
     * names ({@code defense.base-capacity}), because the file is the one that shows the nesting.
     */
    public int baseCapacity() {
        return configs.get(ConfigFile.DEFENSE).getInt("capacity.base", 100);
    }

    /** SPEC 25.5: each Fortification level buys this many more points, so 100 to 225. */
    public int capacityPerFortificationLevel() {
        return configs.get(ConfigFile.DEFENSE).getInt("capacity.per-fortification-level", 25);
    }

    /**
     * What a unit costs the budget, or nothing at all if this server no longer offers it.
     *
     * <p>Read from the catalogue and never from the stored row, deliberately: retuning a price in
     * {@code defense.yml} must re-price every existing garrison, or SPEC 30.2 case 101 could
     * never fire on a server where nothing can downgrade an upgrade.
     *
     * <p>A type the catalogue has never heard of costs zero. Every {@code defense_units} row
     * written before SPEC 25.1 retired the eight-unit roster carries such a key, and those rows
     * cannot materialise either — {@link UnitMaterializer#materialize} refuses on an empty type —
     * so they are ghosts rather than free units. That is a decision rather than an oversight,
     * which is why it is warned about once per unknown type instead of silently.
     */
    public int pointsOf(String typeKey) {
        Optional<DefenseUnitType> type = byKey(typeKey);
        if (type.isPresent()) {
            return type.get().points();
        }
        if (unknownTypes.add(String.valueOf(typeKey))) {
            logger.warning(() -> "Defense unit type '" + typeKey + "' is not in the catalogue; "
                    + "rows of that type cost no Defense Capacity and cannot be spawned.");
        }
        return 0;
    }

    /** Types already warned about, so an upkeep sweep does not repeat itself every day. */
    private final java.util.Set<String> unknownTypes = ConcurrentHashMap.newKeySet();

    /** SPEC 27.8: no more than three in one chunk, so nobody stacks a death-blob. */
    public int maxUnitsPerChunk() {
        return configs.get(ConfigFile.DEFENSE).getInt("placement.max-units-per-chunk", 3);
    }

    /** SPEC 27.8: units bought during an active war cost double. */
    public double wartimeMultiplier() {
        return configs.get(ConfigFile.DEFENSE)
                .getDouble("placement.wartime-purchase-multiplier", 2.0);
    }

    /**
     * SPEC 27.8: a unit placed during an ACTIVE war "enter[s] a 60-second inactive period before
     * functioning", so a garrison bought mid-fight cannot turn the fight it was bought for.
     */
    public long warPurchaseInactiveMillis() {
        return configs.get(ConfigFile.DEFENSE)
                .getLong("placement.war-purchase-inactive-seconds", 60) * 1000L;
    }

    /**
     * SPEC 30.2 case 92: "If teleport fails three times, dematerialized and re-materialized at
     * post." SPEC states the number in the case and gives it no home in SPEC 30.3.
     */
    public int leashTeleportFailures() {
        return configs.get(ConfigFile.DEFENSE).getInt("placement.leash-teleport-failures", 3);
    }

    /** SPEC 5.7: Fortification adds this much health per level. */
    public double healthBonusPercentPerLevel() {
        return configs.get(ConfigFile.DEFENSE)
                .getDouble("health-bonus-percent-per-fortification-level", 5.0);
    }

    /**
     * The same, for one unit, and the reason the overload exists.
     *
     * <p>SPEC 28.2 gates the Warden behind Fortification <b>5</b>, so every Warden that can legally
     * exist would take the full five levels of SPEC 5.7's health bonus and stand at 625 HP. That
     * would make SPEC 28.3's own words false — "Health 500. Vanilla 500. Unchanged. This is the
     * point of the unit" — and every figure in SPEC 28.5's time-to-kill table wrong by a quarter,
     * which is the table the whole unit is balanced against.
     *
     * <p>SPEC does not address the interaction, so it is a config key rather than a constant, and
     * it defaults to the reading that keeps SPEC 28.3 and SPEC 28.5 consistent with each other.
     */
    public double healthBonusPercentPerLevel(DefenseUnitType type) {
        boolean isWarden = type != null && CityWarden.TYPE_KEY.equals(type.key());
        if (isWarden && !configs.get(ConfigFile.DEFENSE)
                .getBoolean("warden.apply-fortification-health-bonus", false)) {
            return 0;
        }
        return healthBonusPercentPerLevel();
    }

    // ==================================================================================
    // SPEC 28, the City Warden
    // ==================================================================================

    public boolean wardenEnabled() {
        return configs.get(ConfigFile.DEFENSE).getBoolean("warden.enabled", true);
    }

    /** SPEC 28.2: 750,000 C, close to 2.75 million once the Fortification track is counted. */
    public BigDecimal wardenCost() {
        return new BigDecimal(
                configs.get(ConfigFile.DEFENSE).getString("warden.cost", "750000"));
    }

    /** SPEC 28.2's Fortification gate, checked at purchase and deliberately nowhere else. */
    public int wardenRequiredFortification() {
        return configs.get(ConfigFile.DEFENSE)
                .getInt("warden.required-fortification-level", 5);
    }

    /** SPEC 28.6's six hours underground after a peacetime defeat. */
    public long wardenRecoveryHours() {
        return configs.get(ConfigFile.DEFENSE).getLong("warden.recovery-hours", 6);
    }

    /** SPEC 28.3: ten blocks, and only against the one player it is alerted at. */
    public double wardenDarknessRadius() {
        return configs.get(ConfigFile.DEFENSE).getDouble("warden.darkness-radius", 10);
    }

    /**
     * How long each application of Darkness lasts.
     *
     * <p>SPEC 28.3 gives the radius and SPEC 28.8 says the plugin applies the effect; neither
     * gives a duration, an amplifier or a refresh rate. All three are this implementation's, and
     * the duration is deliberately a little longer than the refresh so the effect does not
     * flicker between ticks of the sweep that reapplies it.
     */
    public int wardenDarknessDurationTicks() {
        return configs.get(ConfigFile.DEFENSE).getInt("warden.darkness-duration-ticks", 100);
    }

    public int wardenDarknessAmplifier() {
        return configs.get(ConfigFile.DEFENSE).getInt("warden.darkness-amplifier", 0);
    }

    /** SPEC 28.3: "core chunk plus 6 blocks", which is not SPEC 27.8's eight. */
    public double wardenLeashBlocks() {
        return configs.get(ConfigFile.DEFENSE).getDouble("warden.leash-blocks", 6);
    }

    /**
     * SPEC 28.8's tenth-of-a-minute checkpoint.
     *
     * <p>"Health is checkpointed to the database every 10 seconds while in combat", against 30 for
     * every other unit. A Warden's health is the difference between a city keeping a 750,000 C
     * asset through a crash and losing the damage record of the fight that was killing it.
     */
    public long wardenCheckpointSeconds() {
        return configs.get(ConfigFile.DEFENSE)
                .getLong("materialization.warden-health-checkpoint-seconds", 10);
    }

    /**
     * The anger the plugin sets on the one target SPEC 30.1 has permitted.
     *
     * <p>SPEC 28.8 says to drive targeting "exclusively from the plugin", and for a Warden that
     * cannot mean {@code Mob#setTarget}: a Warden is a brain mob and its attack target is its
     * anger map, which is why {@link WardenSuppression} writes anger rather than a target.
     * Vanilla treats 80 as {@code AngerLevel.ANGRY}, which is the level at which it attacks.
     */
    public int wardenAngerOnTarget() {
        return configs.get(ConfigFile.DEFENSE).getInt("warden.anger-on-target", 80);
    }

    /**
     * SPEC 30.3's three declarations, read so an operator who changes one is told it did nothing.
     *
     * <p>{@code sonic-boom} carries SPEC's own comment: "NEVER set true. See 28.3." The other two
     * describe SPEC 28.6's behaviour rather than switching it — an immortal 750,000 C asset in war,
     * or one a single griefer can delete in peacetime, are both outcomes SPEC 28.6 spends a
     * paragraph refusing. So they are honoured at their documented values and refused at any
     * other, which is the shape {@code RollbackPolicy} already uses for the same problem.
     *
     * @return the settings an operator has changed away from the only supported value
     */
    public List<String> unsupportedWardenSettings() {
        FileConfiguration defense = configs.get(ConfigFile.DEFENSE);
        List<String> bad = new ArrayList<>();
        if (defense.getBoolean("warden.sonic-boom", false)) {
            bad.add("warden.sonic-boom");
        }
        if (defense.getBoolean("warden.killable-in-peacetime", false)) {
            bad.add("warden.killable-in-peacetime");
        }
        if (!defense.getBoolean("warden.killable-in-war", true)) {
            bad.add("warden.killable-in-war");
        }
        return bad;
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

    /** SPEC 30.2 case 112: what a unit saved from terrain comes back at. */
    public double deathSaveHealthPercent() {
        return configs.get(ConfigFile.DEFENSE)
                .getDouble("survival.death-save-health-percent", 20);
    }

    /** SPEC 30.2 case 112: "once per hour", so a player who keeps at it eventually wins. */
    public long deathSaveCooldownMillis() {
        return configs.get(ConfigFile.DEFENSE)
                .getLong("survival.death-save-cooldown-minutes", 60) * 60_000L;
    }
}
