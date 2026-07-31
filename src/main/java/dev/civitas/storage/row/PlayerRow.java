package dev.civitas.storage.row;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A row of {@code players}, SPEC 3.1.
 *
 * <p>A storage row, not a domain object: it mirrors the columns exactly and carries no
 * behaviour. The domain model that wraps it arrives in M2.
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
        boolean frozen) {
}
