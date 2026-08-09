package dev.civitas.core.defense;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * One line of the SPEC 27 roster, read from {@code defense.yml}.
 *
 * <p>Every unit is a vanilla mob with attribute modifiers and equipment, per SPEC 25.3's
 * toolbox and SPEC 2.1's "no NMS unless unavoidable". That constraint is what keeps this a
 * data record: a type is a mob to spawn and a set of numbers to apply to it, so retuning the
 * roster is a config edit rather than a class.
 *
 * <h2>What this replaces</h2>
 *
 * <p>SPEC 25.1 retired Part I 12.2's eight units, of which five performed two jobs. The shape
 * that went with them was a single optional {@code Debuff}, which the Frost Sentry outgrows the
 * moment it applies two effects rather than one. Per-unit numbers are now a typed
 * {@link Ability} map: one parse loop, one place to add a number, and a key that cannot be
 * misspelled at the call site.
 *
 * @param range                  how far it can see, SPEC 30.1's last line. One key for the
 *                               Warhound's chase, the Archer's hard cap and the Keeper's
 *                               detection radius, because SPEC gives one number three names
 * @param scale                  SPEC 27.7's {@code Attribute.SCALE}; 1.0 leaves it alone
 * @param armour                 the total the unit ends up with, SPEC 27.6
 * @param invulnerableOutsideWar SPEC 27.3's Keeper, which cannot be destroyed in peacetime
 * @param points                 SPEC 25.5's price in Defense Capacity. Zero is legal and means
 *                               excluded from the budget, which is the City Warden's row
 * @param icon                   what the shop draws and hands over when the mob has no egg
 */
public record DefenseUnitType(
        String key,
        String displayName,
        EntityType mob,
        double health,
        double damage,
        double speed,
        double range,
        double scale,
        double armour,
        double armourToughness,
        double knockbackResistance,
        boolean invulnerableOutsideWar,
        TargetPriority targetPriority,
        BigDecimal cost,
        BigDecimal upkeepPerDay,
        int points,
        Optional<Material> icon,
        Map<EquipmentSlotKey, Material> equipment,
        Map<String, Integer> mainHandEnchantments,
        Map<Ability, Double> abilities) {

    public DefenseUnitType {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(upkeepPerDay, "upkeepPerDay");
        Objects.requireNonNull(targetPriority, "targetPriority");
        Objects.requireNonNull(icon, "icon");
        equipment = Map.copyOf(equipment);
        mainHandEnchantments = Map.copyOf(mainHandEnchantments);
        abilities = abilities.isEmpty()
                ? Map.of()
                : Map.copyOf(new EnumMap<>(abilities));

        if (health <= 0) {
            throw new IllegalArgumentException("health must be positive for " + key);
        }
        if (cost.signum() < 0 || upkeepPerDay.signum() < 0) {
            throw new IllegalArgumentException("prices cannot be negative for " + key);
        }
        if (points < 0) {
            // Negative points would give a city capacity back for fielding something, which is
            // the one arithmetic that lets SPEC 25.5's budget be beaten rather than spent.
            throw new IllegalArgumentException("points cannot be negative for " + key);
        }
    }

    /** Whether this unit fights with a bow rather than in melee. */
    public boolean isRanged() {
        return equipment.get(EquipmentSlotKey.MAIN_HAND) == Material.BOW;
    }

    /**
     * Whether it fights at all.
     *
     * <p>Two of SPEC 27's six do not: the Frost Sentry "deals no damage ever" and the
     * Watchtower Keeper "cannot fight and cannot be targeted by mobs". A shop line reading
     * "damage 0" for either of them reads as a broken unit rather than as a stated design.
     */
    public boolean dealsDamage() {
        return damage > 0;
    }

    /**
     * Whether it can walk.
     *
     * <p>Read by SPEC 30.2 case 112's death save, whose premise is a unit that "pathfinds into
     * lava or off a cliff". A unit at zero speed pathfinds nowhere, so terrain cannot have
     * deleted it — and the Frost Sentry's stated counterplay is that it melts in lava.
     */
    public boolean canMove() {
        return speed > 0;
    }

    /** A per-unit number, or the default for a unit that does not have that ability. */
    public double ability(Ability ability, double fallback) {
        Double value = abilities.get(ability);
        return value == null ? fallback : value;
    }

    public boolean hasAbility(Ability ability) {
        return abilities.containsKey(ability);
    }

    /** The language key for its description. */
    public String messageKey() {
        return "defense.unit." + key;
    }

    /** The slots {@code defense.yml} can fill. */
    public enum EquipmentSlotKey {
        HELMET("helmet"),
        CHESTPLATE("chestplate"),
        LEGGINGS("leggings"),
        BOOTS("boots"),
        MAIN_HAND("main-hand"),
        OFF_HAND("off-hand"),
        /** Wolf armour, which is neither armour nor a hand slot. */
        BODY("body");

        private final String configKey;

        EquipmentSlotKey(String configKey) {
            this.configKey = configKey;
        }

        public String configKey() {
            return configKey;
        }
    }

    /**
     * SPEC 27.4: "prioritises the lowest-health valid target rather than the nearest".
     *
     * <p>Worth saying out loud, because SPEC 30.1 forbids "unit-specific targeting logic
     * anywhere else" in the same Part and the next reader will reach for that sentence.
     * Priority is <b>selection</b> among candidates the one handler has already permitted;
     * permission is still entirely {@link TargetingRule}'s. A Warhound cannot reach a player
     * the rule refused, however low their health.
     */
    public enum TargetPriority {
        NEAREST,
        LOWEST_HEALTH
    }

    /**
     * The per-unit numbers behind SPEC 27.2 to 27.7's abilities.
     *
     * <p>Typed rather than a free-form map so a misspelling is a compile error, and named for
     * the config key so the two can be read against each other.
     */
    public enum Ability {
        /** SPEC 27.2, and SPEC 30.3 writes it as a level where Bukkit wants an amplifier. */
        SLOWNESS_LEVEL("slowness-level"),
        SLOWNESS_SECONDS("slowness-seconds"),
        MINING_FATIGUE_LEVEL("mining-fatigue-level"),
        MINING_FATIGUE_SECONDS("mining-fatigue-seconds"),
        /** SPEC 27.3. */
        GLOW_REFRESH_SECONDS("glow-refresh-seconds"),
        CHAT_ALERT_COOLDOWN_MINUTES("chat-alert-cooldown-minutes"),
        /** SPEC 27.4. */
        BITE_SLOWNESS_LEVEL("bite-slowness-level"),
        BITE_SLOWNESS_SECONDS("bite-slowness-seconds"),
        /** SPEC 27.5. */
        MELEE_FIRERATE_PENALTY("melee-firerate-penalty"),
        MELEE_FIRERATE_RADIUS("melee-firerate-radius"),
        /** SPEC 27.6. */
        ALERT_NETWORK_CHUNKS("alert-network-chunks"),
        ALERT_NETWORK_SECONDS("alert-network-seconds"),
        /** SPEC 27.7. */
        SLAM_RADIUS("slam-radius"),
        SLAM_DAMAGE("slam-damage"),
        SLAM_KNOCKBACK("slam-knockback"),
        ARROW_RESIST_THRESHOLD("arrow-resist-threshold"),
        ARROW_RESIST_PERCENT("arrow-resist-percent");

        private final String configKey;

        Ability(String configKey) {
            this.configKey = configKey;
        }

        public String configKey() {
            return configKey;
        }
    }

    static Optional<TargetPriority> priorityOf(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(TargetPriority.valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
