package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import dev.civitas.core.defense.TrespassResponse.Phase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 26.2's three phases.
 *
 * <p>{@link Warning#takingTheWarningAndLeavingIsSafe} is the test this class exists for. SPEC
 * 26.2 gives the warning phase a purpose rather than a mechanic — "no player is ever killed
 * without being told, in plain language, that they are about to be" — and a warning a player
 * cannot act on is decoration. Everything else here is bookkeeping around that one guarantee.
 */
class TrespassResponseTest {

    private static final int CITY = 1;
    private static final int OTHER_CITY = 2;
    private static final long WARNING = 5_000L;
    private static final long ALERTED = 45_000L;

    private static final UUID TRESPASSER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPANION = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final TrespassResponse response = new TrespassResponse(WARNING, ALERTED);

    @Nested
    @DisplayName("the warning phase, SPEC 26.2 step 1")
    class Warning {

        @Test
        @DisplayName("crossing the threshold warns rather than attacking")
        void warnsFirst() {
            assertTrue(response.warn(CITY, TRESPASSER, 1_000L));

            assertEquals(Phase.WARNING, response.phaseOf(CITY, TRESPASSER, 1_000L));
        }

        @Test
        @DisplayName("SPEC 26.2: taking the warning and leaving is safe")
        void takingTheWarningAndLeavingIsSafe() {
            // The whole reason the warning phase exists rather than alerting on the third
            // violation. A player who is told to leave, and leaves, must not be attacked.
            response.warn(CITY, TRESPASSER, 1_000L);

            boolean alerted = response.promote(CITY, TRESPASSER, false, 1_000L + WARNING);

            assertFalse(alerted, "a player who left was alerted anyway");
            assertEquals(Phase.NONE, response.phaseOf(CITY, TRESPASSER, 1_000L + WARNING));
        }

        @Test
        @DisplayName("staying through the warning earns the alert")
        void stayingEarnsIt() {
            response.warn(CITY, TRESPASSER, 1_000L);

            assertTrue(response.promote(CITY, TRESPASSER, true, 1_000L + WARNING));
            assertEquals(Phase.ALERTED, response.phaseOf(CITY, TRESPASSER, 1_000L + WARNING));
        }

        @Test
        @DisplayName("nothing is alerted before the warning has run")
        void nothingAttacksDuringTheWarning() {
            response.warn(CITY, TRESPASSER, 1_000L);

            assertTrue(response.alertedIn(CITY, 2_000L).isEmpty(),
                    "a unit must not target anybody during the warning");
        }

        @Test
        @DisplayName("a burst warns once, not on every swing")
        void burstWarnsOnce() {
            assertTrue(response.warn(CITY, TRESPASSER, 1_000L));
            assertFalse(response.warn(CITY, TRESPASSER, 1_500L));
            assertFalse(response.warn(CITY, TRESPASSER, 2_000L));
        }

        @Test
        @DisplayName("promoting somebody who was never warned does nothing")
        void promotingWithoutAWarning() {
            assertFalse(response.promote(CITY, TRESPASSER, true, 1_000L));
        }
    }

    @Nested
    @DisplayName("the alerted phase, SPEC 26.2 step 2")
    class Alerted {

        private void alert(UUID player, long at) {
            response.warn(CITY, player, at);
            response.promote(CITY, player, true, at + WARNING);
        }

        @Test
        @DisplayName("SPEC 26.2: per player, so a companion standing by is not a target")
        void perPlayer() {
            // "ALERTED is per player, never per group. A trespasser's teammates standing
            // peacefully nearby are not attacked."
            alert(TRESPASSER, 1_000L);

            assertEquals(java.util.List.of(TRESPASSER), response.alertedIn(CITY, 7_000L));
            assertEquals(Phase.NONE, response.phaseOf(CITY, COMPANION, 7_000L));
        }

        @Test
        @DisplayName("it expires on its own")
        void expires() {
            alert(TRESPASSER, 1_000L);
            long ends = 1_000L + WARNING + ALERTED;

            assertEquals(Phase.ALERTED, response.phaseOf(CITY, TRESPASSER, ends - 1));
            assertEquals(Phase.NONE, response.phaseOf(CITY, TRESPASSER, ends));
        }

        @Test
        @DisplayName("SPEC 26.2 step 3: leaving calms it immediately")
        void leavingCalms() {
            alert(TRESPASSER, 1_000L);
            response.deEscalate(CITY, TRESPASSER);

            assertEquals(Phase.NONE, response.phaseOf(CITY, TRESPASSER, 7_000L));
            assertTrue(response.alertedIn(CITY, 7_000L).isEmpty());
        }

        @Test
        @DisplayName("one city's alert is not another's")
        void perCity() {
            alert(TRESPASSER, 1_000L);

            assertEquals(Phase.NONE, response.phaseOf(OTHER_CITY, TRESPASSER, 7_000L));
        }

        @Test
        @DisplayName("a disbanded city forgets, and so does a player who logs out")
        void forgetting() {
            alert(TRESPASSER, 1_000L);
            response.forgetCity(CITY);
            assertEquals(Phase.NONE, response.phaseOf(CITY, TRESPASSER, 7_000L));

            alert(COMPANION, 20_000L);
            response.forget(COMPANION);
            assertEquals(Phase.NONE, response.phaseOf(CITY, COMPANION, 30_000L));
        }
    }
}
