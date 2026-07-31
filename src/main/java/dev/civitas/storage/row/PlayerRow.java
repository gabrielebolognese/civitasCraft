package dev.civitas.storage.row;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A row of {@code players}, SPEC 3.1, plus the two cooldown timestamps added by V2.
 *
 * <p>A storage row, not a domain object: it mirrors the columns exactly and carries no
 * behaviour.
 *
 * @param lastCityLeave   when this player last left a city, driving the SPEC 5.2 24-hour
 *                        cooldown before joining a different one; 0 means never
 * @param lastCityDisband when this player last disbanded a city, driving the SPEC 17.1
 *                        case 7 cooldown before founding another; 0 means never
 */
public record PlayerRow(
        UUID uuid,
        String lastKnownName,
        BigDecimal balance,
        Integer cityId,
        Integer rankId,
        long firstJoin,
        long lastSeen,
        long totalPlaytimeMs,
        long activePlaytimeMs,
        int dailyStreak,
        long lastDailyClaim,
        long newcomerUntil,
        boolean frozen,
        long lastCityLeave,
        long lastCityDisband) {
}
