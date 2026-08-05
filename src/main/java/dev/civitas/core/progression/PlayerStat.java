package dev.civitas.core.progression;

import java.util.Locale;
import java.util.Optional;

/**
 * A lifetime counter kept for a player, SPEC 13.3.
 *
 * <p>Distinct from {@code QuestMetric}, which counts the same events for a different purpose.
 * A quest metric is a target a player is working toward and is reassigned daily; one of these
 * only ever goes up. The two are fed from the same listener but neither can reset the other,
 * which is the point: SPEC 13.3's Builder board ranks a career, not a morning.
 */
public enum PlayerStat {

    /** Blocks placed, SPEC 13.3 Builder. War-zone placements are excluded, see SPEC 13.3. */
    BLOCKS_PLACED,

    /** Crops harvested, SPEC 13.3 Farmer. */
    CROPS_HARVESTED;

    /** The value stored in {@code player_stats.stat}. */
    public String key() {
        return name();
    }

    /**
     * Reads a stored name back.
     *
     * @return empty if the name is not one this build knows, which is what a counter removed
     *         in a later version looks like from here. The row stays on disk and is ignored
     *         rather than failing the whole read.
     */
    public static Optional<PlayerStat> parse(String name) {
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
