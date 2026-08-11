package dev.civitas.core.season;

import java.util.Objects;

import dev.civitas.core.progression.LeaderboardType;

/**
 * One season, SPEC 35.
 *
 * <h2>What a season is, and what it is not</h2>
 *
 * <p>SPEC 35.2 is unusually emphatic: "<b>Nothing a player built or owns is ever taken away. This
 * is not a wipe. It is a scoreboard reset.</b> That distinction must be stated explicitly and
 * repeatedly in-game, because 'season' on most servers means 'your stuff is deleted' and players
 * will assume the worst."
 *
 * <p>So there is no code in this package that deletes a city, a claim, a balance or a block, and
 * nothing here can reach one. The only thing a season changes is which window a leaderboard is
 * measured over.
 */
public record Season(int id, String name, String theme, long startsAt, long endsAt,
                     State state, Long endedAt) {

    public enum State {
        /** Scores are accruing against this season's baselines. */
        RUNNING,
        /** Scored, paid and written into the Hall of Fame. */
        FINISHED
    }

    public Season {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(state, "state");
        if (endsAt <= startsAt) {
            throw new IllegalArgumentException("a season must end after it starts");
        }
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public long millisRemaining(long now) {
        return Math.max(0, endsAt - now);
    }

    public int dayOf(long now) {
        return (int) ((now - startsAt) / (24L * 60 * 60 * 1000)) + 1;
    }

    public boolean hasExpired(long now) {
        return isRunning() && now >= endsAt;
    }

    /**
     * Which boards a season actually resets.
     *
     * <h3>The finding, and it is structural rather than a choice</h3>
     *
     * <p>SPEC 35.2 says a season resets "leaderboard rankings only" and that balances, treasuries,
     * claims and member rosters never reset. For four of the nine boards those are <b>the same
     * number</b>: Wealth <em>is</em> a balance, City Treasury <em>is</em> a treasury, City Size
     * <em>is</em> a claim count and City Population <em>is</em> a member roster. There is no window
     * to measure them over, because a stock has no "since" — it is what it is right now.
     *
     * <p>So a season rebases the five <b>flow</b> boards, which count things that happened, and
     * leaves the four <b>stock</b> boards alone. Resetting a stock board would mean taking
     * something away, which is the one thing SPEC 35.2 forbids in bold.
     *
     * <p>This is not a gap: it is what pillar 1.3 needs anyway. A newcomer cannot out-bank a
     * six-month-old city and never could, and SPEC 13.3's whole argument is that "servers where
     * wealth is the only ladder become toxic because there is exactly one way to matter." The
     * ladders a season resets are precisely the ones a newcomer can climb.
     */
    public static boolean resets(LeaderboardType board) {
        return switch (board) {
            case CONTRIBUTION, BUILDER, FARMER, CONTEST_CHAMPIONS, WAR_RECORD -> true;
            case WEALTH, CITY_TREASURY, CITY_SIZE, CITY_POPULATION -> false;
        };
    }

    /** The five boards a season ranks. */
    public static java.util.List<LeaderboardType> seasonalBoards() {
        return java.util.Arrays.stream(LeaderboardType.values())
                .filter(Season::resets)
                .toList();
    }
}
