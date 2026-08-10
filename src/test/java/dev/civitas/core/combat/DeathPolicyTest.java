package dev.civitas.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import dev.civitas.core.combat.DeathPolicy.Cause;
import dev.civitas.core.combat.DeathPolicy.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 33.6's death table.
 *
 * <p>{@link WhatIsLost#warIsTheOnlyWayToLoseThingsToAnotherPlayer} is the property worth having.
 * Read with SPEC 11.7 — hand-looted container items are never restored by the rollback — it says
 * war is the <b>only</b> mechanism in the plugin by which a player permanently loses possessions
 * to another player. Everything else the plugin takes is money, ranking or reputation, and that
 * is what makes SPEC 1.2's "destruction is never permanent" true everywhere else.
 */
class DeathPolicyTest {

    private static final long WINDOW = 30_000L;
    private static final UUID PLACER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** The shipped configuration: keep in peacetime PvP, drop in war, drop to mobs and terrain. */
    private final DeathPolicy policy = new DeathPolicy(WINDOW, true, false, false, false);

    @Nested
    @DisplayName("what is lost, SPEC 33.6")
    class WhatIsLost {

        @Test
        @DisplayName("war is the only way to lose things to another player")
        void warIsTheOnlyWayToLoseThingsToAnotherPlayer() {
            assertEquals(Outcome.DROP, policy.decide(Cause.PLAYER, true),
                    "a war kill takes everything, which is what gives raiding weight");
            assertEquals(Outcome.KEEP, policy.decide(Cause.PLAYER, false),
                    "and a peacetime kill takes nothing, so wilderness PvP has no loot motive");
        }

        @Test
        @DisplayName("SPEC 33.9 case 127: a defense unit is a mob, not a war participant")
        void aGarrisonKillIsAMobKill() {
            // However inconvenient for the city that paid for the garrison: killing a raider
            // with a Colossus does not hand the city their inventory.
            assertEquals(Outcome.DROP, policy.decide(Cause.MOB, true));
            assertEquals(Outcome.DROP, policy.decide(Cause.MOB, false),
                    "and the war makes no difference to it either way");
        }

        @Test
        @DisplayName("environment drops in war and out, SPEC 33.6")
        void environmentIsUnchangedByWar() {
            assertEquals(Outcome.DROP, policy.decide(Cause.ENVIRONMENT, true));
            assertEquals(Outcome.DROP, policy.decide(Cause.ENVIRONMENT, false));
        }

        @Test
        @DisplayName("every branch is configurable, per the hard rule on hardcoded numbers")
        void configurable() {
            // SPEC 33.7 documents war graves as the intended remedy if 33.6 proves too
            // punishing, and flipping keepOnWarPvp is the first half of enabling it.
            DeathPolicy gentle = new DeathPolicy(WINDOW, true, true, true, true);

            assertEquals(Outcome.KEEP, gentle.decide(Cause.PLAYER, true));
            assertEquals(Outcome.KEEP, gentle.decide(Cause.ENVIRONMENT, false));
        }
    }

    @Nested
    @DisplayName("attribution, SPEC 33.6")
    class Attribution {

        @Test
        @DisplayName("SPEC 33.9 case 119: TNT is answered for by whoever placed it")
        void tntIsAttributedToItsPlacer() {
            // None of TNT, fire, lava or a crystal names its author at the moment it kills:
            // the victim has a DamageCause, not an attacker.
            policy.placed("world:10:64:10", PLACER, 1_000L);

            assertEquals(PLACER, policy.attributedTo("world:10:64:10", 5_000L));
        }

        @Test
        @DisplayName("past the window it is an environmental death, which keeps nothing extra")
        void staleAttributionIsEnvironmental() {
            // The conservative direction: an unattributed death drops as terrain rather than
            // being credited to somebody who walked away five minutes ago.
            policy.placed("world:10:64:10", PLACER, 1_000L);

            assertNull(policy.attributedTo("world:10:64:10", 1_000L + WINDOW));
        }

        @Test
        @DisplayName("somewhere nobody touched is nobody's doing")
        void unknownPlaceIsUnattributed() {
            assertNull(policy.attributedTo("world:0:0:0", 1_000L));
        }

        @Test
        @DisplayName("the sweep drops what has gone stale, so the map stays bounded")
        void sweepIsBounded() {
            policy.placed("a", PLACER, 1_000L);
            policy.placed("b", PLACER, 1_000L);
            policy.placed("c", PLACER, 1_000L + WINDOW);

            assertEquals(2, policy.sweep(1_000L + WINDOW));
            assertEquals(1, policy.trackedPlacements(), "the fresh one survives");
        }
    }
}
