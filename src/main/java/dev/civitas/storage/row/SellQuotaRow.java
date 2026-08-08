package dev.civitas.storage.row;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One player's SPEC 21.5 daily sell quota, as stored.
 *
 * @param uuid        the player
 * @param periodStart 00:00 of the day this row counts, in server time
 * @param used        value already sold to the server market inside that day
 */
public record SellQuotaRow(UUID uuid, long periodStart, BigDecimal used) {

    public SellQuotaRow {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid is required");
        }
        if (used == null || used.signum() < 0) {
            throw new IllegalArgumentException("used must be present and not negative");
        }
    }
}
