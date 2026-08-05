package dev.civitas.core.contest;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One player's scoring of one entry, SPEC 13.4 step 4.
 *
 * @param weight what SPEC 13.4's anti-abuse rules decided this vote is worth: 1 normally,
 *               0.25 for an account under the playtime bar, 0 for one its rules discard
 */
public record Vote(UUID voter, int entryId, Map<VoteAxis, Integer> scores, double weight) {

    public Vote {
        Objects.requireNonNull(voter, "voter");
        scores = Map.copyOf(new EnumMap<>(Objects.requireNonNull(scores, "scores")));
    }

    /**
     * The single figure this vote contributes, before weighting: the mean of its axes.
     *
     * <p>Equal weight per axis, because SPEC 13.4 lists the three without ranking them.
     */
    public double combined() {
        if (scores.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (VoteAxis axis : VoteAxis.all()) {
            total += scores.getOrDefault(axis, 0);
        }
        return total / VoteAxis.all().size();
    }

    /** Whether this vote counts at all. A discarded vote is stored, and weighs nothing. */
    public boolean counts() {
        return weight > 0.0;
    }

    public int score(VoteAxis axis) {
        return scores.getOrDefault(axis, 0);
    }
}
