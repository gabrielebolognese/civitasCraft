package dev.civitas.storage.row;

import java.math.BigDecimal;

/**
 * A row of {@code wars}, SPEC 3.7.
 *
 * @param state                      kept as a string: SPEC 11.2 and 11.8.5 name states
 *                                   ({@code DECLARED}, {@code ROLLBACK_FAILED}) that the
 *                                   SPEC 3.7 list omits, so the war service owns the set
 * @param rollbackCheckpointSequence last sequence restored, per SPEC 11.8.5, so a rollback
 *                                   interrupted by a crash resumes instead of restarting
 */
public record WarRow(
        int id,
        int attackerCityId,
        int defenderCityId,
        long declaredAt,
        long prepEndsAt,
        long warEndsAt,
        String state,
        int attackerScore,
        int defenderScore,
        Integer winnerCityId,
        BigDecimal wager,
        Long rollbackCompletedAt,
        Long rollbackCheckpointSequence) {
}
