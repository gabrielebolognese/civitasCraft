package dev.civitas.storage.row;

import java.math.BigDecimal;

/**
 * A row of {@code money_supply}, SPEC 21.4 Class G.
 *
 * <p>Three stocks and nothing else. The flows SPEC 21.4 also asks for are read from the ledger,
 * which already holds every one of them and which SPEC 1.5 makes authoritative — a second copy
 * could disagree with the first, and the copy is the one an investigation would be reading.
 *
 * @param escrowTotal money that exists but belongs to nobody right now: war wagers held by SPEC
 *                    11.3's escrow and bounties held by SPEC 4.7. Counted separately because it
 *                    is neither in a wallet nor in a treasury, and a supply figure that omitted
 *                    it would appear to shrink every time a war was declared
 */
public record MoneySupplyRow(
        long id,
        long timestamp,
        BigDecimal playerTotal,
        BigDecimal treasuryTotal,
        BigDecimal escrowTotal) {

    /** Everything in circulation, which is the number SPEC 4.8 tracks week over week. */
    public BigDecimal circulation() {
        return playerTotal.add(treasuryTotal).add(escrowTotal);
    }
}
