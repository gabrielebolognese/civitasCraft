package dev.civitas.core.defense;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which units should exist as entities right now, SPEC 25.4.
 *
 * <p>Pure. No Bukkit types cross this boundary: it takes positions in and returns decisions out,
 * so SPEC 31's case 113 benchmark — "no more than 60 materialized units server-wide at 40
 * players" — can be run over two hundred cities without a server, and so the rule that decides
 * whether 2,400 entities exist is testable rather than inferred from watching tick rate.
 *
 * <h2>Why a radius and not chunk load</h2>
 *
 * <p>The superseded M12 respawned units on {@code ChunkLoadEvent}, which is coarser than it
 * looks: a chunk stays loaded for a player standing 200 blocks away at view distance 16, and
 * spawn chunks never unload at all. SPEC 25.4 asks for 48 blocks, which is roughly the distance
 * at which a player could see a unit and therefore the distance at which one needs to exist.
 *
 * <h2>The delay is not politeness</h2>
 *
 * <p>Dematerialising the instant a player steps out of range would thrash: a player walking the
 * border of their own city would spawn and despawn a guard several times a second, and each
 * cycle is an entity construction plus a database write. SPEC 25.4's 30-second delay makes the
 * common case — walking past — cost one materialisation rather than twenty.
 *
 * <h2>The budget is global, because SPEC 31 case 113 says server-wide</h2>
 *
 * <p>Case 113 caps this at "no more than 60 materialized units server-wide at 40 players", and
 * a radius alone cannot deliver that: forty players each standing inside their own twelve-unit
 * garrison are near four hundred units, and no per-player rule makes forty players produce
 * fewer than forty times what is in range of them. So the cap is a **fleet budget**, and units
 * compete for it.
 *
 * <p>Two rules decide who wins. Units in an active war zone are seated first and never lose
 * their place, because SPEC 25.4 makes a war the second materialisation trigger in its own
 * right — a defender arriving to find their garrison missing because forty strangers were
 * standing in other cities would be the worst possible failure of this system. Everything else
 * is ordered by distance to the nearest player, so what materialises is what somebody is
 * closest to and therefore most likely to be looking at.
 */
public final class Materialization {

    /** A point, in the shape this class reasons about. */
    public record Point(String world, double x, double y, double z) {

        public Point {
            Objects.requireNonNull(world, "world");
        }

        /** Squared horizontal-and-vertical distance, or -1 when the worlds differ. */
        double distanceSquaredTo(Point other) {
            if (!world.equals(other.world)) {
                return -1;
            }
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** What the rule decided about one unit. */
    public enum Decision {

        /** A player is close enough, or the unit is in an active war zone. */
        MATERIALIZE,

        /** Nobody has been near for long enough. */
        DEMATERIALIZE,

        /** No change: near a materialised unit, or still inside the grace delay. */
        LEAVE
    }

    private final double radius;
    private final long delayMillis;
    private final int budget;

    public Materialization(double radiusBlocks, long dematerializeDelayMillis, int budget) {
        this.radius = Math.max(0, radiusBlocks);
        this.delayMillis = Math.max(0, dematerializeDelayMillis);
        this.budget = budget <= 0 ? Integer.MAX_VALUE : budget;
    }

    /** Without a budget, for the rule tests that are about one unit rather than the fleet. */
    public Materialization(double radiusBlocks, long dematerializeDelayMillis) {
        this(radiusBlocks, dematerializeDelayMillis, 0);
    }

    /**
     * Whether any of these players is close enough to keep a unit standing.
     *
     * <p>Squared distances throughout, so the hot path — this runs over every unit on a timer —
     * never takes a square root.
     */
    public boolean anyoneNear(Point unit, List<Point> players) {
        double limit = radius * radius;
        for (Point player : players) {
            double distance = player.distanceSquaredTo(unit);
            if (distance >= 0 && distance <= limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * The decision for one unit.
     *
     * @param materialized     whether it currently exists as an entity
     * @param lastSeenNearby   when a player was last within range, or 0 if never
     * @param inActiveWarZone  SPEC 25.4's second trigger: a war keeps units standing whether or
     *                         not anyone is watching, because a defender arriving at an empty
     *                         city would find its garrison spawning in around them
     */
    public Decision decide(boolean materialized, boolean anyoneNear, long lastSeenNearby,
                           boolean inActiveWarZone, long now) {
        if (anyoneNear || inActiveWarZone) {
            return materialized ? Decision.LEAVE : Decision.MATERIALIZE;
        }
        if (!materialized) {
            return Decision.LEAVE;
        }
        return now - lastSeenNearby >= delayMillis
                ? Decision.DEMATERIALIZE
                : Decision.LEAVE;
    }

    /**
     * Every decision for a whole server tick.
     *
     * <p>Grouped by world first, because the overwhelming majority of comparisons are between a
     * unit and a player who is not even in the same world, and comparing world names is cheaper
     * than comparing coordinates.
     *
     * @return one entry per unit whose state should change; units that stay as they are are
     *         omitted, so the caller does no work for the common case
     */
    public Map<Integer, Decision> sweep(List<UnitState> units, List<Point> players, long now) {
        Set<Integer> wanted = seated(units, players);

        Map<Integer, Decision> changes = new HashMap<>();
        for (UnitState unit : units) {
            boolean keep = wanted.contains(unit.id());
            Decision decision = decide(unit.materialized(), keep, unit.lastSeenNearby(),
                    unit.inActiveWarZone() && keep, now);
            if (decision != Decision.LEAVE) {
                changes.put(unit.id(), decision);
            }
        }
        return changes;
    }

    /**
     * Which units win a place in the budget.
     *
     * <p>War-zone units first, then the rest by how close the nearest player is. A unit already
     * standing is not favoured over one that is not: preferring incumbents would mean a player
     * walking up to their own garrison finds it will not appear because forty units elsewhere
     * got there first and never yield.
     */
    private Set<Integer> seated(List<UnitState> units, List<Point> players) {
        Map<String, List<Point>> byWorld = new HashMap<>();
        for (Point player : players) {
            byWorld.computeIfAbsent(player.world(), key -> new ArrayList<>()).add(player);
        }

        Set<Integer> seats = new java.util.HashSet<>();
        List<double[]> candidates = new ArrayList<>();
        double limit = radius * radius;

        for (UnitState unit : units) {
            if (unit.inActiveWarZone()) {
                seats.add(unit.id());
                continue;
            }
            double nearest = Double.MAX_VALUE;
            for (Point player : byWorld.getOrDefault(unit.at().world(), List.of())) {
                double distance = player.distanceSquaredTo(unit.at());
                if (distance >= 0 && distance < nearest) {
                    nearest = distance;
                }
            }
            if (nearest <= limit) {
                candidates.add(new double[] {nearest, unit.id()});
            }
        }

        candidates.sort((a, b) -> Double.compare(a[0], b[0]));
        for (double[] candidate : candidates) {
            if (seats.size() >= budget) {
                break;
            }
            seats.add((int) candidate[1]);
        }
        return seats;
    }

    /** What the sweep needs to know about one unit. */
    public record UnitState(int id, Point at, boolean materialized, long lastSeenNearby,
                            boolean inActiveWarZone) {

        public UnitState {
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * SPEC 25.4's dormant regeneration: 10% of maximum per hour, capped at full.
     *
     * <p>"This is disabled entirely during a war, so damage dealt in a war sticks." A city that
     * could heal its garrison by keeping players away from it would have found the cheapest
     * possible defence against a siege.
     *
     * @param dormantSince when it stopped being an entity, or 0 if it never was
     */
    public static double regenerated(double health, double maxHealth, double percentPerHour,
                                     long dormantSince, long now, boolean atWar) {
        if (atWar || dormantSince <= 0 || health >= maxHealth || percentPerHour <= 0) {
            return Math.min(health, maxHealth);
        }
        double hours = Math.max(0, now - dormantSince) / 3_600_000.0;
        double healed = health + maxHealth * (percentPerHour / 100.0) * hours;
        return Math.min(healed, maxHealth);
    }

    /** Units that should exist right now, for the SPEC 31 case 113 benchmark. */
    public int countMaterializable(List<UnitState> units, List<Point> players) {
        return seated(units, players).size();
    }

    /** The fleet ceiling, SPEC 31 case 113. */
    public int budget() {
        return budget == Integer.MAX_VALUE ? 0 : budget;
    }

    public double radius() {
        return radius;
    }

    public long dematerializeDelayMillis() {
        return delayMillis;
    }

    /** Guards a caller against passing a set where a list is wanted, without copying. */
    public static List<Point> pointsOf(Set<Point> points) {
        return new ArrayList<>(points);
    }
}
