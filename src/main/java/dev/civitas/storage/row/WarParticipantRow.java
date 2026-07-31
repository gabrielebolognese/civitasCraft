package dev.civitas.storage.row;

/**
 * A row of {@code war_participants}, SPEC 3.9.
 *
 * @param side    {@code ATTACKER} or {@code DEFENDER}
 * @param isAlly  true when the city joined an ally's war under SPEC 11.10
 */
public record WarParticipantRow(
        int warId,
        int cityId,
        String side,
        boolean isAlly) {
}
