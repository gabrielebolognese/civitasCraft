package dev.civitas.storage.row;

/**
 * A row of {@code truces}, SPEC 3.9.
 *
 * <p>Stored once per pair with {@code cityAId < cityBId}, matching {@link AllianceRow}, so a
 * truce blocks war declaration in both directions (SPEC 14.3).
 */
public record TruceRow(
        int cityAId,
        int cityBId,
        long expiresAt) {
}
