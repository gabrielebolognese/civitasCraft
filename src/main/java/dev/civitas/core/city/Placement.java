package dev.civitas.core.city;

import org.bukkit.Location;

/**
 * Where a city is being founded: the chunk that becomes its core, and the exact position
 * that becomes its spawn.
 *
 * <p>Plain values rather than a Bukkit {@link Location} so {@link CityService} can be tested
 * without a world loaded.
 */
public record Placement(
        String world,
        int chunkX,
        int chunkZ,
        double x,
        double y,
        double z,
        float yaw,
        float pitch) {

    public static Placement of(Location location) {
        return new Placement(
                location.getWorld().getName(),
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4,
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch());
    }
}
