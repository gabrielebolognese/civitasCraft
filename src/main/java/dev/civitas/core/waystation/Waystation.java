package dev.civitas.core.waystation;

import java.util.Objects;

import dev.civitas.storage.row.WaystationRow;

/**
 * One city waystation, SPEC 39.10.
 *
 * <p>Unlike an outpost it has no name. SPEC 39.11 addresses it by <em>world</em> —
 * {@code /city waystation tp <world>} — which it can do because SPEC 39.10 allows only one per
 * city per resource world. The world is the identity, so there is nothing for a name to
 * disambiguate and asking a player to invent one would be asking for a label they would then
 * have to remember in order to use it.
 *
 * <p>The chunks are not stored here. They are {@code waystation_chunks} rows pointing at this
 * one, which is the shape the SPEC 39 rework found already worked for multi-chunk outposts:
 * keeping a position in exactly one place means the two views cannot disagree about where
 * something is.
 */
public record Waystation(
        int id,
        int cityId,
        String world,
        double warpX,
        double warpY,
        double warpZ,
        float warpYaw,
        float warpPitch,
        long createdAt) {

    public Waystation {
        Objects.requireNonNull(world, "world");
    }

    public static Waystation from(WaystationRow row) {
        return new Waystation(row.id(), row.cityId(), row.world(), row.warpX(), row.warpY(),
                row.warpZ(), row.warpYaw(), row.warpPitch(), row.createdAt());
    }

    public WaystationRow toRow() {
        return new WaystationRow(id, cityId, world, createdAt, warpX, warpY, warpZ,
                warpYaw, warpPitch);
    }

    public Waystation withWarp(double x, double y, double z, float yaw, float pitch) {
        return new Waystation(id, cityId, world, x, y, z, yaw, pitch, createdAt);
    }

    /** World names are compared case-insensitively, because players type them into commands. */
    public boolean isIn(String other) {
        return other != null && world.equalsIgnoreCase(other.trim());
    }
}
