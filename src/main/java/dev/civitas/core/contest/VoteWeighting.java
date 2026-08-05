package dev.civitas.core.contest;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;

/**
 * SPEC 13.4's anti-abuse rules, as decisions with no I/O in them.
 *
 * <h2>Why refusing and discarding are different</h2>
 * SPEC 13.4 words two of these rules differently, and the difference is worth keeping.
 * "Players cannot vote for their own city" is a prohibition, so that vote is refused and the
 * player is told why. "Votes from accounts sharing an IP with a member of the entered city are
 * discarded" is not a prohibition, so that vote is accepted, stored, and weighed at nothing.
 *
 * <p>That is not pedantry. Telling somebody "your vote was discarded because you share a
 * connection with a member of that city" reports on another account's connection to a player
 * who did not ask and has no business knowing. Storing the vote at zero weight achieves what
 * SPEC 17.6 case 72 wants, which is that the vote does not count, and tells the voter nothing
 * about anyone else.
 */
public final class VoteWeighting {

    /** A vote from an account that clears every bar. */
    public static final double FULL = 1.0;

    /** A vote that one of SPEC 13.4's rules discards. */
    public static final double DISCARDED = 0.0;

    private final ConfigManager configs;

    public VoteWeighting(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /**
     * What a vote is worth.
     *
     * @param playtimeMs      the voter's active playtime
     * @param sharesLoginWith whether the voter's connection matches a member of the entered
     *                        city, which the caller resolves; this class does not know what a
     *                        login hash is
     * @return {@link #FULL}, the reduced weight, or {@link #DISCARDED}
     */
    public double weigh(long playtimeMs, boolean sharesLoginWith) {
        if (sharesLoginWith && discardsSharedLogins()) {
            return DISCARDED;
        }
        if (playtimeMs < lowPlaytimeMs()) {
            return lowPlaytimeWeight();
        }
        return FULL;
    }

    /** SPEC 13.4: "Players cannot vote for their own city." */
    public boolean isSelfVote(Integer voterCityId, int entryCityId) {
        return blocksSelfVotes() && voterCityId != null && voterCityId == entryCityId;
    }

    /** Whether a score is inside the SPEC 13.4 range of 1 to 10. */
    public boolean isScoreInRange(int score) {
        return score >= minScore() && score <= maxScore();
    }

    // ==================================================================================
    // Configuration, SPEC 13.4
    // ==================================================================================

    public int minScore() {
        return contests().getInt("contests.vote-min", 1);
    }

    public int maxScore() {
        return contests().getInt("contests.vote-max", 10);
    }

    /** Below this much active playtime, a vote is weighted down rather than refused. */
    public long lowPlaytimeMs() {
        return TimeUnit.HOURS.toMillis(contests().getLong("contests.anti-abuse.low-playtime-hours", 5));
    }

    public double lowPlaytimeWeight() {
        return contests().getDouble("contests.anti-abuse.low-playtime-vote-weight", 0.25);
    }

    public boolean blocksSelfVotes() {
        return contests().getBoolean("contests.anti-abuse.block-self-city-votes", true);
    }

    public boolean discardsSharedLogins() {
        return contests().getBoolean("contests.anti-abuse.discard-ip-matched-votes", true);
    }

    /**
     * SPEC 13.4: "Entries must be built during the contest window (verified against block
     * placement logs)."
     *
     * <p>Whether an operator has asked for that check. Whether it can be performed is a
     * different question, and the answer today is no: see
     * {@code ContestService.canVerifyBuildWindow}.
     */
    public boolean verifiesBuildWindow() {
        return contests().getBoolean("contests.anti-abuse.verify-built-during-window", true);
    }

    private org.bukkit.configuration.file.FileConfiguration contests() {
        return configs.get(ConfigFile.EVENTS);
    }
}
