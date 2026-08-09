package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import dev.civitas.core.defense.DefenseCapacity.Placed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 25.5's points budget, asserted against the specification's own worked garrisons.
 *
 * <p>SPEC 25.5 publishes an example table, and three of its rows are arithmetically exact rather
 * than illustrative: five City Guards fill 100 exactly, three Colossi fit 150, and five Colossi
 * fill 225 exactly. Those are the rows worth testing, because they pin the comparison operator —
 * a budget that read {@code <} instead of {@code <=} would refuse two of the three garrisons SPEC
 * gives as examples of what fits. Its fourth row, "a mixed garrison of 12 units", is satisfied by
 * many combinations and is not checkable, so it is left alone.
 *
 * <p>No server: {@link DefenseCapacity} takes a unit as an id and a price, so the arithmetic can
 * be asserted without MockBukkit standing in the way.
 */
class DefenseCapacityTest {

    /** SPEC 25.5's price list. */
    private static final int SENTRY = 8;
    private static final int KEEPER = 10;
    private static final int WARHOUND = 12;
    private static final int ARCHER = 18;
    private static final int GUARD = 20;
    private static final int COLOSSUS = 45;
    private static final int WARDEN = 0;

    /** The shipped rule: 100 base, 25 a level, five levels. */
    private final DefenseCapacity rule = new DefenseCapacity(100, 25, 5);

    private static List<Placed> garrison(int... points) {
        List<Placed> units = new java.util.ArrayList<>();
        for (int index = 0; index < points.length; index++) {
            units.add(new Placed(index + 1, points[index]));
        }
        return units;
    }

    // ==================================================================================
    // The formula
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 25.5's formula")
    class Formula {

        @Test
        @DisplayName("100 at Fortification 0, rising by 25 a level to 225")
        void hundredToTwoTwentyFive() {
            assertEquals(100, rule.capacityAt(0));
            assertEquals(125, rule.capacityAt(1));
            assertEquals(150, rule.capacityAt(2));
            assertEquals(175, rule.capacityAt(3));
            assertEquals(200, rule.capacityAt(4));
            assertEquals(225, rule.capacityAt(5));
        }

        @Test
        @DisplayName("SPEC 25.5 writes the range as \"(0 to 5)\", and nothing else clamps it")
        void levelsAreClamped() {
            // A row written straight into city_upgrades would otherwise buy capacity SPEC does
            // not offer: UpgradeService stops a purchase at five, but nothing stops a database.
            assertEquals(225, rule.capacityAt(6));
            assertEquals(225, rule.capacityAt(99));
            assertEquals(100, rule.capacityAt(-1));
        }
    }

    // ==================================================================================
    // SPEC 25.5's example garrisons
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 25.5's example garrisons")
    class Examples {

        @Test
        @DisplayName("Fortification 0, capacity 100: five City Guards, or two Colossi and a Sentry")
        void atLevelZero() {
            assertEquals(100, DefenseCapacity.spent(
                    garrison(GUARD, GUARD, GUARD, GUARD, GUARD)));
            assertTrue(DefenseCapacity.fits(80, GUARD, 100),
                    "the fifth guard fills it exactly, so the test is <= and never <");
            assertFalse(DefenseCapacity.fits(100, GUARD, 100), "and the sixth does not fit");

            assertEquals(98, DefenseCapacity.spent(garrison(COLOSSUS, COLOSSUS, SENTRY)));
            assertTrue(DefenseCapacity.fits(90, SENTRY, 100));
        }

        @Test
        @DisplayName("Fortification 2, capacity 150: seven City Guards, or three Colossi")
        void atLevelTwo() {
            int capacity = rule.capacityAt(2);
            assertEquals(150, capacity);

            assertEquals(140, DefenseCapacity.spent(
                    garrison(GUARD, GUARD, GUARD, GUARD, GUARD, GUARD, GUARD)));
            assertFalse(DefenseCapacity.fits(140, GUARD, capacity), "an eighth would be 160");
            assertEquals(135, DefenseCapacity.spent(garrison(COLOSSUS, COLOSSUS, COLOSSUS)));
            assertFalse(DefenseCapacity.fits(135, COLOSSUS, capacity), "a fourth would be 180");
        }

        @Test
        @DisplayName("Fortification 5, capacity 225: eleven City Guards, or exactly five Colossi")
        void atLevelFive() {
            int capacity = rule.capacityAt(5);

            assertEquals(220, DefenseCapacity.spent(garrison(
                    GUARD, GUARD, GUARD, GUARD, GUARD, GUARD, GUARD, GUARD, GUARD, GUARD, GUARD)));
            assertFalse(DefenseCapacity.fits(220, GUARD, capacity), "a twelfth would be 240");

            assertEquals(225, DefenseCapacity.spent(
                    garrison(COLOSSUS, COLOSSUS, COLOSSUS, COLOSSUS, COLOSSUS)));
            assertTrue(DefenseCapacity.fits(180, COLOSSUS, capacity),
                    "five Colossi fill it exactly, which is the second row that pins <=");
        }

        @Test
        @DisplayName("SPEC 25.5: \"A count permits fifteen Colossi. A points budget does not.\"")
        void aCountPermittedFifteenColossi() {
            // The sentence this whole milestone exists for. Part I 12.4's cap was five units
            // plus two a level, which at Fortification 5 is fifteen -- of anything at all.
            int maxed = rule.capacityAt(5);
            assertEquals(675, DefenseCapacity.spent(garrison(
                    COLOSSUS, COLOSSUS, COLOSSUS, COLOSSUS, COLOSSUS,
                    COLOSSUS, COLOSSUS, COLOSSUS, COLOSSUS, COLOSSUS,
                    COLOSSUS, COLOSSUS, COLOSSUS, COLOSSUS, COLOSSUS)));
            assertTrue(675 > maxed * 2, "three times the budget of a fully fortified city");

            // Fifteen of the cheapest unit is a different matter, and that is the design: a
            // count could not tell fifteen Colossi from fifteen Frost Sentries.
            assertEquals(120, DefenseCapacity.spent(garrison(
                    SENTRY, SENTRY, SENTRY, SENTRY, SENTRY, SENTRY, SENTRY, SENTRY,
                    SENTRY, SENTRY, SENTRY, SENTRY, SENTRY, SENTRY, SENTRY)));
            assertTrue(120 < maxed, "and a fortified city may field all fifteen of those");
        }

        @Test
        @DisplayName("a mixed garrison prices as the sum of its parts")
        void mixed() {
            assertEquals(68, DefenseCapacity.spent(
                    garrison(SENTRY, KEEPER, WARHOUND, ARCHER, GUARD)),
                    "8 + 10 + 12 + 18 + 20");
            assertEquals(113, DefenseCapacity.spent(
                    garrison(SENTRY, KEEPER, WARHOUND, ARCHER, GUARD, COLOSSUS)),
                    "one of each of SPEC 27's six is 113, so a fortified city can field them all");
        }
    }

    // ==================================================================================
    // SPEC 30.2 case 101
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 30.2 case 101, newest first")
    class Suspension {

        @Test
        @DisplayName("nothing is suspended while a garrison is inside its budget")
        void withinBudgetIsUntouched() {
            assertTrue(DefenseCapacity.suspendToFit(garrison(GUARD, GUARD, GUARD), 100).isEmpty());
            assertTrue(DefenseCapacity.suspendToFit(
                    garrison(GUARD, GUARD, GUARD, GUARD, GUARD), 100).isEmpty(),
                    "exactly at capacity is inside it");
        }

        @Test
        @DisplayName("the newest go first, and only as many as it takes")
        void newestFirst() {
            // Five guards at capacity 100, then the operator drops the base to 60: two must go,
            // and they are the last two placed.
            List<Placed> five = garrison(GUARD, GUARD, GUARD, GUARD, GUARD);

            assertEquals(List.of(5, 4), DefenseCapacity.suspendToFit(five, 60));
            assertEquals(List.of(5), DefenseCapacity.suspendToFit(five, 80),
                    "one over is one unit, not the whole garrison");
            assertEquals(List.of(5, 4, 3, 2, 1), DefenseCapacity.suspendToFit(five, 0),
                    "and a capacity of nothing takes everything, still newest first");
        }

        @Test
        @DisplayName("SPEC 25.5 excludes the Warden from the budget, so it is never stood down")
        void zeroPointUnitsAreNeverSuspended() {
            // Suspending one would cost a city its flagship and free nothing at all: the
            // overage would still be there on the next pass.
            List<Placed> withWarden = garrison(WARDEN, GUARD, GUARD, WARDEN, GUARD);

            // 60 points standing against a capacity of 20, so two guards have to go. The newest
            // thing in the garrison is a Warden and it is stepped over.
            List<Integer> suspended = DefenseCapacity.suspendToFit(withWarden, 20);
            assertEquals(List.of(5, 3), suspended);
            assertFalse(suspended.contains(1), "the Warden is excluded from the budget");
            assertFalse(suspended.contains(4));
        }

        @Test
        @DisplayName("what comes back is what went down, oldest first")
        void restoreIsTheMirrorImage() {
            List<Placed> suspended = garrison(GUARD, GUARD, GUARD);

            assertEquals(List.of(1, 2), DefenseCapacity.restoreToFit(suspended, 60, 100),
                    "two of the three fit, and they are the two placed first");
            assertEquals(List.of(1, 2, 3), DefenseCapacity.restoreToFit(suspended, 0, 100));
            assertTrue(DefenseCapacity.restoreToFit(suspended, 100, 100).isEmpty());
        }

        @Test
        @DisplayName("a suspend and a restore round trip to the same garrison")
        void roundTrip() {
            // A city loses a Fortification level and buys it back. It should get its own
            // garrison returned, not a differently-shaped one.
            List<Placed> standing = garrison(COLOSSUS, GUARD, ARCHER, WARHOUND);
            List<Integer> down = DefenseCapacity.suspendToFit(standing, 70);
            assertEquals(List.of(4, 3), down);

            List<Placed> left = standing.stream()
                    .filter(unit -> !down.contains(unit.unitId())).toList();
            List<Placed> off = standing.stream()
                    .filter(unit -> down.contains(unit.unitId()))
                    .sorted(java.util.Comparator.comparingInt(Placed::unitId)).toList();

            assertEquals(List.of(3, 4), DefenseCapacity.restoreToFit(off,
                    DefenseCapacity.spent(left), 100), "the same two, in the order they were placed");
        }
    }

    @Test
    @DisplayName("a negative price is refused rather than handing capacity back")
    void negativePointsAreRefused() {
        // The one arithmetic that lets the budget be beaten rather than spent: a unit worth -10
        // would give a city ten points for fielding it.
        assertThrows(IllegalArgumentException.class, () -> new Placed(1, -10));
    }
}
