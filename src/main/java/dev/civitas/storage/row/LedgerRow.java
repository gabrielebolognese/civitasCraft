package dev.civitas.storage.row;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A row of {@code ledger}, SPEC 3.6. Append-only: never updated, never deleted.
 *
 * @param type         one of the SPEC 4.6 transaction types, as a string because the
 *                     {@code TransactionType} enum belongs to the economy module in M5
 * @param amount       signed
 * @param balanceAfter snapshot taken at write time, for reconciliation
 * @param metadata     JSON blob, context-specific, may be null
 */
public record LedgerRow(
        long id,
        long timestamp,
        String type,
        UUID actorUuid,
        UUID targetUuid,
        Integer cityId,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String metadata) {
}
