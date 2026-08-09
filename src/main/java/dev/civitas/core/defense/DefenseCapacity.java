package dev.civitas.core.defense;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SPEC 25.5's points budget. What a city may field, and what to take down when it may not.
 *
 * <h2>Why a budget rather than a count</h2>
 *
 * <p>SPEC 25.5 gives the reason in one line: "A count permits fifteen Colossi. A points budget
 * does not." Part I 12.4 capped defense at five units plus two per Fortification level, which
 * says nothing at all about what those units are — a maxed city could field fifteen of the most
 * expensive thing on the roster, which is the unbeatable garrison SPEC 25.2 Rule 1 forbids.
 * Pricing each unit in points makes the roster a composition decision instead of a shopping one.
 *
 * <h2>Pure on purpose</h2>
 *
 * <p>No Bukkit, no registry, no configuration. A unit arrives here as {@link Placed}, an id and a
 * price, so the arithmetic can be asserted against every checkable row of SPEC 25.5's own
 * example-garrison table without a server. {@link CapacityReconciler} owns the consequences.
 *
 * <h2>The comparison is inclusive, and SPEC says so</h2>
 *
 * <p>SPEC 25.5's table has five City Guards (100 points) fitting capacity 100 at Fortification 0,
 * and exactly five Colossi (225) fitting 225 at Fortification 5. Both are exact, so the test is
 * {@code spent + cost <= capacity} and never {@code <}.
 */
public final class DefenseCapacity {

    private final int base;
    private final int perFortificationLevel;
    private final int maxFortificationLevel;

    /**
     * @param maxFortificationLevel SPEC 25.5 writes the range as "(0 to 5)", and nothing else
     *                              clamps it: a row written straight into {@code city_upgrades}
     *                              would otherwise buy capacity SPEC does not offer
     */
    public DefenseCapacity(int base, int perFortificationLevel, int maxFortificationLevel) {
        this.base = base;
        this.perFortificationLevel = perFortificationLevel;
        this.maxFortificationLevel = maxFortificationLevel;
    }

    /** SPEC 25.5: {@code base + per-level * fortification}, so 100 at level 0 and 225 at 5. */
    public int capacityAt(int fortificationLevel) {
        int level = Math.max(0, Math.min(fortificationLevel, maxFortificationLevel));
        return base + perFortificationLevel * level;
    }

    /** One standing unit, as the budget sees it. */
    public record Placed(int unitId, int points) {

        public Placed {
            if (points < 0) {
                throw new IllegalArgumentException("points cannot be negative for " + unitId);
            }
        }
    }

    /** What a garrison costs. */
    public static int spent(List<Placed> standing) {
        int total = 0;
        for (Placed unit : standing) {
            total += unit.points();
        }
        return total;
    }

    /** Whether one more unit of this price fits, SPEC 25.5's table read inclusively. */
    public static boolean fits(int spent, int cost, int capacity) {
        return spent + cost <= capacity;
    }

    /**
     * SPEC 30.2 case 101: which units to suspend, newest first, until the rest are within budget.
     *
     * <p>"Units over budget are marked inactive (dematerialized, upkeep suspended) newest-first
     * until within budget. <b>Not deleted.</b>" Newest-first is what makes this fair: a city that
     * loses a Fortification level keeps the garrison it built first and loses what the level it
     * lost had paid for.
     *
     * <p>Zero-point units are never suspended, however far over budget a city is. SPEC 25.5
     * excludes the City Warden from the budget entirely, so taking one down would cost a city its
     * flagship and free nothing — the overage would still be there on the next pass.
     *
     * @param standing the city's standing units, oldest first
     * @return the ids to take down, in the order to take them
     */
    public static List<Integer> suspendToFit(List<Placed> standing, int capacity) {
        Objects.requireNonNull(standing, "standing");
        int over = spent(standing) - capacity;
        List<Integer> suspend = new ArrayList<>();
        for (int index = standing.size() - 1; index >= 0 && over > 0; index--) {
            Placed unit = standing.get(index);
            if (unit.points() <= 0) {
                continue;
            }
            suspend.add(unit.unitId());
            over -= unit.points();
        }
        return suspend;
    }

    /**
     * Which suspended units may stand up again, oldest first, while they fit.
     *
     * <p>The reverse of {@link #suspendToFit}, and deliberately the mirror image of it: units go
     * down newest-first, so bringing them back oldest-first restores exactly the set that was
     * standing before capacity fell. A city that loses a level and buys it back gets its own
     * garrison rather than a differently-shaped one.
     *
     * @param suspended the city's inactive units, oldest first
     * @param spent     what its standing units already cost
     */
    public static List<Integer> restoreToFit(List<Placed> suspended, int spent, int capacity) {
        Objects.requireNonNull(suspended, "suspended");
        List<Integer> restore = new ArrayList<>();
        int running = spent;
        for (Placed unit : suspended) {
            if (!fits(running, unit.points(), capacity)) {
                continue;
            }
            restore.add(unit.unitId());
            running += unit.points();
        }
        return restore;
    }
}
