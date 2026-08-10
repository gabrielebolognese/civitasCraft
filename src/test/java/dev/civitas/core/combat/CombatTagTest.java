package dev.civitas.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 33.8's combat tag.
 *
 * <p>{@link Refreshing#twentyArrowsAreNotTenMinutes} is the test this class exists for. Stacking
 * is the natural implementation, and SPEC 33.8 spends a paragraph explaining why it is the wrong
 * one: a long lockout "a harasser who lands one hit every four minutes can keep a target tagged
 * indefinitely". Everything else here is bookkeeping around that.
 */
class CombatTagTest {

    private static final long PEACE = 30_000L;
    private static final long WAR = 120_000L;

    private static final UUID ARCHER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final CombatTag tags = new CombatTag(PEACE, WAR);

    @Nested
    @DisplayName("refreshing, not stacking, SPEC 33.8")
    class Refreshing {

        @Test
        @DisplayName("twenty arrows are still two minutes, not ten")
        void twentyArrowsAreNotTenMinutes() {
            long now = 1_000_000L;
            for (int i = 0; i < 20; i++) {
                tags.tag(TARGET, true, now + i * 100L);
            }

            long last = now + 19 * 100L;
            assertEquals(WAR, tags.remaining(TARGET, last), 200,
                    "stacking would have made one engagement a ten-minute lockout");
        }

        @Test
        @DisplayName("a later hit does move the end, which is the point of a tag")
        void aLaterHitExtends() {
            tags.tag(TARGET, false, 1_000L);
            tags.tag(TARGET, false, 20_000L);

            assertEquals(PEACE, tags.remaining(TARGET, 20_000L), 1);
        }

        @Test
        @DisplayName("it expires on its own")
        void expires() {
            tags.tag(TARGET, false, 1_000L);

            assertTrue(tags.isTagged(TARGET, 1_000L + PEACE - 1));
            assertFalse(tags.isTagged(TARGET, 1_000L + PEACE));
        }
    }

    @Nested
    @DisplayName("who gets tagged")
    class BothParties {

        @Test
        @DisplayName("SPEC 33.8: dealing damage tags you too")
        void theArcherIsTaggedAsWellAsTheTarget() {
            // Tagging only the victim would let an archer fire and teleport out, which is the
            // whole behaviour this exists to prevent.
            tags.hit(ARCHER, TARGET, false, 1_000L);

            assertTrue(tags.isTagged(ARCHER, 1_000L), "the attacker escaped by menu");
            assertTrue(tags.isTagged(TARGET, 1_000L));
        }
    }

    @Nested
    @DisplayName("the two durations, SPEC 33.8")
    class Durations {

        @Test
        @DisplayName("30 seconds in peacetime, 120 in a war")
        void twoDurations() {
            tags.tag(TARGET, false, 1_000L);
            assertEquals(PEACE, tags.remaining(TARGET, 1_000L), 1);

            tags.clear(TARGET);
            tags.tag(TARGET, true, 1_000L);
            assertEquals(WAR, tags.remaining(TARGET, 1_000L), 1);
            assertTrue(tags.isWarTag(TARGET, 1_000L));
        }

        @Test
        @DisplayName("a stray peacetime hit cannot shorten a running war tag")
        void peacetimeCannotCutAWarTag() {
            // Otherwise a player in a war could have an ally poke them to drop a two-minute
            // lockout to thirty seconds, which is the tag being deliberately shortened.
            tags.tag(TARGET, true, 1_000L);
            tags.tag(TARGET, false, 2_000L);

            assertTrue(tags.remaining(TARGET, 2_000L) > PEACE,
                    "a peacetime hit shortened a war tag");
            assertTrue(tags.isWarTag(TARGET, 2_000L));
        }

        @Test
        @DisplayName("SPEC 33.9 case 115: a war starting extends a peacetime tag")
        void warStartingExtends() {
            tags.tag(TARGET, false, 1_000L);
            tags.warStarted(TARGET, 2_000L);

            assertEquals(WAR, tags.remaining(TARGET, 2_000L), 1);
            assertTrue(tags.isWarTag(TARGET, 2_000L));
        }

        @Test
        @DisplayName("SPEC 33.9 case 114: a war ending shortens it rather than clearing it")
        void warEndingShortens() {
            // The fight that produced it is still happening; only its stakes changed.
            tags.tag(TARGET, true, 1_000L);
            tags.warEnded(TARGET, 2_000L);

            assertTrue(tags.isTagged(TARGET, 2_000L), "the tag should survive the war ending");
            assertFalse(tags.isWarTag(TARGET, 2_000L));
            assertTrue(tags.remaining(TARGET, 2_000L) <= PEACE);
        }
    }

    @Nested
    @DisplayName("clearing")
    class Clearing {

        @Test
        @DisplayName("death and quit both drop it")
        void cleared() {
            tags.tag(TARGET, true, 1_000L);
            tags.clear(TARGET);

            assertFalse(tags.isTagged(TARGET, 1_000L));
            assertEquals(0, tags.remaining(TARGET, 1_000L));
        }

        @Test
        @DisplayName("an untagged player is simply untagged")
        void untagged() {
            assertFalse(tags.isTagged(TARGET, 1_000L));
            assertFalse(tags.isWarTag(TARGET, 1_000L));
        }

        @Test
        @DisplayName("a zero duration switches the tag off rather than tagging forever")
        void zeroDurationIsOff() {
            CombatTag none = new CombatTag(0, 0);
            none.tag(TARGET, true, 1_000L);

            assertFalse(none.isTagged(TARGET, 1_000L));
        }
    }
}
