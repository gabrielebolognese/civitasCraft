package dev.civitas.core.diplomacy;

import java.util.Locale;
import java.util.Optional;

/**
 * Where an alliance row is in its life, SPEC 14.2.
 *
 * <p>Four states rather than two, because SPEC 14.2 gives breaking a 24-hour notice period
 * "during which the alliance still holds". A pair in {@link #BREAKING} is allied for every
 * purpose that matters, and simply has an end date.
 */
public enum AllianceState {

    /** Proposed by one city, waiting for the other to accept. */
    PENDING,

    /** In force. */
    ACTIVE,

    /**
     * Notice given, but still in force until the notice expires.
     *
     * <p>This is what stops a city breaking an alliance the instant a war is declared and
     * turning on its ally the same evening.
     */
    BREAKING,

    /**
     * Ended, and kept only to time the SPEC 14.2 seven-day cooldown before re-allying.
     *
     * <p>The row survives its own alliance for exactly that reason: deleting it would lose
     * the one fact the cooldown needs.
     */
    BROKEN;

    /** Whether a pair in this state counts as allied right now. */
    public boolean isAllied() {
        return this == ACTIVE || this == BREAKING;
    }

    public static Optional<AllianceState> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalised = name.trim().toUpperCase(Locale.ROOT);
        for (AllianceState state : values()) {
            if (state.name().equals(normalised)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }
}
