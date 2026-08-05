package dev.civitas.core.contest;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a contest is in the SPEC 13.4 cycle.
 *
 * <p>SPEC 13.4 describes the cycle in days rather than in states, so these are the phases
 * those days divide into: build, vote, tally, done. {@link #SCORING} exists as a state of its
 * own rather than as a moment inside the tally because the tally moves money into treasuries,
 * and a server that dies halfway through one must come back knowing it was in the middle of
 * it rather than replaying the payouts.
 */
public enum ContestState {

    /** Days 0 to 11: the theme is announced and cities build. */
    BUILDING,

    /** Days 11 to 13: submissions are closed and players score the entries. */
    VOTING,

    /** Day 14: votes are being tallied and prizes paid. Transient. */
    SCORING,

    /** Scored, paid, and on the record. */
    FINISHED;

    public String key() {
        return name();
    }

    /** The {@code lang/} key for this phase's name. */
    public String messageKey() {
        return "contest.state." + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Whether entries may still be marked and submitted. */
    public boolean acceptsSubmissions() {
        return this == BUILDING;
    }

    /** Whether votes are being taken. */
    public boolean acceptsVotes() {
        return this == VOTING;
    }

    public static Optional<ContestState> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
