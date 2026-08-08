package dev.civitas.core.world;

/**
 * What a world is for, SPEC 32.2.
 *
 * <p>SPEC 32.2 lays out five worlds with three different jobs. This is that table as a type, so
 * the question "may anything be claimed here" has one answer in one place rather than a
 * {@code getStringList} in every caller that needs to know.
 *
 * <p>{@link #BLACKLISTED} and {@link #PLAIN} both refuse a claim and differ only in which
 * refusal the player reads: an operator who explicitly listed a world deserves to be told that
 * is why, rather than being told the world was merely never enabled.
 */
public enum WorldKind {

    /**
     * Cities, outposts, contests and wars. SPEC 32.2's main overworld.
     *
     * <p>Part I Open Decision 4 asked whether a city may hold territory in several worlds. SPEC
     * 32.2 answers it: a city and its outposts exist in the main world only.
     */
    CLAIMABLE,

    /**
     * The resource worlds, SPEC 32.5. City claims are blocked entirely; a personal Mining Claim
     * (SPEC 32.6) and a city Waystation (SPEC 39.10) are the only land ownership here, and both
     * belong to later milestones.
     */
    MINING,

    /**
     * Reachable, playable, and nothing may be claimed. The main Nether and End, and any world
     * the operator has not mentioned at all.
     *
     * <p>Part I Open Decision 1 asked whether the Nether and End are claimable. SPEC 32.2
     * answers no.
     */
    PLAIN,

    /** Named on the operator's blacklist. Refused, and told so in those terms. */
    BLACKLISTED;

    /** Whether a city may claim land here. */
    public boolean allowsCityClaims() {
        return this == CLAIMABLE;
    }

    /** Whether a personal mining claim or a city waystation may exist here. */
    public boolean allowsMiningClaims() {
        return this == MINING;
    }
}
