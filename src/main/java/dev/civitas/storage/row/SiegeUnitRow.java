package dev.civitas.storage.row;

/**
 * A row of {@code siege_units}, SPEC 29.4.
 *
 * @param points  copied from the roster at purchase rather than looked up. An operator who retunes
 *                {@code defense.yml} mid-war would otherwise change what a war already under way
 *                has spent, and could push an attacker over a budget they were inside when they
 *                bought
 * @param alive   false once killed. The row is kept because it is the record of what the budget
 *                was spent on, and SPEC 29.4 refunds nothing, ever — a dead unit is spent money
 */
public record SiegeUnitRow(
        int id,
        int warId,
        int cityId,
        String type,
        int points,
        String world,
        double x,
        double y,
        double z,
        boolean alive,
        long boughtAt) {

    public SiegeUnitRow {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world is required");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
    }
}
