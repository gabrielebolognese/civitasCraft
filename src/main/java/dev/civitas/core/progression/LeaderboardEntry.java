package dev.civitas.core.progression;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One line of a leaderboard.
 *
 * <p>Carries numbers and a name, never rendered text: how a line reads is decided by the
 * language files at print time, because SPEC 2.1 forbids building player-facing strings in
 * Java.
 *
 * @param rank      1 for the top entry
 * @param name      the player or city name, printed as literal text and never as MiniMessage
 * @param value     the ranked figure; wins, for SPEC 13.3's War Record
 * @param secondary the tiebreaker where a board has one, losses for War Record, otherwise
 *                  {@code null}
 */
public record LeaderboardEntry(
        int rank,
        String name,
        BigDecimal value,
        BigDecimal secondary) {

    public LeaderboardEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
    }

    /** A line on a board that ranks by a single figure. */
    public static LeaderboardEntry of(int rank, String name, BigDecimal value) {
        return new LeaderboardEntry(rank, name, value, null);
    }

    /** A line on a board that ranks by a whole count. */
    public static LeaderboardEntry of(int rank, String name, long value) {
        return new LeaderboardEntry(rank, name, BigDecimal.valueOf(value), null);
    }

    public boolean hasSecondary() {
        return secondary != null;
    }
}
