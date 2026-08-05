package dev.civitas.core.progression;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The boards of SPEC 13.3.
 *
 * <h2>Nine, not seven</h2>
 * SPEC 13.3 says "There are seven" and SPEC 19 repeats it, but the table in 13.3 lists nine
 * rows and no section anywhere enumerates a set of seven. Every board named in that table is
 * here, because dropping two would mean choosing which two, and that choice appears nowhere
 * in the specification. Recorded in OPEN_QUESTIONS.md.
 *
 * <p>Declaration order is the order SPEC 13.3 lists them in, and it is the order
 * {@code /leaderboard} prints them in. Wealth is first only because SPEC's table puts it
 * there; SPEC 13.3 requires all of them to be shown "with equal prominence", so nothing in
 * the rendering treats it as the headline.
 */
public enum LeaderboardType {

    /** Personal balance. */
    WEALTH("wealth", Scope.PLAYER, Format.MONEY),

    /** City treasury. */
    CITY_TREASURY("city-treasury", Scope.CITY, Format.MONEY),

    /** Claim count. */
    CITY_SIZE("city-size", Scope.CITY, Format.COUNT),

    /** Active member count, by the SPEC 6.2 definition of active. */
    CITY_POPULATION("city-population", Scope.CITY, Format.COUNT),

    /** Cumulative contest points. Needs the contest cycle, M15. */
    CONTEST_CHAMPIONS("contest-champions", Scope.CITY, Format.COUNT),

    /** Wins, with losses as a tiebreaker. Needs the war system, M19. */
    WAR_RECORD("war-record", Scope.CITY, Format.RECORD),

    /** Lifetime treasury deposits, personal. */
    CONTRIBUTION("contribution", Scope.PLAYER, Format.MONEY),

    /** Blocks placed, excluding war zones. */
    BUILDER("builder", Scope.PLAYER, Format.COUNT),

    /** Crops harvested. */
    FARMER("farmer", Scope.PLAYER, Format.COUNT);

    /** Whether a board ranks players or cities. Decides which name column it prints. */
    public enum Scope { PLAYER, CITY }

    /** How a board's number is rendered. */
    public enum Format {
        /** Coins, printed with the currency symbol. */
        MONEY,
        /** A whole count of things. */
        COUNT,
        /** Wins and losses together, SPEC 13.3's War Record. */
        RECORD
    }

    private final String key;
    private final Scope scope;
    private final Format format;

    LeaderboardType(String key, Scope scope, Format format) {
        this.key = key;
        this.scope = scope;
        this.format = format;
    }

    /** The name a player types after {@code /leaderboard}, and the {@code lang/} key suffix. */
    public String key() {
        return key;
    }

    public Scope scope() {
        return scope;
    }

    public Format format() {
        return format;
    }

    /** {@code leaderboard.type.<key>.name} in the language files. */
    public String nameKey() {
        return "leaderboard.type." + key + ".name";
    }

    /** {@code leaderboard.type.<key>.metric}, the one-line description of what is ranked. */
    public String metricKey() {
        return "leaderboard.type." + key + ".metric";
    }

    /** The lang key for one line of this board, which differs by how its value is formatted. */
    public String entryKey() {
        return switch (format) {
            case MONEY -> "leaderboard.entry.money";
            case COUNT -> "leaderboard.entry.count";
            case RECORD -> "leaderboard.entry.record";
        };
    }

    public static List<LeaderboardType> all() {
        return List.of(values());
    }

    /** Resolves what a player typed. Case and separator insensitive, so "City Size" works. */
    public static Optional<LeaderboardType> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String normalised = input.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        return Arrays.stream(values())
                .filter(type -> type.key.equals(normalised))
                .findFirst();
    }
}
