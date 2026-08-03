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
 * @param active false while the city cannot pay its upkeep; the row survives, the entity does not
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
        boolean active) {

    public DefenseUnit {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(upkeep, "upkeep");
    }

    public static DefenseUnit from(DefenseUnitRow row) {
        return new DefenseUnit(row.id(), row.cityId(), row.type(), row.world(),
                row.spawnX(), row.spawnY(), row.spawnZ(), row.upkeep(), row.active());
    }

    public DefenseUnitRow toRow() {
        return new DefenseUnitRow(id, cityId, type, world, x, y, z, upkeep, active);
    }

    public DefenseUnit withActive(boolean value) {
        return new DefenseUnit(id, cityId, type, world, x, y, z, upkeep, value);
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
}
