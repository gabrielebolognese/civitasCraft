package dev.civitas.core.siege;

import java.util.Objects;
import java.util.Optional;

import dev.civitas.lang.LangManager;
import dev.civitas.storage.row.SiegeUnitRow;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Raider;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Turning a roster entry into a living siege unit, SPEC 29.3.
 *
 * <p>Deliberately a separate class from {@code DefenseSpawner} rather than a branch inside it. A
 * siege unit is a different animal: it has no upkeep, no city colour, no materialisation, no leash
 * to a claim, and a life measured in days rather than months. Sharing the spawner would mean an
 * {@code if} in every one of those paths.
 *
 * <p>What it does share is the tagging discipline. The database id goes in the entity's persistent
 * data, which is what tells {@code SiegeTargeting} that this pillager is somebody's siege rather
 * than a raid, and what lets the war-end sweep find it.
 */
public final class SiegeSpawner {

    /** The persistent-data key carrying the {@code siege_units} row id. */
    public static final String UNIT_KEY = "siege_unit";

    private final Plugin plugin;
    private final LangManager lang;
    private final NamespacedKey key;

    public SiegeSpawner(Plugin plugin, LangManager lang) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.key = new NamespacedKey(plugin, UNIT_KEY);
    }

    public NamespacedKey key() {
        return key;
    }

    /** @return the spawned mob, or empty when the world is not loaded */
    public Optional<LivingEntity> spawn(SiegeUnitRow row, SiegeUnitType type) {
        World world = plugin.getServer().getWorld(row.world());
        if (world == null) {
            return Optional.empty();
        }
        Location where = new Location(world, row.x(), row.y(), row.z());
        Entity spawned = world.spawnEntity(where, type.mob());
        if (!(spawned instanceof LivingEntity living)) {
            spawned.remove();
            return Optional.empty();
        }

        shape(living, row, type);
        return Optional.of(living);
    }

    private void shape(LivingEntity living, SiegeUnitRow row, SiegeUnitType type) {
        set(living, Attribute.MAX_HEALTH, type.health());
        living.setHealth(type.health());
        set(living, Attribute.ATTACK_DAMAGE, type.damage());
        set(living, Attribute.FOLLOW_RANGE, type.range());

        living.customName(Component.text(type.displayName()));
        living.setCustomNameVisible(false);
        living.setRemoveWhenFarAway(false);
        living.setPersistent(true);

        // SPEC 30.2 case 105 read across: equipment on a paid unit must never become loot.
        if (living.getEquipment() != null) {
            living.getEquipment().setItemInMainHandDropChance(0f);
            living.getEquipment().setHelmetDropChance(0f);
            living.getEquipment().setChestplateDropChance(0f);
            living.getEquipment().setLeggingsDropChance(0f);
            living.getEquipment().setBootsDropChance(0f);
        }
        if (living instanceof AbstractSkeleton skeleton) {
            skeleton.setShouldBurnInDay(false);
        }
        if (living instanceof Raider raider) {
            // A siege unit is not a raid. Left as a patrol leader it would drag Bad Omen and a
            // vanilla raid into a war zone, which is grief nobody declared.
            raider.setPatrolLeader(false);
            raider.setCanJoinRaid(false);
        }
        if (living instanceof Mob mob) {
            mob.setAware(true);
        }

        living.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, row.id());
        living.getPersistentDataContainer().set(warKey(), PersistentDataType.INTEGER, row.warId());
        living.getPersistentDataContainer().set(cityKey(), PersistentDataType.INTEGER,
                row.cityId());
        living.getPersistentDataContainer().set(typeKey(), PersistentDataType.STRING, row.type());
    }

    private static void set(LivingEntity living, Attribute attribute, double value) {
        AttributeInstance instance = living.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    public NamespacedKey warKey() {
        return new NamespacedKey(plugin, "siege_war");
    }

    public NamespacedKey cityKey() {
        return new NamespacedKey(plugin, "siege_city");
    }

    public NamespacedKey typeKey() {
        return new NamespacedKey(plugin, "siege_type");
    }

    public Optional<Integer> unitIdOf(Entity entity) {
        return read(entity, key, PersistentDataType.INTEGER);
    }

    public Optional<Integer> warIdOf(Entity entity) {
        return read(entity, warKey(), PersistentDataType.INTEGER);
    }

    public Optional<Integer> cityIdOf(Entity entity) {
        return read(entity, cityKey(), PersistentDataType.INTEGER);
    }

    public Optional<String> typeKeyOf(Entity entity) {
        return read(entity, typeKey(), PersistentDataType.STRING);
    }

    public boolean isSiegeUnit(Entity entity) {
        return unitIdOf(entity).isPresent();
    }

    private static <T> Optional<T> read(Entity entity, NamespacedKey key,
                                        PersistentDataType<?, T> type) {
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entity.getPersistentDataContainer().get(key, type));
    }

    /** Unused for now, but the lang manager is the route every name will take. */
    LangManager lang() {
        return lang;
    }
}
