package dev.civitas.core.siege;

/**
 * Where a Siege Camp may stand, SPEC 29.5. Pure, so every branch has a test with no server.
 *
 * <p>SPEC 29.5 gives four constraints and they pull in different directions, which is the point:
 * a camp must be close enough to be a staging point, far enough that it is not inside the thing it
 * is besieging, on ground the attacker is entitled to use, and singular.
 */
public final class SiegePlacement {

    /** What the rule refused, or {@link #OK}. */
    public enum Verdict {
        OK,
        /** SPEC 29.4: siege exists only during a war that is being prepared or fought. */
        WRONG_PHASE,
        /** SPEC 29.5: "Attackers place". A defender under siege does not lay one of its own. */
        NOT_ATTACKING,
        /** SPEC 30.2 case 103: wilderness or the attacker's own claims, nowhere else. */
        FOREIGN_GROUND,
        /** Beyond {@code siege.max-camp-distance-chunks} of the defender. */
        TOO_FAR,
        /** SPEC 29.5: "One camp per attacking city." */
        ALREADY_PLACED,
        /** It was destroyed and the one rebuild SPEC allows has been spent. */
        REBUILD_SPENT
    }

    /**
     * Everything the rule needs about a proposed site.
     *
     * @param ownerOfChunk     the city owning the chunk, or {@code null} for wilderness
     * @param chunksToDefender Chebyshev distance in chunks to the nearest defending-side claim,
     *                         which is how every other distance in this plugin is measured
     * @param existing         the placer's camp in this war, or {@code null} if they have none
     */
    public record Site(
            boolean warEngaged,
            boolean attackerSide,
            int placerCityId,
            Integer ownerOfChunk,
            int chunksToDefender,
            SiegeCamp existing) {
    }

    private final int maxDistanceChunks;

    public SiegePlacement(int maxDistanceChunks) {
        this.maxDistanceChunks = maxDistanceChunks;
    }

    public Verdict judge(Site site) {
        if (!site.warEngaged()) {
            return Verdict.WRONG_PHASE;
        }
        if (!site.attackerSide()) {
            return Verdict.NOT_ATTACKING;
        }

        // SPEC 30.2 case 103. Wilderness is fine and the attacker's own land is fine; a third
        // city's claims are not, because a camp planted in somebody uninvolved makes them a
        // battlefield without their consent — the same narrowing SPEC 33.4 makes for PvP.
        Integer owner = site.ownerOfChunk();
        if (owner != null && owner != site.placerCityId()) {
            return Verdict.FOREIGN_GROUND;
        }

        if (site.chunksToDefender() > maxDistanceChunks) {
            return Verdict.TOO_FAR;
        }

        SiegeCamp existing = site.existing();
        if (existing != null) {
            if (existing.stands()) {
                return Verdict.ALREADY_PLACED;
            }
            if (existing.rebuilt()) {
                return Verdict.REBUILD_SPENT;
            }
        }
        return Verdict.OK;
    }

    /** Whether this placement reoccupies a destroyed camp, which is what costs half. */
    public boolean isRebuild(Site site) {
        return site.existing() != null && !site.existing().stands();
    }

    public int maxDistanceChunks() {
        return maxDistanceChunks;
    }
}
