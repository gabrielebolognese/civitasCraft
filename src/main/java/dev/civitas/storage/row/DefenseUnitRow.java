package dev.civitas.storage.row;

import java.math.BigDecimal;

/**
 * A row of {@code defense_units}, SPEC 3.9 and SPEC 12.
 *
 * <p>Carries {@code world}, which SPEC 3.9 omits: SPEC 20 decision 4 lets a city hold
 * territory in several worlds, so spawn coordinates alone would not locate the unit.
 *
 * @param active false while the unit is despawned for unpaid upkeep, SPEC 12.3
 */
public record DefenseUnitRow(
        int id,
        int cityId,
        String type,
        String world,
        double spawnX,
        double spawnY,
        double spawnZ,
        BigDecimal upkeep,
        boolean active) {
}
