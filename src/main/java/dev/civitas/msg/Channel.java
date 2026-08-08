package dev.civitas.msg;

/**
 * Where a message goes, SPEC 23.4.
 *
 * <p>SPEC 23.1: "Frequency-matched channel. Constant feedback goes to the action bar,
 * transactional feedback goes to chat, momentous feedback goes to a title, ongoing state goes to
 * a boss bar." Choosing the channel is most of what makes a plugin's messaging readable, and
 * getting it wrong is what makes chat unusable.
 */
public enum Channel {

    /**
     * Transactions, state changes, anything a player may want to scroll back to.
     *
     * <p>The default. Money figures here are always exact, never abbreviated: SPEC 23.7, "a
     * player reading a transaction wants the exact figure".
     */
    CHAT(false, false),

    /**
     * Transient status, repeated events, positional information.
     *
     * <p>SPEC 23.4: "Never for anything with a number the player needs to remember." Throttled
     * per player per message, because these fire on every blocked click.
     */
    ACTION_BAR(true, true),

    /**
     * War start, war end, city founded, contest results.
     *
     * <p>SPEC 23.4: "Maximum 4 per hour per player, <b>hard-limited in code</b>." A title cannot
     * be dismissed and covers the screen, so an unbounded one is not a message, it is an
     * interruption.
     */
    TITLE(false, false),

    /**
     * Ongoing timed state: a war countdown, an active event, rollback progress.
     *
     * <p>SPEC 23.4: "One at a time, priority ordered."
     */
    BOSS_BAR(true, false),

    /** Reinforcement only, never the sole carrier of information. SPEC 23.8. */
    SOUND(false, false);

    private final boolean abbreviatesNumbers;
    private final boolean throttled;

    Channel(boolean abbreviatesNumbers, boolean throttled) {
        this.abbreviatesNumbers = abbreviatesNumbers;
        this.throttled = throttled;
    }

    /**
     * Whether large numbers are abbreviated here.
     *
     * <p>SPEC 23.7 names exactly two channels: "Large numbers abbreviate above 1,000,000
     * ({@code 1.25M}) in action bars and boss bars only, never in chat."
     */
    public boolean abbreviatesNumbers() {
        return abbreviatesNumbers;
    }

    /** Whether repeats of the same message are suppressed for a cooldown. */
    public boolean throttled() {
        return throttled;
    }
}
