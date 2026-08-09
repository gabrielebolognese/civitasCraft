package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import dev.civitas.core.defense.DefenseUnitType.TargetPriority;
import dev.civitas.core.defense.UnitAcquisition.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SPEC 27.4's priority and SPEC 27.5's hard cap.
 *
 * <p>Selection, never permission. {@link TargetingRule} decides who a unit <em>may</em> attack
 * and SPEC 30.1 forbids a second table doing that; everything here is handed candidates the rule
 * has already allowed and picks one of them. That distinction is the only thing that makes SPEC
 * 27.4's "prioritises the lowest-health <b>valid</b> target" implementable in the same Part that
 * forbids unit-specific targeting logic.
 */
class UnitAcquisitionTest {

    private static final UUID NEAR = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID FAR = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID FARTHEST = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    @DisplayName("SPEC 27.4: a Warhound goes for the weakest, not the nearest")
    void lowestHealthBeatsNearest() {
        List<Target> permitted = List.of(
                new Target(NEAR, 2.0, 20.0),
                new Target(FAR, 18.0, 4.0));

        assertEquals(FAR, UnitAcquisition.choose(permitted, TargetPriority.LOWEST_HEALTH, 24)
                        .orElseThrow().uuid(),
                "\"prioritises the lowest-health valid target rather than the nearest\" is what "
                        + "makes it a hunting animal and punishes retreating unhealed");
    }

    @Test
    @DisplayName("and everything else goes for the nearest")
    void nearestIsTheDefault() {
        List<Target> permitted = List.of(
                new Target(NEAR, 2.0, 20.0),
                new Target(FAR, 18.0, 4.0));

        assertEquals(NEAR, UnitAcquisition.choose(permitted, TargetPriority.NEAREST, 24)
                .orElseThrow().uuid());
    }

    @Test
    @DisplayName("SPEC 27.5's cap refuses a target the priority would otherwise pick")
    void rangeCapBinds() {
        // The Archer's 20 blocks is "hard capped". A candidate at 25 is not a candidate,
        // whatever its health, and the cap has to be applied after the priority is stated
        // rather than instead of it.
        List<Target> permitted = List.of(
                new Target(NEAR, 12.0, 20.0),
                new Target(FAR, 25.0, 1.0));

        assertEquals(NEAR, UnitAcquisition.choose(permitted, TargetPriority.LOWEST_HEALTH, 20)
                        .orElseThrow().uuid(),
                "the candidate on 1 health is out of range and must not be reached");
    }

    @Test
    @DisplayName("nothing in range means the unit's current target is left alone")
    void emptyWhenNothingIsInReach() {
        List<Target> permitted = List.of(new Target(FAR, 40.0, 1.0));

        assertTrue(UnitAcquisition.choose(permitted, TargetPriority.NEAREST, 20).isEmpty());
        assertTrue(UnitAcquisition.choose(List.of(), TargetPriority.NEAREST, 20).isEmpty(),
                "an empty candidate list is the ordinary case: the rule refused everybody");
    }

    @Test
    @DisplayName("a tie is broken the same way twice")
    void tiesAreStable() {
        // Not decoration. Two candidates on identical health would otherwise be chosen by
        // whatever order the world handed its entities back in, and a unit that switched target
        // every tick would never land a hit.
        List<Target> one = List.of(
                new Target(NEAR, 5.0, 10.0),
                new Target(FAR, 5.0, 10.0),
                new Target(FARTHEST, 5.0, 10.0));
        List<Target> reversed = List.of(
                new Target(FARTHEST, 5.0, 10.0),
                new Target(FAR, 5.0, 10.0),
                new Target(NEAR, 5.0, 10.0));

        assertEquals(
                UnitAcquisition.choose(one, TargetPriority.LOWEST_HEALTH, 24).orElseThrow(),
                UnitAcquisition.choose(reversed, TargetPriority.LOWEST_HEALTH, 24)
                        .orElseThrow());
    }

    @Test
    @DisplayName("equal health falls back to distance before it falls back to a coin toss")
    void distanceBreaksAHealthTie() {
        List<Target> permitted = List.of(
                new Target(FAR, 18.0, 10.0),
                new Target(NEAR, 3.0, 10.0));

        assertEquals(NEAR, UnitAcquisition.choose(permitted, TargetPriority.LOWEST_HEALTH, 24)
                .orElseThrow().uuid());
    }

    @Test
    @DisplayName("a target exactly at the cap is in range")
    void capIsInclusive() {
        List<Target> permitted = List.of(new Target(NEAR, 20.0, 10.0));

        assertTrue(UnitAcquisition.choose(permitted, TargetPriority.NEAREST, 20).isPresent());
    }
}
