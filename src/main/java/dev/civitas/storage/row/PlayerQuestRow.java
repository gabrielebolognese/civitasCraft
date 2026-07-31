package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code player_quests}, SPEC 3.9.
 *
 * <p>Carries a surrogate {@code id} that SPEC 3.9 does not list, because daily quests reset
 * (SPEC 13.1) and the same player can be assigned the same quest on many days, so no subset
 * of the listed columns is a stable key.
 *
 * @param completedAt null while the quest is still in progress
 */
public record PlayerQuestRow(
        long id,
        UUID uuid,
        String questId,
        int progress,
        long assignedAt,
        Long completedAt) {
}
