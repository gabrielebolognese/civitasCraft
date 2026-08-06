package dev.civitas.core.war;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.core.city.City;
import dev.civitas.core.claim.Claim;

/**
 * SPEC 11.6's two timed objectives: capture points, and reaching the enemy City Hall.
 *
 * <p>Both are the same mechanic — stand in a chunk, unopposed, for a number of seconds — so
 * they share the occupancy plumbing rather than each ticking the world separately.
 *
 * <h2>Where the points are</h2>
 * SPEC 11.6: "three are auto-generated at war start, placed at the geometric extremes of the
 * defender's claim set (north-most, south-most, and the chunk furthest from the core)". Placing
 * them at the extremes rather than at the middle is what spreads a war out: a defender cannot
 * park everyone in one place and hold all three.
 *
 * <h2>What holding means</h2>
 * SPEC 11.6: "Holding one means having more of your members than the enemy's inside that
 * chunk." So it is a headcount, not a first-arrival claim, and it is contested the moment the
 * other side matches you. A tie holds for nobody and stops the clock, which is what makes a
 * point worth fighting over rather than worth sitting on.
 */
public final class CapturePoints {

    /** One objective: a chunk somebody has to stand in. */
    public record Point(String world, int chunkX, int chunkZ) {

        public int centreBlockX() {
            return (chunkX << 4) + 8;
        }

        public int centreBlockZ() {
            return (chunkZ << 4) + 8;
        }
    }

    /** How many of a set of cities' members are standing in a chunk. Injected, so testable. */
    @FunctionalInterface
    public interface Occupancy {

        int countIn(String world, int chunkX, int chunkZ, Set<Integer> cities);
    }

    /** Who is holding an objective and since when. */
    private record Hold(Boolean attackerSide, long since) { }

    private final WarScoring scoring;

    /** War id to its points. Generated once at war start, like the zone. */
    private final Map<Integer, List<Point>> points = new ConcurrentHashMap<>();

    /** War id and point index to the current hold. */
    private final Map<String, Hold> holds = new ConcurrentHashMap<>();

    /** War id and city id to when that city's City Hall stand began. */
    private final Map<String, Hold> cityHallStands = new ConcurrentHashMap<>();

    public CapturePoints(WarScoring scoring) {
        this.scoring = Objects.requireNonNull(scoring, "scoring");
    }

    // ==================================================================================
    // Generating, SPEC 11.6
    // ==================================================================================

    /**
     * Places the points for a war.
     *
     * @param defenderClaims the defending city's land; SPEC 11.6 puts the points on the
     *                       defender's ground, which is what makes the attacker travel
     * @param wanted         how many to place, three by SPEC 11.6
     * @param coreChunkX     the defender's core, for the "furthest from the core" point
     */
    public List<Point> generate(War war, Collection<Claim> defenderClaims, int wanted,
                                int coreChunkX, int coreChunkZ) {
        List<Claim> claims = List.copyOf(defenderClaims);
        if (claims.isEmpty()) {
            points.put(war.id(), List.of());
            return List.of();
        }

        // A LinkedHashSet because the three extremes can coincide on a small city, and SPEC
        // asks for three distinct places rather than the same chunk named three ways.
        Set<Point> chosen = new LinkedHashSet<>();

        chosen.add(pointOf(extremeBy(claims, claim -> claim.chunkZ(), true)));
        chosen.add(pointOf(extremeBy(claims, claim -> claim.chunkZ(), false)));
        chosen.add(pointOf(furthestFromCore(claims, coreChunkX, coreChunkZ)));

        // If the city is too small for three distinct extremes, fill from its other claims
        // rather than returning fewer than asked for without trying.
        for (Claim claim : claims) {
            if (chosen.size() >= wanted) {
                break;
            }
            chosen.add(pointOf(claim));
        }

        List<Point> placed = new ArrayList<>(chosen).subList(0, Math.min(wanted, chosen.size()));
        points.put(war.id(), List.copyOf(placed));
        return List.copyOf(placed);
    }

    private static Point pointOf(Claim claim) {
        return new Point(claim.world(), claim.chunkX(), claim.chunkZ());
    }

    private static Claim extremeBy(List<Claim> claims,
                                   java.util.function.ToIntFunction<Claim> axis, boolean lowest) {
        Claim best = claims.get(0);
        for (Claim claim : claims) {
            int value = axis.applyAsInt(claim);
            int bestValue = axis.applyAsInt(best);
            if (lowest ? value < bestValue : value > bestValue) {
                best = claim;
            }
        }
        return best;
    }

    /** Chebyshev distance, the same measure SPEC 6.2 uses for claim pricing. */
    private static Claim furthestFromCore(List<Claim> claims, int coreX, int coreZ) {
        Claim best = claims.get(0);
        int bestDistance = -1;
        for (Claim claim : claims) {
            int distance = Math.max(Math.abs(claim.chunkX() - coreX),
                    Math.abs(claim.chunkZ() - coreZ));
            if (distance > bestDistance) {
                bestDistance = distance;
                best = claim;
            }
        }
        return best;
    }

    public List<Point> pointsOf(War war) {
        return points.getOrDefault(war.id(), List.of());
    }

    // ==================================================================================
    // Ticking
    // ==================================================================================

    /** What one tick awarded, for the announcement. */
    public record Award(Point point, boolean attackerSide, int points) { }

    /**
     * Advances every point of a war by one tick.
     *
     * @param now milliseconds; the hold is measured in wall time rather than ticks so a
     *            laggy server does not make a point cheaper to hold
     * @return the awards made this tick, usually empty
     */
    public List<Award> tick(War war, Occupancy occupancy, long now) {
        List<Award> awarded = new ArrayList<>();
        Set<Integer> attackers = war.side(true);
        Set<Integer> defenders = war.side(false);
        long holdMillis = scoring.captureHoldSeconds() * 1000L;

        List<Point> all = pointsOf(war);
        for (int index = 0; index < all.size(); index++) {
            Point point = all.get(index);
            int attackerCount = occupancy.countIn(point.world(), point.chunkX(), point.chunkZ(),
                    attackers);
            int defenderCount = occupancy.countIn(point.world(), point.chunkX(), point.chunkZ(),
                    defenders);

            Boolean holder = holderOf(attackerCount, defenderCount);
            String key = war.id() + ":" + index;
            Hold current = holds.get(key);

            if (holder == null) {
                // Contested, or empty. The clock stops and starts again from zero, which is
                // what "60 continuous seconds" means.
                holds.remove(key);
                continue;
            }
            if (current == null || !holder.equals(current.attackerSide())) {
                holds.put(key, new Hold(holder, now));
                continue;
            }
            if (now - current.since() >= holdMillis) {
                awarded.add(new Award(point, holder, scoring.awardCapture(war, holder)));
                // Restart rather than stop: SPEC 11.6 awards per 60 seconds held, so a side
                // that keeps a point keeps earning from it.
                holds.put(key, new Hold(holder, now));
            }
        }
        return awarded;
    }

    /** Whichever side has more members present, or null when tied or empty. */
    private static Boolean holderOf(int attackerCount, int defenderCount) {
        if (attackerCount == defenderCount) {
            return null;
        }
        return attackerCount > defenderCount;
    }

    // ==================================================================================
    // The City Hall stand, SPEC 11.6
    // ==================================================================================

    /**
     * Tracks a city standing in the enemy City Hall chunk.
     *
     * <p>SPEC 11.6: "Reach the enemy City Hall chunk and stand there 30s: +100, once per war
     * per city." The once-per-war part lives in {@link War#claimCityHallReach}, so a side that
     * loses the chunk and takes it again does not earn it twice.
     *
     * @param present whether any member of {@code cityId} is in the enemy City Hall chunk now
     * @return the points awarded, or zero
     */
    public int tickCityHallStand(War war, int cityId, boolean attackerSide, boolean present,
                                 long now) {
        String key = war.id() + ":" + cityId;
        if (!present) {
            cityHallStands.remove(key);
            return 0;
        }

        Hold stand = cityHallStands.get(key);
        if (stand == null) {
            cityHallStands.put(key, new Hold(attackerSide, now));
            return 0;
        }
        if (now - stand.since() < scoring.cityHallReachSeconds() * 1000L) {
            return 0;
        }
        cityHallStands.remove(key);
        return scoring.awardCityHallReach(war, cityId, attackerSide);
    }

    /** Drops a finished war's state. */
    public void forget(int warId) {
        points.remove(warId);
        holds.keySet().removeIf(key -> key.startsWith(warId + ":"));
        cityHallStands.keySet().removeIf(key -> key.startsWith(warId + ":"));
    }

    /** The defending city's core, for {@link #generate}. */
    public static int[] coreOf(City city) {
        return new int[] {city.coreChunkX(), city.coreChunkZ()};
    }
}
