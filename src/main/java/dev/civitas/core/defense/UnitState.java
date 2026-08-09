package dev.civitas.core.defense;

/**
 * The four states a defense unit is in, SPEC 26.1.
 *
 * <p>Exactly one at a time, and the progression is one-way under normal play: a unit comes up
 * PASSIVE, may be provoked to ALERTED and falls back, and is only ever HOSTILE while a war is
 * running.
 *
 * <h2>Why PASSIVE is the default and not something milder</h2>
 *
 * <p>SPEC 25.2's Rule 2 makes this a design constraint rather than a preference: "peacetime is
 * safe". SPEC 13.4 requires players to travel to other cities to view and vote on contest
 * entries, so a defense system that attacks visitors makes contest voting impossible and kills
 * build tourism. On a building-focused server that is fatal, which is why a unit that can see
 * a stranger standing in front of it does nothing at all.
 */
public enum UnitState {

    /**
     * Not materialised. No player nearby, and regenerating.
     *
     * <p>A unit in this state has no entity, so it cannot target anything — but the state is
     * still asked about, because SPEC 30.1's table cancels on it explicitly rather than relying
     * on the absence of an entity to do the cancelling.
     */
    DORMANT,

    /** Materialised and visible, ignoring players entirely. Attacks hostile mobs in the claim. */
    PASSIVE,

    /** Provoked by SPEC 26.2's trespass threshold. Targets one named player, for a while. */
    ALERTED,

    /** War only. Targets members of enemy cities on sight. */
    HOSTILE;

    /** Whether a unit in this state exists as an entity. */
    public boolean materialized() {
        return this != DORMANT;
    }

    /** Whether this state permits attacking a player at all, before any other rule. */
    public boolean canTargetPlayers() {
        return this == ALERTED || this == HOSTILE;
    }
}
