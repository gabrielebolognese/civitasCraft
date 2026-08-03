package dev.civitas.core.defense;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.civitas.core.city.City;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Turning a catalogue entry into a living unit, SPEC 12.2 and 12.5.
 *
 * <p>Vanilla mobs with attribute modifiers and equipment, exactly as SPEC 12.5 requires. The
 * things that make one of these a defense unit rather than a wandering zombie are all
 * ordinary Bukkit: an attribute for health, equipment with no drop chance, a custom name, and
 * a {@link PersistentDataType} stamp carrying the database id.
 *
 * <p>That stamp is the important one. SPEC 12.5 says to keep the unit's id in the entity's
 * container so it survives a chunk unload and a restart, and it is also what tells every
 * listener in the plugin that this mob is somebody's guard rather than a monster.
 */
public final class DefenseSpawner {

    /** The persistent-data key SPEC 12.5 asks for. */
    public static final String UNIT_KEY = "defense_unit";

    private final Plugin plugin;
    private final DefenseCatalogue catalogue;
    private final LangManager lang;
    private final NamespacedKey key;

    public DefenseSpawner(Plugin plugin, DefenseCatalogue catalogue, LangManager lang) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.key = new NamespacedKey(plugin, UNIT_KEY);
    }

    public NamespacedKey key() {
        return key;
    }

    // ==================================================================================
    // Spawning
    // ==================================================================================

    /**
     * Puts a unit in the world.
     *
     * @param fortificationLevel the city's SPEC 5.7 Fortification level, for the health bonus
     * @return the entity, or empty if the world is not loaded
     */
    public Optional<LivingEntity> spawn(DefenseUnit unit, DefenseUnitType type, City city,
                                        int fortificationLevel) {
        Optional<Location> where = unit.location();
        if (where.isEmpty()) {
            return Optional.empty();
        }

        Entity spawned = where.get().getWorld().spawnEntity(where.get(), type.mob());
        if (!(spawned instanceof LivingEntity living)) {
            spawned.remove();
            return Optional.empty();
        }

        applyAttributes(living, type, fortificationLevel);
        applyEquipment(living, type);
        applyIdentity(living, unit, type, city);
        return Optional.of(living);
    }

    /** SPEC 12.2's stat table, plus the SPEC 5.7 Fortification health bonus. */
    private void applyAttributes(LivingEntity living, DefenseUnitType type, int fortification) {
        double bonus = 1 + catalogue.healthBonusPercentPerLevel() / 100.0
                * Math.max(0, fortification);
        double health = type.health() * bonus;

        set(living, Attribute.MAX_HEALTH, health);
        living.setHealth(Math.min(health, living.getAttribute(Attribute.MAX_HEALTH) == null
                ? health
                : living.getAttribute(Attribute.MAX_HEALTH).getValue()));

        if (type.damage() > 0) {
            set(living, Attribute.ATTACK_DAMAGE, type.damage());
        }
        if (type.speed() > 0) {
            set(living, Attribute.MOVEMENT_SPEED, type.speed());
        } else {
            // A unit with no speed does not wander, which is the Sentry.
            set(living, Attribute.MOVEMENT_SPEED, 0.0);
        }
    }

    private static void set(LivingEntity living, Attribute attribute, double value) {
        AttributeInstance instance = living.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    /**
     * Armour and hands, with drop chances at zero.
     *
     * <p>Zero drop chance matters more than it looks: a Siege Golem in full diamond that drops
     * its kit on death would make killing defenses profitable, which is the opposite of SPEC
     * 12.1's "consumed resources".
     */
    private void applyEquipment(LivingEntity living, DefenseUnitType type) {
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return;
        }

        for (Map.Entry<DefenseUnitType.EquipmentSlotKey, Material> entry
                : type.equipment().entrySet()) {
            ItemStack stack = new ItemStack(entry.getValue());
            if (entry.getKey() == DefenseUnitType.EquipmentSlotKey.MAIN_HAND) {
                applyEnchantments(stack, type);
            }
            switch (entry.getKey()) {
                case HELMET -> {
                    equipment.setHelmet(stack);
                    equipment.setHelmetDropChance(0f);
                }
                case CHESTPLATE -> {
                    equipment.setChestplate(stack);
                    equipment.setChestplateDropChance(0f);
                }
                case LEGGINGS -> {
                    equipment.setLeggings(stack);
                    equipment.setLeggingsDropChance(0f);
                }
                case BOOTS -> {
                    equipment.setBoots(stack);
                    equipment.setBootsDropChance(0f);
                }
                case MAIN_HAND -> {
                    equipment.setItemInMainHand(stack);
                    equipment.setItemInMainHandDropChance(0f);
                }
                case OFF_HAND -> {
                    equipment.setItemInOffHand(stack);
                    equipment.setItemInOffHandDropChance(0f);
                }
                case BODY -> applyBodyArmour(living, stack);
            }
        }
    }

    /** Wolf armour, which is neither an armour slot nor a hand. */
    private static void applyBodyArmour(LivingEntity living, ItemStack stack) {
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return;
        }
        try {
            equipment.setItem(org.bukkit.inventory.EquipmentSlot.BODY, stack);
            equipment.setDropChance(org.bukkit.inventory.EquipmentSlot.BODY, 0f);
        } catch (IllegalArgumentException e) {
            // Not every mob has a body slot; a unit without one simply goes unarmoured.
        }
    }

    private void applyEnchantments(ItemStack stack, DefenseUnitType type) {
        for (Map.Entry<String, Integer> entry : type.mainHandEnchantments().entrySet()) {
            // Through Paper's registry access rather than Bukkit's deprecated statics, so
            // this keeps working as registries move off them.
            Enchantment enchantment = io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT)
                    .get(NamespacedKey.minecraft(entry.getKey().toLowerCase(Locale.ROOT)));
            if (enchantment != null) {
                stack.addUnsafeEnchantment(enchantment, entry.getValue());
            }
        }
    }

    /**
     * The name, the stamp, and the flags SPEC 12.5 lists.
     *
     * <p>{@code setRemoveWhenFarAway(false)} and {@code setPersistent(true)} together are what
     * stop a unit despawning the moment its city logs off, which for something a city paid
     * 60,000 C for would be unacceptable.
     */
    private void applyIdentity(LivingEntity living, DefenseUnit unit, DefenseUnitType type,
                               City city) {
        Component name = lang.get("defense.unit-name",
                LangManager.placeholder("city", city.name()),
                LangManager.placeholder("unit", type.displayName()));

        living.customName(name);
        // SPEC 12.5: visible only close up, so a defended city is not a wall of floating text.
        living.setCustomNameVisible(false);
        living.setRemoveWhenFarAway(false);
        living.setPersistent(true);
        living.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, unit.id());

        if (living instanceof Mob mob) {
            mob.setTarget(null);
        }
        if (living instanceof Tameable tameable) {
            // A Warhound belongs to its city, not to a player: tamed so it behaves like an
            // ally rather than like wildlife, with no owner to follow home.
            tameable.setTamed(true);
            tameable.setOwner(null);
        }
    }

    // ==================================================================================
    // Reading a live entity
    // ==================================================================================

    /** @return the unit id stamped on an entity, if it is one of ours */
    public Optional<Integer> unitIdOf(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                entity.getPersistentDataContainer().get(key, PersistentDataType.INTEGER));
    }

    public boolean isDefenseUnit(Entity entity) {
        return unitIdOf(entity).isPresent();
    }
}
