package dev.civitas.core.defense;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

/**
 * One line of the SPEC 12.2 unit catalogue.
 *
 * <p>Every unit is a vanilla mob with attribute modifiers and equipment, per SPEC 12.5's "no
 * NMS" rule. That constraint is what keeps this a data record: a type is a mob to spawn and a
 * set of numbers to apply to it, so adding a ninth unit is a config edit rather than a class.
 *
 * @param key        how it is written in {@code defense.yml} and stored in {@code defense_units}
 * @param mob        the vanilla entity underneath
 * @param health     hearts times two, before the Fortification bonus
 * @param damage     melee damage, or bow damage for a ranged unit
 * @param speed      movement speed attribute; zero for a unit that does not move
 * @param equipment  armour and hands, by slot
 * @param effect     what it applies instead of damage, for the Sentry
 */
public record DefenseUnitType(
        String key,
        String displayName,
        EntityType mob,
        double health,
        double damage,
        double speed,
        BigDecimal cost,
        BigDecimal upkeepPerDay,
        Map<EquipmentSlotKey, Material> equipment,
        Map<String, Integer> mainHandEnchantments,
        Optional<Debuff> effect) {

    public DefenseUnitType {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(upkeepPerDay, "upkeepPerDay");
        equipment = Map.copyOf(equipment);
        mainHandEnchantments = Map.copyOf(mainHandEnchantments);

        if (health <= 0) {
            throw new IllegalArgumentException("health must be positive for " + key);
        }
        if (cost.signum() < 0 || upkeepPerDay.signum() < 0) {
            throw new IllegalArgumentException("prices cannot be negative for " + key);
        }
    }

    /** Whether this unit fights with a bow rather than in melee. */
    public boolean isRanged() {
        return equipment.get(EquipmentSlotKey.MAIN_HAND) == Material.BOW;
    }

    /** Whether it deals no damage at all, which is the Sentry. */
    public boolean isSupport() {
        return damage <= 0 && effect.isPresent();
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
     * What a support unit applies instead of hitting things.
     *
     * @param type     the effect, resolved from its registry name
     * @param duration in ticks
     */
    public record Debuff(PotionEffectType type, int amplifier, int duration) { }
}
