package dev.civitas.core.defense;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

/**
 * What kills a unit and what merely inconveniences it, SPEC 30.2 cases 107 and 112.
 *
 * <h2>The trap this class exists to hold open</h2>
 *
 * <p>Case 107 says a Frost Sentry's "water and melting damage cancelled explicitly, or every
 * sentry dies in the first storm". Case 112 says a unit that walks into lava is put back at its
 * post rather than deleted. Read together, the obvious implementation cancels environmental
 * damage — and SPEC 27.2's stated counterplay for the Frost Sentry is, in full: <b>"Melts in
 * lava or near fire."</b>
 *
 * <p>SPEC 25.2 Rule 3 makes that a shipping gate: "A unit without a written counterplay does not
 * ship." So the two rules are kept apart on the two axes SPEC itself uses:
 *
 * <ul>
 *   <li><b>Case 107 is about weather</b>, not about heat. Rain and water are cancelled; fire and
 *       lava are not, and a sentry left near either dies exactly as SPEC 27.2 promises.
 *   <li><b>Case 112 is about pathfinding.</b> Its premise is a unit that "pathfinds into lava or
 *       off a cliff", so it applies to units that walk. A Frost Sentry is static at zero speed
 *       and can have walked nowhere; a player who lured it into lava did that, and it dies.
 * </ul>
 *
 * <p>That is the only reading under which both SPEC sentences are true at once, and both halves
 * are asserted.
 */
public final class UnitSurvival {

    /**
     * What weather does to a snow golem, SPEC 30.2 case 107.
     *
     * <p>Rain and a warm biome both arrive as {@code MELTING}; standing in water is
     * {@code DROWNING}. Neither is anybody's counterplay — they are the world deleting a unit a
     * city paid 6,000 C for, on a timer, for being outdoors.
     */
    private static final Set<DamageCause> WEATHER = Set.of(
            DamageCause.MELTING, DamageCause.DROWNING);

    /**
     * Terrain, SPEC 30.2 case 112.
     *
     * <p>Deliberately narrow. Fire and lava are here because case 112 names lava outright, and
     * the guard that keeps SPEC 27.2's counterplay alive is {@link #canBeSaved}, not this set —
     * a unit that cannot walk is never offered the save whatever damaged it. {@code SUFFOCATION}
     * is included because a 1.8x Colossus does not fit where a normal golem walks, which SPEC
     * 27.7 treats as a feature and which must not therefore be a slow death sentence.
     */
    private static final Set<DamageCause> TERRAIN = Set.of(
            DamageCause.LAVA, DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.FALL,
            DamageCause.VOID, DamageCause.SUFFOCATION, DamageCause.CONTACT,
            DamageCause.HOT_FLOOR, DamageCause.DROWNING);

    /** The last time each unit was pulled out of the terrain, by unit id. */
    private final Map<Integer, Long> lastSave = new ConcurrentHashMap<>();

    /**
     * Whether this damage should be cancelled outright, SPEC 30.2 case 107.
     *
     * <p>By mob rather than by unit key, so an operator who repoints the Frost Sentry at another
     * snow golem still gets the rule, and one who repoints it at a zombie does not get a zombie
     * immune to water.
     */
    public static boolean isWeatherDeath(EntityType mob, DamageCause cause) {
        return mob == EntityType.SNOW_GOLEM && WEATHER.contains(cause);
    }

    /**
     * Whether SPEC 30.2 case 112's death save may apply to this unit at all.
     *
     * <p>Case 112's premise is a unit that <em>pathfinds</em> somewhere fatal. A unit at zero
     * speed pathfinds nowhere, so nothing it stands in got there by accident — and the two
     * units SPEC 27 gives no movement are the Frost Sentry, whose counterplay is that it melts,
     * and the Watchtower Keeper, which is invulnerable outside a war and has 40 HP inside one
     * precisely so that killing it first is the attacker's opening move.
     */
    public static boolean canBeSaved(DefenseUnitType type) {
        return type.canMove();
    }

    public static boolean isTerrain(DamageCause cause) {
        return TERRAIN.contains(cause);
    }

    /**
     * Whether this unit may be pulled back to its post now.
     *
     * <p>Once per hour, per SPEC 30.2 case 112, so terrain cannot delete a paid asset and a
     * player who keeps pushing one in still eventually wins. Records the save as it grants it,
     * which is why this both asks and answers.
     */
    public boolean claimSave(int unitId, long now, long cooldownMillis) {
        Long previous = lastSave.get(unitId);
        if (previous != null && now - previous < cooldownMillis) {
            return false;
        }
        lastSave.put(unitId, now);
        return true;
    }

    /** What a saved unit comes back at, SPEC 30.2 case 112's twenty percent. */
    public static double savedHealth(double maxHealth, double percent) {
        double clamped = Math.max(1, Math.min(100, percent));
        return Math.max(1.0, maxHealth * clamped / 100.0);
    }

    public void forget(int unitId) {
        lastSave.remove(unitId);
    }
}
