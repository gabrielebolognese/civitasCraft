package dev.civitas.storage.row;

import java.util.UUID;

/**
 * One player's active-playtime baseline for the current day, SPEC 21.4 F12.
 *
 * @param uuid       the player
 * @param dayStart   00:00 of the day this baseline belongs to, server time
 * @param baselineMs lifetime active playtime as it stood when that day began
 */
public record DailyActivityRow(UUID uuid, long dayStart, long baselineMs) {

    public DailyActivityRow {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid is required");
        }
        if (baselineMs < 0) {
            throw new IllegalArgumentException("baselineMs cannot be negative");
        }
    }
}
