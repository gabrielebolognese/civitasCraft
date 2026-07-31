package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code audit_log}, SPEC 3.9 and SPEC 17.6 case 80.
 *
 * <p>Admin actions only, deliberately separate from the ledger and not clearable in game.
 * SPEC 3.9 names the table but not its columns; SPEC 17.6 case 80 requires actor, target,
 * timestamp and reason, and {@code action} is what makes those four meaningful.
 */
public record AuditLogRow(
        long id,
        long timestamp,
        UUID actorUuid,
        String action,
        String target,
        String reason,
        String metadata) {
}
