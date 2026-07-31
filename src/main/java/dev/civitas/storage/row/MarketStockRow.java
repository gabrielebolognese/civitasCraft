package dev.civitas.storage.row;

import java.math.BigDecimal;

/**
 * A row of {@code market_stock}, SPEC 3.9, feeding the SPEC 4.4 price formula.
 *
 * @param currentStock may go negative: SPEC 17.3 case 28 keeps the market an infinite
 *                     seller and lets the price clamp hold instead
 */
public record MarketStockRow(
        String material,
        int currentStock,
        int targetStock,
        BigDecimal basePrice) {
}
