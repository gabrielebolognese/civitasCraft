package dev.civitas.core.contest;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The three things a voter scores, SPEC 13.4 step 4.
 *
 * <p>Three axes rather than one number because a contest with a single score becomes a
 * popularity vote for the biggest build. Splitting technical skill from theme fit is what
 * lets a small, exactly-on-theme entry beat a large one that ignored the brief.
 */
public enum VoteAxis {

    CREATIVITY("creativity"),
    TECHNICAL_SKILL("technical-skill"),
    THEME_FIT("theme-fit");

    private final String key;

    VoteAxis(String key) {
        this.key = key;
    }

    /** The name used in {@code events.yml} and in the command. */
    public String key() {
        return key;
    }

    /** The database column this axis is stored in. */
    public String column() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String messageKey() {
        return "contest.axis." + key;
    }

    public static List<VoteAxis> all() {
        return List.of(values());
    }

    public static Optional<VoteAxis> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String normalised = input.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return all().stream().filter(axis -> axis.key.equals(normalised)).findFirst();
    }
}
