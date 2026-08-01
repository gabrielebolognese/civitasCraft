package dev.civitas.storage.row;

import java.math.BigDecimal;

/**
 * A row of {@code economy_snapshots}, added by migration V3 for SPEC 4.8.
 *
 * @param playerTotal   every wallet on the server added together
 * @param treasuryTotal every live city treasury added together
 */
public record EconomySnapshotRow(
        long id,
        long timestamp,
        BigDecimal playerTotal,
        BigDecimal treasuryTotal) {

    /** What SPEC 4.8 calls "total circulating currency". */
    public BigDecimal circulation() {
        return playerTotal.add(treasuryTotal);
    }
}
