package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import dev.civitas.core.defense.Materialization.Decision;
import dev.civitas.core.defense.Materialization.Point;
import dev.civitas.core.defense.Materialization.UnitState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 25.4's materialisation rule, and SPEC 31's case 113 benchmark.
 *
 * <p>SPEC 31 makes the benchmark a gate rather than a nicety: "Benchmark case 113 before
 * proceeding." The reason is in SPEC 25.4's own opening — this is "an architectural requirement,
 * not an optimisation" — and a rule that quietly materialises everything would not fail any test
 * of behaviour. It would fail as tick rate, months later, on somebody's live server.
 */
class MaterializationTest {

    private static final double RADIUS = 48;
    private static final long DELAY = 30_000;

    private static final int BUDGET = 60;

    /** The fleet rule, with SPEC 31 case 113's ceiling. */
    private final Materialization rule = new Materialization(RADIUS, DELAY, BUDGET);

    /** One unit at a time, where the budget is not what is being tested. */
    private final Materialization unbudgeted = new Materialization(RADIUS, DELAY);

    private static Point at(double x, double z) {
        return new Point("world", x, 64, z);
    }

    // ==================================================================================
    // SPEC 31 case 113, the gate
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 31 case 113: 200 cities, 12 units each, 40 players")
    class Case113 {

        /**
         * Builds the case as SPEC 17.7 and 31 describe it: 2,400 units spread over a large map,
         * 40 players scattered through it.
         *
         * <p>Cities are placed on a grid 2,000 blocks apart, which is far wider than the 48-block
         * radius and far narrower than the unbounded world SPEC 32.3 ships — a deliberately
         * unfavourable arrangement, since packing cities closer is what makes the count rise.
         */
        private List<UnitState> twoHundredCities() {
            List<UnitState> units = new ArrayList<>();
            int id = 0;
            for (int city = 0; city < 200; city++) {
                double baseX = (city % 20) * 2000.0;
                double baseZ = (city / 20) * 2000.0;
                for (int unit = 0; unit < 12; unit++) {
                    // Twelve units inside one city, a few chunks apart.
                    units.add(new UnitState(id++,
                            at(baseX + (unit % 4) * 16, baseZ + (unit / 4) * 16),
                            false, 0L, false));
                }
            }
            return units;
        }

        @Test
        @DisplayName("the ceiling holds even with every player inside a full garrison")
        void everyPlayerInsideAGarrison() {
            List<UnitState> units = twoHundredCities();
            assertEquals(2400, units.size(), "the fixture should be the case SPEC describes");

            // Forty players, each standing on their own City Hall, which is the most hostile
            // arrangement the case allows: every single player is inside a full garrison.
            List<Point> players = new ArrayList<>();
            for (int city = 0; city < 40; city++) {
                players.add(at((city % 20) * 2000.0, (city / 20) * 2000.0));
            }

            int materialized = rule.countMaterializable(units, players);

            // Four hundred units are within 48 blocks of somebody here, so a radius alone
            // cannot deliver the published ceiling. The budget is what makes it binding, and
            // the nearest four hundred compete for sixty seats.
            assertTrue(materialized <= BUDGET,
                    "SPEC 31 case 113 caps this at 60; the rule materialised " + materialized);
            assertEquals(BUDGET, materialized,
                    "and with far more candidates than seats, every seat should be taken");
        }

        @Test
        @DisplayName("war-zone units are seated first and cannot be crowded out")
        void warZoneUnitsKeepTheirPlace() {
            // The failure this prevents: a defender arrives at their own besieged city to find
            // the garrison missing, because forty strangers were standing in other cities when
            // the budget was handed out.
            List<UnitState> units = new ArrayList<>(twoHundredCities());
            List<UnitState> besieged = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                besieged.add(new UnitState(90_000 + i, at(500_000, 500_000), false, 0L, true));
            }
            units.addAll(besieged);

            List<Point> players = new ArrayList<>();
            for (int city = 0; city < 40; city++) {
                players.add(at((city % 20) * 2000.0, (city / 20) * 2000.0));
            }

            Map<Integer, Decision> changes = rule.sweep(units, players, 1_000L);

            for (UnitState unit : besieged) {
                assertEquals(Decision.MATERIALIZE, changes.get(unit.id()),
                        "a besieged unit lost its place to a bystander");
            }
        }

        @Test
        @DisplayName("SPEC 31 case 113: 60 holds when players are where players actually are")
        void publishedCeilingUnderARealisticSpread() {
            List<UnitState> units = twoHundredCities();

            // Forty players spread across the settled map. SPEC 32.4 puts /rtp inside 15,000
            // blocks and SPEC 32.3 leaves the world unbounded, so players are overwhelmingly
            // not standing on top of one another's garrisons.
            Random random = new Random(113L);
            List<Point> players = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                players.add(at(random.nextInt(40_000), random.nextInt(20_000)));
            }

            int materialized = rule.countMaterializable(units, players);
            assertTrue(materialized <= 60,
                    "SPEC 31 case 113 caps this at 60; the rule materialised " + materialized);
        }

        @Test
        @DisplayName("scattered players see even fewer, because most stand near nothing")
        void scatteredPlayers() {
            List<UnitState> units = twoHundredCities();
            Random random = new Random(20260809L);

            List<Point> players = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                players.add(at(random.nextInt(40_000), random.nextInt(20_000)));
            }

            assertTrue(rule.countMaterializable(units, players) <= 60);
        }

        @Test
        @DisplayName("the sweep over 2,400 units is fast enough to run on a timer")
        void sweepIsCheap() {
            List<UnitState> units = twoHundredCities();
            List<Point> players = new ArrayList<>();
            for (int city = 0; city < 40; city++) {
                players.add(at((city % 20) * 2000.0, (city / 20) * 2000.0));
            }

            // Warm up, then measure. Not a strict timing assertion -- CI machines vary and a
            // flaky performance test is worse than none -- but a generous ceiling that would
            // catch an accidental O(units x players x something).
            for (int i = 0; i < 50; i++) {
                rule.sweep(units, players, 1_000L);
            }
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                rule.sweep(units, players, 1_000L);
            }
            long perSweepMicros = (System.nanoTime() - start) / 100 / 1_000;

            assertTrue(perSweepMicros < 20_000,
                    "a sweep took " + perSweepMicros + " microseconds; it runs on a timer");
        }
    }

    // ==================================================================================
    // The rule itself, SPEC 25.4
    // ==================================================================================

    @Nested
    @DisplayName("when a unit exists, SPEC 25.4")
    class Rule {

        @Test
        @DisplayName("a player inside the radius brings one up")
        void nearMaterializes() {
            assertEquals(Decision.MATERIALIZE,
                    unbudgeted.decide(false, true, 0L, false, 1_000L));
        }

        @Test
        @DisplayName("48 blocks is inside, 49 is not")
        void radiusBoundary() {
            assertTrue(unbudgeted.anyoneNear(at(0, 0), List.of(at(48, 0))));
            assertFalse(unbudgeted.anyoneNear(at(0, 0), List.of(at(49, 0))));
        }

        @Test
        @DisplayName("a player in another world is not nearby, whatever the coordinates say")
        void otherWorldIsNotNear() {
            assertFalse(unbudgeted.anyoneNear(new Point("world", 0, 64, 0),
                    List.of(new Point("resource", 0, 64, 0))));
        }

        @Test
        @DisplayName("SPEC 25.4: leaving does not despawn it at once")
        void delayBeforeDematerializing() {
            long left = 1_000_000L;

            assertEquals(Decision.LEAVE,
                    unbudgeted.decide(true, false, left, false, left + DELAY - 1),
                    "still inside the grace period");
            assertEquals(Decision.DEMATERIALIZE,
                    unbudgeted.decide(true, false, left, false, left + DELAY));
        }

        @Test
        @DisplayName("walking past a city costs one materialisation, not twenty")
        void walkingPastDoesNotThrash() {
            // The reason the delay exists. A player crossing the 48-block boundary repeatedly
            // would otherwise spawn and despawn a guard several times a second, and each cycle
            // is an entity construction plus a database write.
            long now = 5_000_000L;
            assertEquals(Decision.MATERIALIZE, unbudgeted.decide(false, true, 0L, false, now));
            assertEquals(Decision.LEAVE, unbudgeted.decide(true, false, now, false, now + 500));
            assertEquals(Decision.LEAVE, unbudgeted.decide(true, true, now + 1000, false, now + 1000));
            assertEquals(Decision.LEAVE, unbudgeted.decide(true, false, now + 1000, false, now + 2000));
        }

        @Test
        @DisplayName("SPEC 25.4: a war keeps units standing with nobody watching")
        void warKeepsThemUp() {
            assertEquals(Decision.MATERIALIZE,
                    unbudgeted.decide(false, false, 0L, true, 1_000L),
                    "a defender arriving should not find their garrison spawning in around them");
            assertEquals(Decision.LEAVE,
                    unbudgeted.decide(true, false, 0L, true, 999_999_999L),
                    "and it never times out while the war runs");
        }

        @Test
        @DisplayName("the sweep reports only what changes")
        void sweepIsSparse() {
            List<UnitState> units = List.of(
                    new UnitState(1, at(0, 0), false, 0L, false),      // near -> materialize
                    new UnitState(2, at(5000, 0), false, 0L, false),   // far, already down
                    new UnitState(3, at(5000, 100), true, 0L, false)); // far, up -> dematerialize

            Map<Integer, Decision> changes = unbudgeted.sweep(units, List.of(at(10, 10)),
                    DELAY + 1);

            assertEquals(2, changes.size(), "unit 2 needs no work and should not appear");
            assertEquals(Decision.MATERIALIZE, changes.get(1));
            assertEquals(Decision.DEMATERIALIZE, changes.get(3));
        }
    }

    // ==================================================================================
    // Dormant regeneration, SPEC 25.4
    // ==================================================================================

    @Nested
    @DisplayName("healing while dormant, SPEC 25.4")
    class Regeneration {

        private static final long HOUR = 3_600_000L;

        /** A moment to have gone dormant at. Not zero, which the contract reads as never. */
        private static final long DORMANT_AT = 1_700_000_000_000L;

        @Test
        @DisplayName("10% of maximum per hour")
        void tenPercentAnHour() {
            assertEquals(50.0, Materialization.regenerated(40, 100, 10, DORMANT_AT,
                    DORMANT_AT + HOUR, false), 0.001);
            assertEquals(70.0, Materialization.regenerated(40, 100, 10, DORMANT_AT,
                    DORMANT_AT + 3 * HOUR, false), 0.001);
        }

        @Test
        @DisplayName("it stops at full rather than overshooting")
        void cappedAtFull() {
            assertEquals(100.0, Materialization.regenerated(40, 100, 10, DORMANT_AT,
                    DORMANT_AT + 100 * HOUR, false), 0.001);
        }

        @Test
        @DisplayName("SPEC 25.4: no healing during a war, so damage sticks")
        void noneDuringWar() {
            // A city that could heal its garrison by keeping players away from it would have
            // found the cheapest possible defence against a siege.
            assertEquals(40.0, Materialization.regenerated(40, 100, 10, DORMANT_AT,
                    DORMANT_AT + 50 * HOUR, true), 0.001);
        }

        @Test
        @DisplayName("a unit that has never been dormant does not heal")
        void neverDormant() {
            assertEquals(40.0, Materialization.regenerated(40, 100, 10, 0L,
                    DORMANT_AT + 50 * HOUR, false), 0.001);
        }

        @Test
        @DisplayName("switching regeneration off leaves health exactly where it was")
        void configurableOff() {
            assertEquals(40.0, Materialization.regenerated(40, 100, 0, DORMANT_AT,
                    DORMANT_AT + 50 * HOUR, false), 0.001);
        }
    }
}
