package dev.civitas.core.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.claim.Claim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 11.6's two timed objectives.
 *
 * <p>The capture rule is the one worth testing hard, because SPEC states it in a single
 * sentence that hides two decisions: a tie holds for nobody, and the sixty seconds must be
 * continuous. Both are what stop a point being something you claim once and forget.
 */
class CapturePointsTest {

    @TempDir
    Path directory;

    private static final long NOW = 1_000_000L;
    private static final int ATTACKER = 1;
    private static final int DEFENDER = 2;

    private CapturePoints points;
    private WarScoring scoring;
    private War war;

    /** A headcount the test drives directly, instead of a world full of players. */
    private final Map<String, Map<Integer, Integer>> occupants = new HashMap<>();

    @BeforeEach
    void setUp() {
        ConfigManager configs = new ConfigManager(PluginResources.ofClasspath(
                directory.resolve("plugin").toFile(), CityTestSupport.quietLogger()));
        configs.loadAll();
        scoring = new WarScoring(configs);
        points = new CapturePoints(scoring);
        war = new War(1, ATTACKER, DEFENDER, 0L, 0L, 0L, WarState.ACTIVE,
                new BigDecimal("50000.00"));
    }

    private CapturePoints.Occupancy occupancy() {
        return (world, chunkX, chunkZ, cities) -> {
            Map<Integer, Integer> here = occupants.getOrDefault(key(world, chunkX, chunkZ),
                    Map.of());
            int total = 0;
            for (int cityId : cities) {
                total += here.getOrDefault(cityId, 0);
            }
            return total;
        };
    }

    private static String key(String world, int chunkX, int chunkZ) {
        return world + ":" + chunkX + ":" + chunkZ;
    }

    private void put(CapturePoints.Point point, int cityId, int count) {
        occupants.computeIfAbsent(key(point.world(), point.chunkX(), point.chunkZ()),
                ignored -> new HashMap<>()).put(cityId, count);
    }

    private static Claim claimAt(int chunkX, int chunkZ) {
        return new Claim(0, DEFENDER, "world", chunkX, chunkZ, 0L, UUID.randomUUID(),
                BigDecimal.ZERO, dev.civitas.core.claim.ClaimType.NORMAL, null);
    }

    // ==================================================================================
    // Placing them, SPEC 11.6
    // ==================================================================================

    @Nested
    @DisplayName("placing the points")
    class Placing {

        @Test
        @DisplayName("three points are placed at the extremes of the defender's land")
        void atTheExtremes() {
            List<Claim> claims = new ArrayList<>();
            for (int z = -5; z <= 5; z++) {
                claims.add(claimAt(0, z));
            }
            claims.add(claimAt(20, 0));

            List<CapturePoints.Point> placed = points.generate(war, claims, 3, 0, 0);

            assertEquals(3, placed.size());
            assertTrue(placed.stream().anyMatch(point -> point.chunkZ() == -5),
                    "the north-most claim should hold a point");
            assertTrue(placed.stream().anyMatch(point -> point.chunkZ() == 5),
                    "and the south-most");
            assertTrue(placed.stream().anyMatch(point -> point.chunkX() == 20),
                    "and the one furthest from the core");
        }

        @Test
        @DisplayName("the points are distinct even when the extremes coincide")
        void noDuplicates() {
            // A city of two chunks has fewer than three distinct extremes.
            List<CapturePoints.Point> placed =
                    points.generate(war, List.of(claimAt(0, 0), claimAt(1, 0)), 3, 0, 0);

            assertEquals(placed.size(), Set.copyOf(placed).size(),
                    "the same chunk must not be named twice");
            assertTrue(placed.size() <= 2);
        }

        @Test
        @DisplayName("a city with one claim gets one point, not a crash")
        void singleClaim() {
            List<CapturePoints.Point> placed =
                    points.generate(war, List.of(claimAt(3, 3)), 3, 0, 0);

            assertEquals(1, placed.size());
            assertEquals(3, placed.get(0).chunkX());
        }

        @Test
        @DisplayName("a city with no claims gets none")
        void noClaims() {
            assertTrue(points.generate(war, List.of(), 3, 0, 0).isEmpty());
        }
    }

    // ==================================================================================
    // Holding them
    // ==================================================================================

    @Nested
    @DisplayName("holding a point")
    class Holding {

        private CapturePoints.Point point;

        @BeforeEach
        void placeOne() {
            point = points.generate(war, List.of(claimAt(0, 0)), 1, 0, 0).get(0);
        }

        @Test
        @DisplayName("an empty point scores for nobody")
        void emptyScoresNothing() {
            assertTrue(points.tick(war, occupancy(), NOW).isEmpty());
            assertTrue(points.tick(war, occupancy(),
                    NOW + TimeUnit.MINUTES.toMillis(5)).isEmpty());
            assertEquals(0, war.attackerScore());
        }

        @Test
        @DisplayName("holding for the full sixty seconds scores twenty-five")
        void heldLongEnough() {
            put(point, ATTACKER, 2);

            assertTrue(points.tick(war, occupancy(), NOW).isEmpty(), "the clock has just started");

            List<CapturePoints.Award> awards = points.tick(war, occupancy(),
                    NOW + TimeUnit.SECONDS.toMillis(60));

            assertEquals(1, awards.size());
            assertEquals(25, awards.get(0).points());
            assertTrue(awards.get(0).attackerSide());
            assertEquals(25, war.attackerScore());
        }

        @Test
        @DisplayName("holding for less than sixty seconds scores nothing")
        void notHeldLongEnough() {
            put(point, ATTACKER, 2);
            points.tick(war, occupancy(), NOW);

            assertTrue(points.tick(war, occupancy(),
                    NOW + TimeUnit.SECONDS.toMillis(59)).isEmpty());
            assertEquals(0, war.attackerScore());
        }

        @Test
        @DisplayName("an equal number on both sides holds for nobody")
        void tieIsContested() {
            // SPEC 11.6: "having more of your members than the enemy's". Equal is not more.
            put(point, ATTACKER, 3);
            put(point, DEFENDER, 3);

            points.tick(war, occupancy(), NOW);
            List<CapturePoints.Award> awards = points.tick(war, occupancy(),
                    NOW + TimeUnit.SECONDS.toMillis(120));

            assertTrue(awards.isEmpty());
            assertEquals(0, war.attackerScore());
            assertEquals(0, war.defenderScore());
        }

        @Test
        @DisplayName("the clock restarts when a point is contested")
        void contestResetsTheClock() {
            // The heart of "60 continuous seconds": showing up at second 59 costs the holder
            // the whole minute, which is what makes a point worth defending.
            put(point, ATTACKER, 2);
            points.tick(war, occupancy(), NOW);

            put(point, DEFENDER, 2);
            points.tick(war, occupancy(), NOW + TimeUnit.SECONDS.toMillis(59));

            occupants.clear();
            put(point, ATTACKER, 2);
            points.tick(war, occupancy(), NOW + TimeUnit.SECONDS.toMillis(60));

            assertTrue(points.tick(war, occupancy(),
                            NOW + TimeUnit.SECONDS.toMillis(119)).isEmpty(),
                    "the attackers must hold a fresh sixty seconds from when they retook it");
            assertEquals(0, war.attackerScore());
        }

        @Test
        @DisplayName("a point kept keeps earning")
        void keptPointEarnsAgain() {
            // SPEC 11.6 awards per sixty seconds held, so holding it for two minutes is worth
            // two awards rather than one.
            put(point, ATTACKER, 2);
            points.tick(war, occupancy(), NOW);
            points.tick(war, occupancy(), NOW + TimeUnit.SECONDS.toMillis(60));
            points.tick(war, occupancy(), NOW + TimeUnit.SECONDS.toMillis(120));

            assertEquals(50, war.attackerScore());
        }

        @Test
        @DisplayName("the defenders can hold their own points")
        void defendersScoreToo() {
            put(point, DEFENDER, 4);
            points.tick(war, occupancy(), NOW);

            points.tick(war, occupancy(), NOW + TimeUnit.SECONDS.toMillis(60));

            assertEquals(25, war.defenderScore());
            assertEquals(0, war.attackerScore());
        }
    }

    // ==================================================================================
    // The City Hall stand, SPEC 11.6
    // ==================================================================================

    @Nested
    @DisplayName("reaching the enemy City Hall")
    class CityHall {

        @Test
        @DisplayName("standing for thirty seconds scores a hundred")
        void awardedAfterThirtySeconds() {
            assertEquals(0, points.tickCityHallStand(war, ATTACKER, true, true, NOW));

            int awarded = points.tickCityHallStand(war, ATTACKER, true, true,
                    NOW + TimeUnit.SECONDS.toMillis(30));

            assertEquals(100, awarded);
            assertEquals(100, war.attackerScore());
        }

        @Test
        @DisplayName("leaving before thirty seconds resets it")
        void leavingResets() {
            points.tickCityHallStand(war, ATTACKER, true, true, NOW);
            points.tickCityHallStand(war, ATTACKER, true, false,
                    NOW + TimeUnit.SECONDS.toMillis(20));

            assertEquals(0, points.tickCityHallStand(war, ATTACKER, true, true,
                    NOW + TimeUnit.SECONDS.toMillis(31)));
            assertEquals(0, war.attackerScore());
        }

        @Test
        @DisplayName("it is awarded once per war however often the chunk is retaken")
        void oncePerWar() {
            points.tickCityHallStand(war, ATTACKER, true, true, NOW);
            points.tickCityHallStand(war, ATTACKER, true, true,
                    NOW + TimeUnit.SECONDS.toMillis(30));

            points.tickCityHallStand(war, ATTACKER, true, true,
                    NOW + TimeUnit.MINUTES.toMillis(5));
            int second = points.tickCityHallStand(war, ATTACKER, true, true,
                    NOW + TimeUnit.MINUTES.toMillis(6));

            assertEquals(0, second, "SPEC 11.6 awards it once per war per city");
            assertEquals(100, war.attackerScore());
        }

        @Test
        @DisplayName("each side has its own bonus to earn")
        void bothSidesCanEarnIt() {
            points.tickCityHallStand(war, ATTACKER, true, true, NOW);
            points.tickCityHallStand(war, ATTACKER, true, true,
                    NOW + TimeUnit.SECONDS.toMillis(30));

            points.tickCityHallStand(war, DEFENDER, false, true, NOW);
            points.tickCityHallStand(war, DEFENDER, false, true,
                    NOW + TimeUnit.SECONDS.toMillis(30));

            assertEquals(100, war.attackerScore());
            assertEquals(100, war.defenderScore());
        }
    }

    @Test
    @DisplayName("forgetting a war clears its objectives")
    void forgetClears() {
        points.generate(war, List.of(claimAt(0, 0)), 1, 0, 0);
        assertFalse(points.pointsOf(war).isEmpty());

        points.forget(war.id());

        assertTrue(points.pointsOf(war).isEmpty());
    }
}
