package dev.civitas.storage.row;

/**
 * A row of {@code waystations}, SPEC 39.10.
 *
 * <p>No name column: SPEC 39.11 addresses a waystation by world, which SPEC 39.10's one-per-
 * city-per-world limit makes unambiguous.
 */
public record WaystationRow(
        int id,
        int cityId,
        String world,
        long createdAt,
        double warpX,
        double warpY,
        double warpZ,
        float warpYaw,
        float warpPitch) {

    public WaystationRow {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world is required");
        }
    }
}
