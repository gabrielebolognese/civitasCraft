package dev.civitas.core.defense;

import java.math.BigDecimal;
import java.util.Objects;

import dev.civitas.storage.row.DefenseUnitRow;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * One placed defense unit, SPEC 12.
 *
 * <p>A unit is a database row first and an entity second. The entity can be killed, unloaded,
 * lost to chunk corruption or removed by {@code /kill}, and SPEC 12.5 requires that it come
 * back from the row when that happens. So the row is what a unit <em>is</em>, and the entity
 * is a thing the plugin keeps in sync with it.
 *
 * @param active       false while the city cannot pay its upkeep; the row survives, the entity
 *                     does not
 * @param health       SPEC 25.4's persisted health, null when it has never materialised
 * @param dormantSince when it last stopped being an entity, null while it is one
 */
public record DefenseUnit(
        int id,
        int cityId,
        String type,
        String world,
        double x,
        double y,
        double z,
        BigDecimal upkeep,
        boolean active,
        Double health,
        Long dormantSince) {

    public DefenseUnit {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(upkeep, "upkeep");
    }

    public static DefenseUnit from(DefenseUnitRow row) {
        return new DefenseUnit(row.id(), row.cityId(), row.type(), row.world(),
                row.spawnX(), row.spawnY(), row.spawnZ(), row.upkeep(), row.active(),
                row.health(), row.dormantSince());
    }

    public DefenseUnitRow toRow() {
        return new DefenseUnitRow(id, cityId, type, world, x, y, z, upkeep, active,
                health, dormantSince);
    }

    public DefenseUnit withActive(boolean value) {
        return new DefenseUnit(id, cityId, type, world, x, y, z, upkeep, value,
                health, dormantSince);
    }

    /** Where it was placed. Empty if that world is not loaded. */
    public java.util.Optional<Location> location() {
        World loaded = org.bukkit.Bukkit.getWorld(world);
        return loaded == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(new Location(loaded, x, y, z));
    }

    public int chunkX() {
        return (int) Math.floor(x) >> 4;
    }

    public int chunkZ() {
        return (int) Math.floor(z) >> 4;
    }

    /** The same unit with its SPEC 25.4 state moved on, for a checkpoint or a dematerialise. */
    public DefenseUnit withState(Double newHealth, Long newDormantSince) {
        return new DefenseUnit(id, cityId, type, world, x, y, z, upkeep, active,
                newHealth, newDormantSince);
    }

    /** SPEC 25.4: never materialised reads as full health, not as zero. */
    public double healthOr(double maximum) {
        return health == null ? maximum : health;
    }

    public boolean isDormant() {
        return dormantSince != null;
    }
}
