package dev.civitas.storage.row;

import java.math.BigDecimal;

/**
 * A row of {@code defense_units}, SPEC 3.9 and SPEC 12.
 *
 * <p>Carries {@code world}, which SPEC 3.9 omits: SPEC 20 decision 4 lets a city hold
 * territory in several worlds, so spawn coordinates alone would not locate the unit.
 *
 * @param active       false while the unit is despawned for unpaid upkeep, SPEC 12.3
 * @param health       current health, or null when it has never materialised. Null reads as
 *                     full: a unit nobody has ever approached has taken no damage, and
 *                     defaulting to zero would kill every unit on the V22 upgrade.
 * @param dormantSince when it last stopped being an entity, or null while it is one. SPEC
 *                     25.4's dormant regeneration measures from here.
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
        boolean active,
        Double health,
        Long dormantSince) {

    /** SPEC 25.4: a unit that has never materialised is at full health, not at zero. */
    public double healthOr(double maximum) {
        return health == null ? maximum : health;
    }
}
