package dev.civitas.storage.row;

/**
 * A row of {@code siege_camps}, SPEC 29.5.
 *
 * @param health      what the camp has left of its 200; SPEC 29.5 calls it "a block-entity",
 *                    which Bukkit has no such thing as, so the health lives here and the block
 *                    is a marker
 * @param destroyedAt null while it stands
 * @param rebuilt     SPEC 29.5 allows exactly one rebuild per war, at half cost
 */
public record SiegeCampRow(
        int id,
        int warId,
        int cityId,
        String world,
        int x,
        int y,
        int z,
        double health,
        long placedAt,
        Long destroyedAt,
        boolean rebuilt) {

    public SiegeCampRow {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world is required");
        }
    }

    public boolean stands() {
        return destroyedAt == null;
    }
}
