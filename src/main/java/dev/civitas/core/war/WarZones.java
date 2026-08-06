package dev.civitas.core.war;

import java.util.List;
import java.util.Optional;

/**
 * Which war, if any, is logging changes at a given block.
 *
 * <h2>The seam M17 is built against</h2>
 * SPEC 19 puts the block logger before the war system on purpose: "milestones 17 and 18 build
 * and test the rollback engine <em>before</em> any war gameplay exists … If rollback is built
 * last, it will be tested under time pressure with players waiting, which is exactly how a
 * plugin ends up eating someone's castle."
 *
 * <p>So the logger needs an answer to "should this change be recorded, and against which war"
 * before there is any war to ask. This interface is that answer. Until M19 the only
 * implementation is {@link #none()}, which logs nothing, and the logger is driven directly by
 * its tests instead.
 *
 * <h2>Why it returns a list</h2>
 * SPEC 17.4 case 51: two wars can overlap geographically, and "a chunk in both zones logs to
 * both". A single-war answer would silently drop one war's record of a shared chunk, and that
 * war's rollback would then leave the other war's damage in place.
 */
@FunctionalInterface
public interface WarZones {

    /**
     * Every war currently logging changes at this position.
     *
     * @return the war ids, empty when the block is outside every war zone. Empty is the normal
     *         case: SPEC 11.4 confines the zone to the participants' claims plus a one-chunk
     *         perimeter, and everywhere else in the world is untouched by war.
     */
    List<Integer> warsCovering(String world, int x, int y, int z);

    /** Whether anything at all is being logged, so the listeners can leave early. */
    default boolean isAnyWarActive() {
        return false;
    }

    /** Convenience for the common single-war lookup. */
    default Optional<Integer> firstWarCovering(String world, int x, int y, int z) {
        List<Integer> wars = warsCovering(world, x, y, z);
        return wars.isEmpty() ? Optional.empty() : Optional.of(wars.get(0));
    }

    /**
     * No war anywhere, which is every server until M19.
     *
     * <p>{@link #isAnyWarActive()} answers false, so every listener returns on its first line
     * and the logger costs a peacetime server nothing measurable.
     */
    static WarZones none() {
        return new WarZones() {
            @Override
            public List<Integer> warsCovering(String world, int x, int y, int z) {
                return List.of();
            }

            @Override
            public boolean isAnyWarActive() {
                return false;
            }
        };
    }
}
