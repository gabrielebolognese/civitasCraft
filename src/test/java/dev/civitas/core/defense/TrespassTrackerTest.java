package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 26.2's violation counter.
 *
 * <p>The test that earns its keep is {@link Sliding#windowActuallySlides}. A running total and a
 * sliding window behave identically until somebody waits, so every other test here passes for
 * either implementation — and the difference between them is whether a player who bumps a
 * locked door twice on Monday gets attacked for touching one on Friday.
 */
class TrespassTrackerTest {

    private static final int CITY = 1;
    private static final int OTHER_CITY = 2;
    private static final long WINDOW = 30_000L;

    private static final UUID TRESPASSER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPANION = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final TrespassTracker tracker = new TrespassTracker(3, WINDOW);

    @Nested
    @DisplayName("the sliding window, SPEC 26.2")
    class Sliding {

        @Test
        @DisplayName("three inside the window trips it, and the third is the one that says so")
        void threeTrips() {
            assertFalse(tracker.record(CITY, TRESPASSER, 1_000L));
            assertFalse(tracker.record(CITY, TRESPASSER, 2_000L));
            assertTrue(tracker.record(CITY, TRESPASSER, 3_000L));
        }

        @Test
        @DisplayName("SPEC 26.2: violations decay, so the window really slides")
        void windowActuallySlides() {
            // The one test that separates a sliding window from a running total. Under a total,
            // the third strike here trips it and a player who touched a door twice last minute
            // is treated as a raider.
            assertFalse(tracker.record(CITY, TRESPASSER, 1_000L));
            assertFalse(tracker.record(CITY, TRESPASSER, 2_000L));

            long later = 1_000L + WINDOW + 1;
            assertFalse(tracker.record(CITY, TRESPASSER, later),
                    "the first two aged out, so this is strike one and not strike three");
        }

        @Test
        @DisplayName("a strike exactly on the boundary has expired")
        void boundaryExpires() {
            tracker.record(CITY, TRESPASSER, 0L);
            assertEquals(0, tracker.count(CITY, TRESPASSER, WINDOW));
        }

        @Test
        @DisplayName("tripping it clears the count, so a burst warns once and not every swing")
        void trippingResets() {
            // SPEC 26.2's response has phases with their own timers. Re-triggering the warning
            // on every block break would mean a trespasser never reaching the alerted phase.
            tracker.record(CITY, TRESPASSER, 1_000L);
            tracker.record(CITY, TRESPASSER, 2_000L);
            assertTrue(tracker.record(CITY, TRESPASSER, 3_000L));

            assertEquals(0, tracker.count(CITY, TRESPASSER, 3_000L));
            assertFalse(tracker.record(CITY, TRESPASSER, 3_500L),
                    "the next swing starts a fresh burst rather than warning again");
        }

        @Test
        @DisplayName("the threshold is configurable, per the hard rule on hardcoded numbers")
        void configurable() {
            TrespassTracker strict = new TrespassTracker(1, WINDOW);
            assertTrue(strict.record(CITY, TRESPASSER, 1_000L), "one strike is enough here");
        }
    }

    @Nested
    @DisplayName("who and where it counts for")
    class Scope {

        @Test
        @DisplayName("SPEC 26.2: per player, so a companion's strikes are not yours")
        void perPlayer() {
            // "ALERTED is per player, never per group."
            tracker.record(CITY, TRESPASSER, 1_000L);
            tracker.record(CITY, TRESPASSER, 2_000L);

            assertFalse(tracker.record(CITY, COMPANION, 3_000L),
                    "the companion's first strike is their own");
            assertEquals(2, tracker.count(CITY, TRESPASSER, 3_000L));
        }

        @Test
        @DisplayName("per city, so one city's patience is not another's")
        void perCity() {
            tracker.record(CITY, TRESPASSER, 1_000L);
            tracker.record(CITY, TRESPASSER, 2_000L);

            assertFalse(tracker.record(OTHER_CITY, TRESPASSER, 3_000L),
                    "a neighbour should not inherit the grudge");
        }

        @Test
        @DisplayName("de-escalation forgets the strikes")
        void clearing() {
            tracker.record(CITY, TRESPASSER, 1_000L);
            tracker.record(CITY, TRESPASSER, 2_000L);
            tracker.clear(CITY, TRESPASSER);

            assertEquals(0, tracker.count(CITY, TRESPASSER, 2_500L));
        }

        @Test
        @DisplayName("a disbanded city forgets everyone")
        void forgetCity() {
            tracker.record(CITY, TRESPASSER, 1_000L);
            tracker.forgetCity(CITY);

            assertEquals(0, tracker.count(CITY, TRESPASSER, 1_500L));
        }

        @Test
        @DisplayName("and a player who logs out is forgotten everywhere")
        void forgetPlayer() {
            tracker.record(CITY, TRESPASSER, 1_000L);
            tracker.record(OTHER_CITY, TRESPASSER, 1_000L);
            tracker.forget(TRESPASSER);

            assertEquals(0, tracker.count(CITY, TRESPASSER, 1_500L));
            assertEquals(0, tracker.count(OTHER_CITY, TRESPASSER, 1_500L));
        }
    }
}
