package dev.civitas.storage.row;

/** A row of {@code outposts}, SPEC 3.5. */
public record OutpostRow(
        int id,
        int cityId,
        String name,
        double tpX,
        double tpY,
        double tpZ,
        float tpYaw,
        float tpPitch,
        long createdAt) {
}
