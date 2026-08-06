package dev.civitas.core.war;

/**
 * Where a rollback has got to, for SPEC 9.4.5's {@code /ca war rollbackstatus} in M21.
 *
 * <p>{@link #FAILED} is the one that matters. SPEC 11.8.5 is unambiguous about what it means:
 * the zone stays closed and an admin has to resolve it by hand. "It must never silently give
 * up and reopen a griefed city."
 */
public enum RollbackStatus {

    /** Reading the log and putting blocks back. */
    RUNNING,

    /** Every entry applied, verified, and the war marked resolved. */
    COMPLETED,

    /**
     * The log could not be read or applied. The war zone stays closed until an admin
     * intervenes; this state is never cleared automatically.
     */
    FAILED;

    public boolean isFinished() {
        return this != RUNNING;
    }
}
