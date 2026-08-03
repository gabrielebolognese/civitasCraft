package dev.civitas.storage.row;

import java.math.BigDecimal;

/**
 * A row of {@code city_challenges}, added in V5 for SPEC 13.2.
 *
 * <p>SPEC 3 lists no table for weekly challenges. They are keyed by city and week rather than
 * by player because SPEC 13.2 pools progress across every member: one row is one city's
 * attempt at one challenge in one week.
 *
 * @param weekStart  Monday 00:00 in the server's zone
 * @param completedAt null while the challenge is still running
 */
public record CityChallengeRow(
        long id,
        int cityId,
        String challengeId,
        long progress,
        long target,
        BigDecimal reward,
        long weekStart,
        Long completedAt) {

    public boolean isComplete() {
        return completedAt != null;
    }
}
