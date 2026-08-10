package dev.civitas.core.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.civitas.core.siege.SiegePlacement.Site;
import dev.civitas.core.siege.SiegePlacement.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 29.5's placement rules and SPEC 30.2 cases 103 and 104.
 *
 * <p>The rule is pure, so every branch is asserted with no server and no database. What it is
 * protecting is worth naming: a camp is the one thing an attacker plants on ground it does not
 * own, and every refusal here is a different way that could go wrong.
 */
class SiegePlacementTest {

    private static final int PLACER = 1;
    private static final int OTHER_CITY = 99;

    private final SiegePlacement rule = new SiegePlacement(12);

    private static Site site(boolean engaged, boolean attacking, Integer owner, int distance,
                             SiegeCamp existing) {
        return new Site(engaged, attacking, PLACER, owner, distance, existing);
    }

    private static SiegeCamp camp(boolean standing, boolean rebuilt) {
        return new SiegeCamp(7, 1, PLACER, "world", 0, 64, 0, standing ? 200 : 0,
                standing ? null : 123L, rebuilt);
    }

    @Nested
    @DisplayName("the ordinary case")
    class Allowed {

        @Test
        @DisplayName("wilderness inside the radius, during a war, on the attacking side")
        void wilderness() {
            assertEquals(Verdict.OK, rule.judge(site(true, true, null, 8, null)));
        }

        @Test
        @DisplayName("the attacker's own claims are equally fine")
        void ownClaims() {
            // SPEC 29.5: "in wilderness or their own claims".
            assertEquals(Verdict.OK, rule.judge(site(true, true, PLACER, 8, null)));
        }

        @Test
        @DisplayName("exactly at the limit still fits")
        void atTheBoundary() {
            assertEquals(Verdict.OK, rule.judge(site(true, true, null, 12, null)));
            assertEquals(Verdict.TOO_FAR, rule.judge(site(true, true, null, 13, null)));
        }
    }

    @Nested
    @DisplayName("SPEC 30.2 case 103: not on somebody else's ground")
    class ForeignGround {

        @Test
        @DisplayName("a third city's claims are refused")
        void thirdCity() {
            // Planting a camp inside an uninvolved city makes them a battlefield without their
            // consent, which is the same narrowing SPEC 33.4 makes for war PvP.
            assertEquals(Verdict.FOREIGN_GROUND,
                    rule.judge(site(true, true, OTHER_CITY, 4, null)));
        }

        @Test
        @DisplayName("and so are the defender's own claims")
        void insideTheDefender() {
            // The defender is a third party for this purpose too: a camp inside the city being
            // besieged would be a staging point nobody has to travel to.
            assertEquals(Verdict.FOREIGN_GROUND,
                    rule.judge(site(true, true, OTHER_CITY, 0, null)));
        }
    }

    @Nested
    @DisplayName("who and when")
    class Eligibility {

        @Test
        @DisplayName("SPEC 29.4: not outside PREP or ACTIVE")
        void wrongPhase() {
            assertEquals(Verdict.WRONG_PHASE, rule.judge(site(false, true, null, 4, null)));
        }

        @Test
        @DisplayName("SPEC 29.5: only attackers plant camps")
        void defendersDoNot() {
            // "Attackers place a Siege Camp banner block". A defender under siege does not lay
            // one of its own, and reading it otherwise would give the defender a second garrison
            // budget that SPEC 29.2 never sized.
            assertEquals(Verdict.NOT_ATTACKING, rule.judge(site(true, false, null, 4, null)));
        }

        @Test
        @DisplayName("phase is checked before side, so a peacetime city is told the real reason")
        void phaseBeforeSide() {
            assertEquals(Verdict.WRONG_PHASE, rule.judge(site(false, false, null, 4, null)));
        }
    }

    @Nested
    @DisplayName("SPEC 29.5: one camp per city, and one rebuild")
    class OneEach {

        @Test
        @DisplayName("a standing camp blocks a second")
        void alreadyPlaced() {
            assertEquals(Verdict.ALREADY_PLACED,
                    rule.judge(site(true, true, null, 4, camp(true, false))));
        }

        @Test
        @DisplayName("a destroyed camp may be rebuilt once")
        void rebuildAllowed() {
            Site after = site(true, true, null, 4, camp(false, false));

            assertEquals(Verdict.OK, rule.judge(after));
            assertTrue(rule.isRebuild(after), "and that placement is the one that costs half");
        }

        @Test
        @DisplayName("but only once")
        void rebuildSpent() {
            assertEquals(Verdict.REBUILD_SPENT,
                    rule.judge(site(true, true, null, 4, camp(false, true))));
        }

        @Test
        @DisplayName("a first camp is not a rebuild")
        void firstIsNotARebuild() {
            assertFalse(rule.isRebuild(site(true, true, null, 4, null)));
        }
    }

    @Nested
    @DisplayName("SPEC 30.2 case 104: two allied attackers")
    class AlliedAttackers {

        @Test
        @DisplayName("each may plant its own, because the rule is per city")
        void oneEach() {
            // The ally's camp is not this city's camp, so `existing` is null for them and the
            // rule never sees it. Asserted because "one camp per attacking city" reads at a
            // glance like "one camp per attack".
            SiegeCamp allysCamp = new SiegeCamp(9, 1, 42, "world", 500, 64, 500, 200, null, false);

            assertEquals(Verdict.OK, rule.judge(new Site(true, true, PLACER, null, 6, null)));
            assertEquals(Verdict.ALREADY_PLACED,
                    rule.judge(new Site(true, true, 42, null, 6, allysCamp)));
        }
    }

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @Test
        @DisplayName("a defender with no land anywhere in this world refuses rather than allows")
        void noDefenderClaims() {
            // SiegeService returns MAX_VALUE when it finds nothing to measure against. Reading
            // that as "close enough" would let a camp be planted anywhere on the map.
            assertEquals(Verdict.TOO_FAR,
                    rule.judge(site(true, true, null, Integer.MAX_VALUE, null)));
        }
    }
}
