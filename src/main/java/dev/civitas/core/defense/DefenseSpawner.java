package dev.civitas.core.defense;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntPredicate;

import dev.civitas.core.city.City;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Snowman;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
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

    /**
     * Whether a city is fighting an active war, for SPEC 27.3's Keeper.
     *
     * <p>A seam in the shape the rest of the package already uses, answering "no war" until M19
     * wires it. The conservative direction: a Keeper is invulnerable in peacetime, so a wiring
     * mistake leaves it indestructible rather than leaving it destructible during peace, and
     * the second of those is the one a city paid 9,000 C to avoid.
     */
    private IntPredicate atWar = cityId -> false;

    public void useWars(IntPredicate cityAtWar) {
        this.atWar = Objects.requireNonNull(cityAtWar, "cityAtWar");
    }

    private Optional<WardenSuppression> suppression = Optional.empty();

    /**
     * SPEC 28.8's shaping, applied last so it can overwrite anything the general path set.
     *
     * <p>Optional because {@link WardenSuppression} touches {@code org.bukkit.entity.Warden}, which
     * MockBukkit does not implement, and because a server with {@code warden.enabled: false} has
     * nothing for it to do. Absent, no Warden can be spawned at all — the catalogue has no type
     * for one — so there is nothing left unshaped.
     */
    public void useWardenSuppression(WardenSuppression rules) {
        this.suppression = Optional.of(Objects.requireNonNull(rules, "rules"));
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

        // Per type rather than server-wide: SPEC 28.2 gates the Warden behind Fortification 5, so
        // every Warden that can legally exist would otherwise take the full bonus and stand at
        // 625 HP -- against SPEC 28.3's "Health 500. Unchanged. This is the point of the unit".
        UnitShaping shaping = UnitShaping.of(type, city.id(), fortificationLevel,
                catalogue.healthBonusPercentPerLevel(type), atWar.test(city.id()));

        applyAttributes(living, shaping);
        applyFlags(living, shaping);
        applyEquipment(living, shaping);
        applyIdentity(living, unit, type, city, shaping);
        suppression.ifPresent(rules -> rules.shape(living, type));
        return Optional.of(living);
    }

    /** SPEC 27.1's stat table, plus the SPEC 5.7 Fortification health bonus. */
    private void applyAttributes(LivingEntity living, UnitShaping shaping) {
        set(living, Attribute.MAX_HEALTH, shaping.maxHealth());
        living.setHealth(Math.min(shaping.maxHealth(),
                living.getAttribute(Attribute.MAX_HEALTH) == null
                        ? shaping.maxHealth()
                        : living.getAttribute(Attribute.MAX_HEALTH).getValue()));

        if (shaping.attackDamage() > 0) {
            set(living, Attribute.ATTACK_DAMAGE, shaping.attackDamage());
        }
        // Always written, including the zero: SPEC 27.2's Frost Sentry is "static, does not move
        // from its post", and a snow golem left at its vanilla speed wanders off it.
        set(living, Attribute.MOVEMENT_SPEED, Math.max(0, shaping.movementSpeed()));

        // SPEC 27.5 calls the Archer's twenty blocks "hard capped", and a vanilla skeleton
        // follows to sixteen. Without this the cap sits above the unit's natural reach, is
        // never met, and a test asserting it proves nothing.
        set(living, Attribute.FOLLOW_RANGE, shaping.followRange());

        if (shaping.armour() > 0) {
            set(living, Attribute.ARMOR, shaping.armour());
        }
        if (shaping.armourToughness() > 0) {
            set(living, Attribute.ARMOR_TOUGHNESS, shaping.armourToughness());
        }
        if (shaping.knockbackResistance() > 0) {
            set(living, Attribute.KNOCKBACK_RESISTANCE, shaping.knockbackResistance());
        }
        if (shaping.scale() != 1.0) {
            // SPEC 25.3: "A 1.8x Iron Golem is one attribute set, no model needed." The best
            // value in the entire toolbox, and the whole of the Colossus's presence.
            set(living, Attribute.SCALE, shaping.scale());
        }
    }

    /**
     * The flags SPEC 30.2 cases 106 to 109 require, applied on every materialisation.
     *
     * <p>Case 108 is explicit that this is per materialisation rather than per purchase, and
     * under SPEC 25.4 a unit respawns whenever a player walks past — so a flag set once at
     * placement would have held until the first time everybody left.
     */
    private void applyFlags(LivingEntity living, UnitShaping shaping) {
        if (living instanceof Zombie zombie) {
            // Case 108, and case 109: reinforcement spawning produces free untracked mobs that
            // no city paid for and no row knows about.
            zombie.setShouldBurnInDay(false);
            // SPEC 27.6: "Disable baby zombie variants." A baby City Guard is faster than the
            // 0.28 the table gives it and is the wrong unit to fight.
            zombie.setAdult();
        }
        if (living instanceof AbstractSkeleton skeleton) {
            skeleton.setShouldBurnInDay(false);
        }
        if (shaping.suppressReinforcements()) {
            set(living, Attribute.SPAWN_REINFORCEMENTS, 0.0);
        }
        if (living instanceof Snowman snowman) {
            // SPEC 27.2: a derped snow golem is a pumpkin-less one, which is a different mob to
            // look at and not the one the shop drew.
            snowman.setDerp(false);
        }
        if (living instanceof ArmorStand stand) {
            // SPEC 27.3: "with arms... setGravity(false), setBasePlate(false)".
            stand.setArms(true);
            stand.setBasePlate(false);
            stand.setGravity(false);
        }
        // SPEC 27.3's Keeper only. Everything else is destructible in peacetime, because
        // SPEC 25.2 Rule 3 needs it to be.
        living.setInvulnerable(shaping.invulnerable());
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
    private void applyEquipment(LivingEntity living, UnitShaping shaping) {
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return;
        }

        for (Map.Entry<DefenseUnitType.EquipmentSlotKey, Material> entry
                : shaping.equipment().entrySet()) {
            ItemStack stack = new ItemStack(entry.getValue());
            if (entry.getKey() == DefenseUnitType.EquipmentSlotKey.MAIN_HAND) {
                applyEnchantments(stack, shaping);
            }
            dye(stack, shaping);
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

    /**
     * SPEC 27's "dyed leather in the city's colour", and the reason it protects nothing.
     *
     * <p>SPEC 25.3 files dyed leather under <b>appearance</b>. SPEC 27.6 states the City Guard's
     * armour as a number in the same table that dresses it in leather, and those are only both
     * true if the leather is cosmetic — worn armour contributes through attribute modifiers, so
     * leaving it alone would turn SPEC's 8 into 15 and put a 90 HP unit most of the way to the
     * unbeatable garrison SPEC 25.2 Rule 1 forbids. An explicit modifier of zero replaces the
     * item's own, which is how a leather chestplate becomes a tabard.
     */
    private void dye(ItemStack stack, UnitShaping shaping) {
        if (shaping.leatherColour().isEmpty() || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof LeatherArmorMeta leather) {
            leather.setColor(shaping.leatherColour().get());
        }
        if (shaping.stripArmourFromEquipment()) {
            meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(
                    new NamespacedKey(plugin, "civitas_cosmetic_armour"), 0,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        }
        stack.setItemMeta(meta);
    }

    private void applyEnchantments(ItemStack stack, UnitShaping shaping) {
        for (Map.Entry<String, Integer> entry : shaping.mainHandEnchantments().entrySet()) {
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
                               City city, UnitShaping shaping) {
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
            // ally rather than like wildlife, with no owner to follow home. It also means the
            // wolf initiates nothing on its own, which is why UnitAcquisition exists.
            tameable.setTamed(true);
            tameable.setOwner(null);
        }
        if (living instanceof Wolf wolf) {
            shaping.collarColour().ifPresent(wolf::setCollarColor);
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
