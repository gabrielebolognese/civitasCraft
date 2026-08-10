package dev.civitas.core.combat;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPEC 33.6: what a player loses when they die.
 *
 * <p>One rule decides it, and it is narrower than it looks: <b>items are lost to another player
 * only inside a war</b>. Every other death — to a mob, to a defense unit, to lava, to a fall, to
 * another player in peacetime — either drops to the world as vanilla does or keeps everything.
 *
 * <p>Combined with SPEC 11.7's rule that hand-looted container items are never restored by the
 * rollback, war is the <b>only</b> mechanism in the entire plugin by which a player permanently
 * loses possessions to another player. Everything else the plugin takes is money, ranking or
 * reputation, which is what makes SPEC 1.2's promise — "destruction is never permanent" — true
 * everywhere else.
 *
 * <h2>Attribution, and why it needs a window</h2>
 *
 * <p>SPEC 33.6 counts a death by "an opposing participant's TNT, fire, lava, or crystal" as a war
 * kill. None of those name their author at the moment they kill: a player who dies to lava has a
 * {@code DamageCause.LAVA}, not an attacker. So a placement is remembered for
 * {@code pvp.attribution-window-seconds} and the death is attributed to whoever set it. Outside
 * that window it is an environmental death, which is the conservative direction — an unattributed
 * death keeps items rather than taking them.
 */
public final class DeathPolicy {

    /** What happens to a player's inventory and experience. */
    public enum Outcome {

        /** Nothing is lost. SPEC 33.6's peacetime player kill. */
        KEEP,

        /** Vanilla: items and experience drop where they fell. */
        DROP
    }

    /** Who or what killed a player, as far as this rule cares. */
    public enum Cause {

        /** Another player, directly or through something they placed. */
        PLAYER,

        /** A mob, including a defense unit. SPEC 33.9 case 127. */
        MOB,

        /** Lava, a fall, the void, drowning, suffocation, starvation. */
        ENVIRONMENT
    }

    /** One remembered placement, for SPEC 33.6's attribution window. */
    private record Placement(UUID placer, long at) {
    }

    private final Map<String, Placement> placements = new ConcurrentHashMap<>();

    private final long attributionMillis;
    private final boolean keepOnPeacetimePvp;
    private final boolean keepOnWarPvp;
    private final boolean keepOnEnvironment;
    private final boolean keepOnMob;

    public DeathPolicy(long attributionMillis, boolean keepOnPeacetimePvp, boolean keepOnWarPvp,
                       boolean keepOnEnvironment, boolean keepOnMob) {
        this.attributionMillis = Math.max(0, attributionMillis);
        this.keepOnPeacetimePvp = keepOnPeacetimePvp;
        this.keepOnWarPvp = keepOnWarPvp;
        this.keepOnEnvironment = keepOnEnvironment;
        this.keepOnMob = keepOnMob;
    }

    /**
     * What this death costs.
     *
     * @param cause      who or what killed them
     * @param opposingWarParticipants whether killer and victim are on opposing sides of a war
     *                                that is ACTIVE right now
     */
    public Outcome decide(Cause cause, boolean opposingWarParticipants) {
        Objects.requireNonNull(cause, "cause");
        return switch (cause) {
            case PLAYER -> opposingWarParticipants
                    ? (keepOnWarPvp ? Outcome.KEEP : Outcome.DROP)
                    : (keepOnPeacetimePvp ? Outcome.KEEP : Outcome.DROP);
            // SPEC 33.9 case 127: "Player dies to a defense unit during war: vanilla drop.
            // Defense units are mobs, not war participants." A garrison kill is not a war kill,
            // however inconvenient that is for the city that paid for the garrison.
            case MOB -> keepOnMob ? Outcome.KEEP : Outcome.DROP;
            case ENVIRONMENT -> keepOnEnvironment ? Outcome.KEEP : Outcome.DROP;
        };
    }

    // ==================================================================================
    // Attribution, SPEC 33.6
    // ==================================================================================

    /** Remembers who placed something that can kill later: TNT, fire, lava, a crystal. */
    public void placed(String key, UUID placer, long now) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(placer, "placer");
        placements.put(key, new Placement(placer, now));
    }

    /**
     * Who is answerable for a death here, if anyone still is.
     *
     * @return the placer, or null once the window has passed
     */
    public UUID attributedTo(String key, long now) {
        Placement placement = placements.get(key);
        if (placement == null) {
            return null;
        }
        if (now - placement.at() >= attributionMillis) {
            placements.remove(key);
            return null;
        }
        return placement.placer();
    }

    /** Drops placements older than the window, so the map does not grow without bound. */
    public int sweep(long now) {
        int before = placements.size();
        placements.entrySet().removeIf(entry -> now - entry.getValue().at() >= attributionMillis);
        return before - placements.size();
    }

    public void forget(String key) {
        placements.remove(key);
    }

    public int trackedPlacements() {
        return placements.size();
    }

    public long attributionMillis() {
        return attributionMillis;
    }
}
