package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import dev.civitas.core.defense.TargetingRule.Candidate;
import dev.civitas.core.defense.TargetingRule.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 29.4's one hole in SPEC 26.4's "units never fight units".
 *
 * <p>The hole is the risk. SPEC 26.4 forbids unit-versus-unit combat for a stated reason — it
 * "produces unwatchable clumps of AI and makes wars resolve without players present" — and a
 * carve-out that leaked would recreate exactly that. So the tests here are mostly about what the
 * exception does <b>not</b> reach: every other unit, every other state, and a garrison on the
 * Breacher's own side.
 */
class BreacherExceptionTest {

    private final TargetingRule rule = new TargetingRule();

    private static Candidate garrison(boolean enemy, double distance) {
        return new Candidate(false, false, true, UUID.randomUUID(),
                false, false, false, false, false, enemy, distance);
    }

    private static TargetingRule.Unit breacher(UnitState state) {
        return new TargetingRule.Unit(state, null, 20, true, true);
    }

    private static TargetingRule.Unit ordinary(UnitState state) {
        return new TargetingRule.Unit(state, null, 20, true, false);
    }

    @Nested
    @DisplayName("the exception itself")
    class TheHole {

        @Test
        @DisplayName("a HOSTILE Breacher may engage an enemy garrison")
        void breacherEngages() {
            Decision decision = rule.decide(breacher(UnitState.HOSTILE), garrison(true, 5));

            assertTrue(decision.allowed());
            assertEquals("BREACHER", decision.reason());
        }

        @Test
        @DisplayName("and range still applies to it")
        void rangeStillApplies() {
            assertFalse(rule.decide(breacher(UnitState.HOSTILE), garrison(true, 40)).allowed());
        }
    }

    @Nested
    @DisplayName("what the exception does not reach")
    class StillForbidden {

        @Test
        @DisplayName("every other unit type: the rule is unchanged for them")
        void notForAnythingElse() {
            for (UnitState state : UnitState.values()) {
                Decision decision = rule.decide(ordinary(state), garrison(true, 5));

                assertFalse(decision.allowed(), state + " should not fight units");
                assertEquals("UNITS_NEVER_FIGHT_UNITS", decision.reason());
            }
        }

        @Test
        @DisplayName("a Breacher outside HOSTILE engages nothing")
        void onlyInHostile() {
            for (UnitState state : UnitState.values()) {
                if (state == UnitState.HOSTILE) {
                    continue;
                }
                assertFalse(rule.decide(breacher(state), garrison(true, 5)).allowed(),
                        "a siege unit in " + state + " has no war to fight");
            }
        }

        @Test
        @DisplayName("its own side's garrison is never a target")
        void neverItsOwnSide() {
            // An attacker's own guards are inside the war zone too, per SPEC 11.4, so this is
            // not hypothetical: without the check a Breacher would eat the garrison of the city
            // that bought it.
            Decision decision = rule.decide(breacher(UnitState.HOSTILE), garrison(false, 5));

            assertFalse(decision.allowed());
            assertEquals("NOT_AN_ENEMY_UNIT", decision.reason());
        }

        @Test
        @DisplayName("SPEC 27.8's commissioning window applies to it too")
        void commissioningApplies() {
            TargetingRule.Unit warmingUp =
                    new TargetingRule.Unit(UnitState.HOSTILE, null, 20, false, true);

            assertEquals("COMMISSIONING",
                    rule.decide(warmingUp, garrison(true, 5)).reason());
        }
    }

    @Nested
    @DisplayName("the general rule is otherwise untouched")
    class Unchanged {

        @Test
        @DisplayName("a Breacher still cannot attack a member of its own city")
        void ownCityStillSafe() {
            Candidate ownMember = new Candidate(true, false, false, UUID.randomUUID(),
                    true, false, false, false, false, true, 5);

            assertEquals("OWN_CITY",
                    rule.decide(breacher(UnitState.HOSTILE), ownMember).reason());
        }

        @Test
        @DisplayName("and a player candidate takes the ordinary path, not the carve-out")
        void playersUnaffected() {
            Candidate enemy = new Candidate(true, false, false, UUID.randomUUID(),
                    false, false, false, false, false, true, 5);

            assertEquals("ALLOWED", rule.decide(breacher(UnitState.HOSTILE), enemy).reason());
        }
    }
}
