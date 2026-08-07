package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code reports}, SPEC 15.3.
 *
 * <p>Kept whatever the outcome. A moderation decision that erased its own evidence would be
 * the one action in this plugin nobody could review afterwards.
 */
public record ReportRow(
        long id,
        UUID reporterUuid,
        UUID targetUuid,
        String reason,
        long createdAt,
        String state,
        UUID handledBy,
        Long handledAt,
        String resolution) {
}
