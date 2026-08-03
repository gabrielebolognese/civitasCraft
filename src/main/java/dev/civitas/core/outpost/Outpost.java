package dev.civitas.core.outpost;

import java.util.Objects;

import dev.civitas.storage.row.OutpostRow;

/**
 * One outpost, SPEC 7.
 *
 * <p>The chunk an outpost occupies is not stored here: it is the {@code claims} row whose
 * {@code outpost_id} points at this one. Keeping the position in exactly one place means the
 * claim cache and the outpost list can never disagree about where an outpost is, which
 * matters because SPEC 7.4's auto-conversion turns one into the other.
 *
 * <p>What is stored here is the warp destination, which is a position <em>inside</em> that
 * chunk and can be moved by {@code /city outpost setwarp} without the outpost moving.
 */
public record Outpost(
        int id,
        int cityId,
        String name,
        double warpX,
        double warpY,
        double warpZ,
        float warpYaw,
        float warpPitch,
        long createdAt) {

    public Outpost {
        Objects.requireNonNull(name, "name");
    }

    public static Outpost from(OutpostRow row) {
        return new Outpost(row.id(), row.cityId(), row.name(), row.tpX(), row.tpY(), row.tpZ(),
                row.tpYaw(), row.tpPitch(), row.createdAt());
    }

    public OutpostRow toRow() {
        return new OutpostRow(id, cityId, name, warpX, warpY, warpZ, warpYaw, warpPitch,
                createdAt);
    }

    public Outpost withName(String newName) {
        return new Outpost(id, cityId, newName, warpX, warpY, warpZ, warpYaw, warpPitch,
                createdAt);
    }

    public Outpost withWarp(double x, double y, double z, float yaw, float pitch) {
        return new Outpost(id, cityId, name, x, y, z, yaw, pitch, createdAt);
    }

    /** Names are compared case-insensitively, because players type them into commands. */
    public boolean isNamed(String other) {
        return other != null && name.equalsIgnoreCase(other.trim());
    }
}
