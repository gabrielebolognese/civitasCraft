package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import dev.civitas.core.defense.TargetingRule.Candidate;
import dev.civitas.core.defense.TargetingRule.Decision;
import dev.civitas.core.defense.TargetingRule.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * SPEC 30.1's table, one branch at a time, as SPEC 31 asks for by name.
 *
 * <p>The branch-by-branch tests are the easy half. {@link NeverAttackedInAnyState} is the one
 * that earns its keep, because it asserts a property no single branch carries: whatever a unit
 * is doing, it does not attack its own side.
 *
 * <h2>What that class does not prove, said plainly</h2>
 *
 * <p>It was first written as {@code Ordering}, on the belief that it verified SPEC 30.1's
 * positional order. Mutation testing showed otherwise: moving the ownership check to <em>after</em>
 * the state checks fails nothing, because it still cancels before any allow and the decision is
 * identical. Only deleting the check outright fails anything — seven tests, which is the failure
 * that matters. So the guarantee here is "ownership and alliance are always consulted before a
 * unit is allowed to attack", not "consulted first", and the class is named for what it checks.
 */
class TargetingRuleTest {

    private static final UUID TRESPASSER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BYSTANDER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final TargetingRule rule = new TargetingRule();

    /** A stranger, at arm's length, with nothing special about them. */
    private static Candidate stranger() {
        return new Candidate(true, false, false, TRESPASSER,
                false, false, false, false, false, true, 5.0);
    }

    private static Unit unit(UnitState state) {
        return new Unit(state, TRESPASSER, 24.0);
    }

    private Decision decide(Unit unit, Candidate candidate) {
        return rule.decide(unit, candidate);
    }

    // ==================================================================================
    // Non-players, SPEC 30.1 line 1
    // ==================================================================================

    @Nested
    @DisplayName("things that are not players")
    class NotPlayers {

        private Candidate mob(boolean hostile) {
            return new Candidate(false, hostile, false, null,
                    false, false, false, false, false, false, 5.0);
        }

        @Test
        @DisplayName("a PASSIVE unit attacks a hostile mob, which is its peacetime job")
        void passiveAttacksHostiles() {
            assertTrue(decide(unit(UnitState.PASSIVE), mob(true)).allowed());
        }

        @Test
        @DisplayName("a peaceful animal is never a target")
        void animalsAreSafe() {
            assertFalse(decide(unit(UnitState.PASSIVE), mob(false)).allowed());
        }

        @Test
        @DisplayName("only PASSIVE hunts mobs: a unit fighting a war has other work")
        void onlyPassiveHuntsMobs() {
            assertFalse(decide(unit(UnitState.HOSTILE), mob(true)).allowed());
            assertFalse(decide(unit(UnitState.ALERTED), mob(true)).allowed());
            assertFalse(decide(unit(UnitState.DORMANT), mob(true)).allowed());
        }

        @Test
        @DisplayName("SPEC 26.4: units never fight units, in any state")
        void unitsNeverFightUnits() {
            // "Only players kill units." Unit-versus-unit combat produces unwatchable clumps
            // of AI and lets a war resolve with no players present.
            Candidate otherUnit = new Candidate(false, true, true, null,
                    false, false, false, false, false, true, 2.0);

            for (UnitState state : UnitState.values()) {
                Decision decision = decide(unit(state), otherUnit);
                assertFalse(decision.allowed(), state + " attacked another unit");
                assertEquals("UNITS_NEVER_FIGHT_UNITS", decision.reason());
            }
        }
    }

    // ==================================================================================
    // The five cancels before state, SPEC 30.1 lines 2 to 6
    // ==================================================================================

    @Nested
    @DisplayName("players who are never targets")
    class NeverTargets {

        private Candidate with(java.util.function.UnaryOperator<Candidate> change) {
            return change.apply(stranger());
        }

        @Test
        @DisplayName("a member of the owning city")
        void ownCity() {
            Decision decision = decide(unit(UnitState.HOSTILE), with(c -> new Candidate(
                    true, false, false, c.uuid(), true, false, false, false, false, true, 5)));
            assertFalse(decision.allowed());
            assertEquals("OWN_CITY", decision.reason());
        }

        @Test
        @DisplayName("a member of an allied city")
        void alliedCity() {
            assertEquals("ALLIED_CITY", decide(unit(UnitState.HOSTILE), with(c -> new Candidate(
                    true, false, false, c.uuid(), false, true, false, false, false, true, 5)))
                    .reason());
        }

        @Test
        @DisplayName("anyone holding civitas.bypass.war")
        void bypass() {
            assertEquals("BYPASS", decide(unit(UnitState.HOSTILE), with(c -> new Candidate(
                    true, false, false, c.uuid(), false, false, true, false, false, true, 5)))
                    .reason());
        }

        @Test
        @DisplayName("anyone in Creative or Spectator")
        void creativeOrSpectator() {
            assertEquals("CREATIVE_OR_SPECTATOR", decide(unit(UnitState.HOSTILE),
                    with(c -> new Candidate(true, false, false, c.uuid(),
                            false, false, false, true, false, true, 5))).reason());
        }

        @Test
        @DisplayName("anyone who joined or respawned in the last five seconds")
        void joinGrace() {
            // A player logging in inside a city at war should not be killed before their
            // screen has finished loading.
            assertEquals("JOIN_GRACE", decide(unit(UnitState.HOSTILE), with(c -> new Candidate(
                    true, false, false, c.uuid(), false, false, false, false, true, true, 5)))
                    .reason());
        }
    }

    // ==================================================================================
    // The property the ordering exists for
    // ==================================================================================

    @Nested
    @DisplayName("a unit never attacks its own side, in any state")
    class NeverAttackedInAnyState {

        @ParameterizedTest
        @EnumSource(UnitState.class)
        @DisplayName("a member of the owning city is safe in every state")
        void ownMembersAreSafeInEveryState(UnitState state) {
            // The failure this catches is a rule that allows before ever asking whose side
            // the candidate is on — a defender killed by their own garrison in their own
            // besieged city. Verified by deleting the ownership check, which fails this.
            Candidate member = new Candidate(true, false, false, TRESPASSER,
                    true, false, false, false, false, true, 1.0);

            assertFalse(decide(new Unit(state, TRESPASSER, 24.0), member).allowed(),
                    "a " + state + " unit attacked a member of its own city");
        }

        @ParameterizedTest
        @EnumSource(UnitState.class)
        @DisplayName("an ally is safe in every state too")
        void alliesAreSafeInEveryState(UnitState state) {
            Candidate ally = new Candidate(true, false, false, TRESPASSER,
                    false, true, false, false, false, true, 1.0);

            assertFalse(decide(new Unit(state, TRESPASSER, 24.0), ally).allowed());
        }

        @Test
        @DisplayName("being the alerted target does not override membership")
        void alertedTargetStillCannotBeAMember() {
            // SPEC 26.2 counts a member's own violations toward nothing, but if that ever
            // changed, this check is what stops a city's guards turning on it.
            Candidate member = new Candidate(true, false, false, TRESPASSER,
                    true, false, false, false, false, true, 1.0);

            assertEquals("OWN_CITY",
                    decide(new Unit(UnitState.ALERTED, TRESPASSER, 24.0), member).reason());
        }
    }

    // ==================================================================================
    // State, SPEC 30.1 lines 7 to 9
    // ==================================================================================

    @Nested
    @DisplayName("what each state permits")
    class States {

        @Test
        @DisplayName("SPEC 25.2 Rule 2: a PASSIVE unit ignores a stranger completely")
        void passiveIgnoresPlayers() {
            // "Peacetime is safe." SPEC 13.4 sends players to other cities to vote on contest
            // entries, so a unit that attacks visitors makes contest voting impossible.
            Decision decision = decide(unit(UnitState.PASSIVE), stranger());
            assertFalse(decision.allowed());
            assertEquals("STATE_PASSIVE", decision.reason());
        }

        @Test
        @DisplayName("a DORMANT unit targets nothing, even though it has no entity to do it with")
        void dormantTargetsNothing() {
            assertFalse(decide(unit(UnitState.DORMANT), stranger()).allowed());
        }

        @Test
        @DisplayName("ALERTED targets its one trespasser")
        void alertedTargetsItsTrespasser() {
            assertTrue(decide(new Unit(UnitState.ALERTED, TRESPASSER, 24.0),
                    stranger()).allowed());
        }

        @Test
        @DisplayName("SPEC 26.2: and nobody else, because alerting is per player")
        void alertedIgnoresCompanions() {
            // "A trespasser's teammates standing peacefully nearby are not attacked."
            Candidate companion = new Candidate(true, false, false, BYSTANDER,
                    false, false, false, false, false, true, 5.0);

            assertEquals("NOT_THE_ALERTED_TARGET",
                    decide(new Unit(UnitState.ALERTED, TRESPASSER, 24.0), companion).reason());
        }

        @Test
        @DisplayName("an ALERTED unit with no target attacks nobody")
        void alertedWithoutATargetIsHarmless() {
            assertFalse(decide(new Unit(UnitState.ALERTED, null, 24.0), stranger()).allowed());
        }

        @Test
        @DisplayName("HOSTILE attacks an enemy in the war zone")
        void hostileAttacksEnemies() {
            assertTrue(decide(unit(UnitState.HOSTILE), stranger()).allowed());
        }

        @Test
        @DisplayName("HOSTILE ignores a neutral stranger, war or no war")
        void hostileIgnoresNeutrals() {
            Candidate neutral = new Candidate(true, false, false, TRESPASSER,
                    false, false, false, false, false, false, 5.0);

            assertEquals("NOT_AN_ENEMY",
                    decide(unit(UnitState.HOSTILE), neutral).reason());
        }
    }

    // ==================================================================================
    // Range, SPEC 30.1's last line
    // ==================================================================================

    @Nested
    @DisplayName("range")
    class Range {

        @Test
        @DisplayName("at the limit is inside it, past it is not")
        void boundary() {
            Candidate at24 = new Candidate(true, false, false, TRESPASSER,
                    false, false, false, false, false, true, 24.0);
            Candidate at25 = new Candidate(true, false, false, TRESPASSER,
                    false, false, false, false, false, true, 25.0);

            assertTrue(decide(unit(UnitState.HOSTILE), at24).allowed());
            assertEquals("OUT_OF_RANGE", decide(unit(UnitState.HOSTILE), at25).reason());
        }

        @Test
        @DisplayName("range is checked last, so a member out of range still reads as a member")
        void rangeIsCheckedLast() {
            // Not pedantry: the reason is what M12c's trespass response and any future
            // diagnostic will read, and "OUT_OF_RANGE" for a member would be misleading.
            Candidate distantMember = new Candidate(true, false, false, TRESPASSER,
                    true, false, false, false, false, true, 500.0);

            assertEquals("OWN_CITY",
                    decide(unit(UnitState.HOSTILE), distantMember).reason());
        }
    }
}
