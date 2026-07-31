package dev.civitas.storage.row;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A row of {@code claims}, SPEC 3.4.
 *
 * @param costPaid what was actually paid, used for the SPEC 6.4 refund
 * @param type     {@code CORE}, {@code NORMAL} or {@code OUTPOST}; kept as a string because
 *                 the enum belongs to the claim module in M3
 * @param outpostId non-null only when {@code type} is {@code OUTPOST}
 */
public record ClaimRow(
        long id,
        int cityId,
        String world,
        int chunkX,
        int chunkZ,
        long claimedAt,
        UUID claimedBy,
        BigDecimal costPaid,
        String type,
        Integer outpostId) {
}
