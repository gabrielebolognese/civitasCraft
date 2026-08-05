package dev.civitas.core.contest;

import java.util.Objects;

import dev.civitas.storage.row.ContestRow;

/**
 * One cycle of SPEC 13.4, with its phase boundaries worked out.
 *
 * <p>SPEC 13.4 describes the cycle in days from the start, so the two boundaries inside it are
 * derived here once rather than recomputed from config at every comparison. That matters for
 * more than tidiness: an operator who edits {@code build-days} mid-contest would otherwise
 * move the submission deadline of a contest already under way, which is exactly the kind of
 * thing players notice and nobody can explain.
 *
 * @param submissionsCloseAt end of SPEC 13.4's day 11
 * @param votingEndsAt       end of its day 13
 * @param endsAt             day 14, when results are published
 */
public record Contest(
        int id,
        String theme,
        long startsAt,
        long submissionsCloseAt,
        long votingEndsAt,
        long endsAt,
        ContestState state) {

    public Contest {
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(state, "state");
    }

    /** Rebuilds the phase boundaries from a stored row. */
    public static Contest of(ContestRow row, long buildMillis, long votingMillis) {
        ContestState state = ContestState.parse(row.state()).orElse(ContestState.BUILDING);
        return new Contest(row.id(), row.theme(), row.startsAt(),
                row.startsAt() + buildMillis,
                row.startsAt() + buildMillis + votingMillis,
                row.endsAt(), state);
    }

    public Contest withState(ContestState next) {
        return new Contest(id, theme, startsAt, submissionsCloseAt, votingEndsAt, endsAt, next);
    }

    /**
     * The phase this contest should be in at {@code now}, by the clock alone.
     *
     * <p>Separate from {@link #state}, which is what has actually been recorded. The two
     * differ whenever a boundary passed while the server was down, and reconciling them is the
     * cycle's whole job.
     */
    public ContestState phaseAt(long now) {
        if (now < submissionsCloseAt) {
            return ContestState.BUILDING;
        }
        if (now < votingEndsAt) {
            return ContestState.VOTING;
        }
        if (now < endsAt) {
            return ContestState.SCORING;
        }
        return ContestState.FINISHED;
    }

    public boolean isOver(long now) {
        return now >= endsAt || state == ContestState.FINISHED;
    }

    /** Milliseconds until the current phase ends, or 0 if the contest is over. */
    public long millisUntilNextPhase(long now) {
        return switch (phaseAt(now)) {
            case BUILDING -> Math.max(0L, submissionsCloseAt - now);
            case VOTING -> Math.max(0L, votingEndsAt - now);
            case SCORING -> Math.max(0L, endsAt - now);
            case FINISHED -> 0L;
        };
    }
}
