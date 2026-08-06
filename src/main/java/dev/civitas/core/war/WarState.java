package dev.civitas.core.war;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a war is in the SPEC 11.2 lifecycle.
 *
 * <p>SPEC 3.7 lists five states, SPEC 11.2 also names {@code DECLARED}, and SPEC 11.8.5 names
 * {@code ROLLBACK_FAILED}. M1 left {@code wars.state} as text precisely so this enum could
 * settle the set without a schema change, which is what it does.
 */
public enum WarState {

    /**
     * Declared and waiting on the defender.
     *
     * <p>SPEC 11.3 gives them six hours to decline. The wagers are already escrowed, so this
     * is a state in which real money is held and no fighting can happen.
     */
    DECLARED,

    /** SPEC 11.5's 48 hours: fortify, buy units, rally. No grief. */
    PREP,

    /** SPEC 11.6's seven days. The only state in which anything can be destroyed. */
    ACTIVE,

    /**
     * The zone is closed and M18's engine is putting it back.
     *
     * <p>SPEC 11.8.2 step 1 evacuates first, so nobody is inside while blocks move.
     */
    ROLLING_BACK,

    /** Scored, paid, rolled back. The war is history. */
    RESOLVED,

    /** Declined, or cancelled by an admin. Wagers are returned per SPEC 11.9. */
    CANCELLED,

    /**
     * SPEC 11.8.5: the rollback could not complete.
     *
     * <p>Terminal, and deliberately not recoverable from inside the plugin: "It must never
     * silently give up and reopen a griefed city."
     */
    ROLLBACK_FAILED;

    public String key() {
        return name();
    }

    /** Whether the two cities are bound together right now, SPEC 11.11. */
    public boolean isEngaged() {
        return this == DECLARED || this == PREP || this == ACTIVE;
    }

    /** Whether grief is permitted. Exactly one state, which is the whole safety property. */
    public boolean permitsGrief() {
        return this == ACTIVE;
    }

    /** Whether the zone is closed to entry, SPEC 11.8.2 step 1. */
    public boolean isZoneClosed() {
        return this == ROLLING_BACK || this == ROLLBACK_FAILED;
    }

    public boolean isFinished() {
        return this == RESOLVED || this == CANCELLED || this == ROLLBACK_FAILED;
    }

    public String messageKey() {
        return "war.state." + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<WarState> parse(String name) {
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
