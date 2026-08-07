package dev.civitas.storage.row;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A row of {@code bounties}, SPEC 4.7.
 *
 * <p>The money is escrowed when the row is written, so the row is the only record that it
 * exists at all. A claimed or refunded bounty keeps its row rather than being deleted: SPEC 1.5
 * makes every coin movement auditable, and a deleted row is money the ledger says went
 * somewhere that no longer exists.
 */
public record BountyRow(
        long id,
        UUID placerUuid,
        UUID targetUuid,
        BigDecimal amount,
        long placedAt,
        long expiresAt,
        String state,
        UUID claimedBy,
        Long claimedAt) {
}
