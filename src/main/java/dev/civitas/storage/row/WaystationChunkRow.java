package dev.civitas.storage.row;

import java.math.BigDecimal;

/**
 * One chunk of a waystation, SPEC 39.10.
 *
 * @param costPaid what was paid for this chunk, so a refund returns a share of the price paid
 *                 rather than of the current price — the SPEC 21.4 F2 rule that a discount
 *                 cannot be laundered into a full-price refund
 */
public record WaystationChunkRow(long id, int waystationId, String world, int chunkX, int chunkZ,
                                 long claimedAt, BigDecimal costPaid) {

    public WaystationChunkRow {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world is required");
        }
        if (costPaid == null || costPaid.signum() < 0) {
            throw new IllegalArgumentException("costPaid must be present and not negative");
        }
    }
}
