package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 28's rule, asserted with no server in the room.
 *
 * <p>Everything that decides whether a city may have a Warden, whether a blow kills it and how far
 * it may walk lives in {@link CityWarden} for exactly this reason: MockBukkit does not implement
 * {@code org.bukkit.entity.Warden}, and an unimplemented Bukkit method is recorded by JUnit as a
 * <em>skip</em> rather than a failure. A suite that asserted these against a live entity would
 * print green having checked none of them.
 */
class CityWardenTest {

    private static final int REQUIRED = 5;

    @Nested
    @DisplayName("SPEC 28.2, the three gates")
    class Acquisition {

        @Test
        @DisplayName("a city at Fortification 5 with no Warden may buy one")
        void allowed() {
            assertEquals(Optional.empty(),
                    CityWarden.checkPurchase(true, false, 5, REQUIRED));
        }

        @Test
        @DisplayName("SPEC 28.2: one per city, so a city that has one is refused")
        void oneEach() {
            assertEquals(Optional.of(CityWarden.Refusal.ALREADY_OWNED),
                    CityWarden.checkPurchase(true, true, 5, REQUIRED));
        }

        @Test
        @DisplayName("SPEC 28.2: Fortification 4 is not enough, and 5 is")
        void fortificationGate() {
            assertEquals(Optional.of(CityWarden.Refusal.NEEDS_FORTIFICATION),
                    CityWarden.checkPurchase(true, false, 4, REQUIRED));
            assertEquals(Optional.empty(),
                    CityWarden.checkPurchase(true, false, 5, REQUIRED));
        }

        @Test
        @DisplayName("a server with the Warden turned off refuses before anything else")
        void disabled() {
            assertEquals(Optional.of(CityWarden.Refusal.DISABLED),
                    CityWarden.checkPurchase(false, true, 0, REQUIRED));
        }

        @Test
        @DisplayName("SPEC 28.2: the core chunk, and nowhere else")
        void coreChunkOnly() {
            assertEquals(Optional.empty(),
                    CityWarden.checkPlacement("world", 3, -7, "world", 3, -7));
            assertEquals(Optional.of(CityWarden.Refusal.NOT_CORE_CHUNK),
                    CityWarden.checkPlacement("world", 3, -7, "world", 4, -7));
            assertEquals(Optional.of(CityWarden.Refusal.NOT_CORE_CHUNK),
                    CityWarden.checkPlacement("world", 3, -7, "resource", 3, -7));
        }

        @Test
        @DisplayName("every refusal names a lang key, so none of them is silent")
        void everyRefusalSpeaks() {
            for (CityWarden.Refusal refusal : CityWarden.Refusal.values()) {
                assertTrue(refusal.messageKey().startsWith("warden."),
                        refusal + " must carry a warden.* lang key");
            }
        }
    }

    @Nested
    @DisplayName("SPEC 28.6, dying")
    class Dying {

        @Test
        @DisplayName("SPEC 28.6: outside a war it cannot be permanently killed")
        void peacetimeIsSurvivable() {
            assertFalse(CityWarden.diesPermanently(false));
        }

        @Test
        @DisplayName("SPEC 28.6 and case 97: inside an ACTIVE war it dies for good")
        void warIsFinal() {
            assertTrue(CityWarden.diesPermanently(true));
        }

        @Test
        @DisplayName("SPEC 28.6's six hours are a deadline, so a restart cannot shorten them")
        void recoveryIsADeadline() {
            long now = 1_000_000L;
            assertEquals(now + 6 * 3_600_000L, CityWarden.recoveryEndsAt(now, 6));
        }

        @Test
        @DisplayName("case 98: a war declared during recovery does not accelerate it")
        void warDoesNotAccelerateRecovery() {
            long now = 1_000_000L;
            CityWarden.Owned owned = new CityWarden.Owned(1, 7, now,
                    CityWarden.recoveryEndsAt(now, 6));

            // Case 98 in full: "Recovery continues. The city fights that war without it. Recovery
            // is not accelerated by war." Nothing about a war is an input to this at all, which
            // is what makes the case true by construction rather than by remembering it.
            assertTrue(owned.isRecovering(now + 5 * 3_600_000L));
            assertFalse(owned.isRecovering(now + 6 * 3_600_000L));
            assertEquals(3_600_000L,
                    owned.recoveryRemaining(now + 5 * 3_600_000L).orElseThrow());
        }

        @Test
        @DisplayName("a Warden that has come back is not recovering")
        void recovered() {
            CityWarden.Owned owned = new CityWarden.Owned(1, 7, 0L, 9_000L);
            assertTrue(owned.isRecovering(8_999L));
            assertFalse(owned.recovered().isRecovering(8_999L));
            assertEquals(Optional.empty(), owned.recovered().recoveryRemaining(0L));
        }
    }

    @Nested
    @DisplayName("SPEC 28.3, confinement")
    class Confinement {

        @Test
        @DisplayName("inside the core chunk is zero blocks out, wherever in it you stand")
        void insideTheCore() {
            assertEquals(0, CityWarden.blocksOutsideCore(0, 0, 0, 0));
            assertEquals(0, CityWarden.blocksOutsideCore(0, 0, 15, 15));
            assertEquals(0, CityWarden.blocksOutsideCore(-2, 3, -32, 48));
        }

        @Test
        @DisplayName("SPEC 28.3: six blocks past the core chunk is the limit")
        void sixBlocks() {
            // Six blocks into the neighbouring chunk, measured from its near edge.
            assertFalse(CityWarden.outsideConfinement(0, 0, 16 + 6, 8, 6));
            assertTrue(CityWarden.outsideConfinement(0, 0, 16 + 7, 8, 6));
        }

        @Test
        @DisplayName("SPEC 27.8's eight-block leash is not SPEC 28.3's six")
        void wardenLeashIsTighter() {
            // The one place the two numbers would silently collide. At seven blocks out an
            // ordinary unit is still on its leash and the Warden is not, and that difference is
            // SPEC 28.3's "It guards the City Hall, it does not patrol the city."
            assertTrue(CityWarden.outsideConfinement(0, 0, 16 + 7, 8, 6));
            assertFalse(CityWarden.outsideConfinement(0, 0, 16 + 7, 8, 8));
        }

        @Test
        @DisplayName("SPEC 28.3: darkness reaches ten blocks and no further")
        void darknessRadius() {
            assertTrue(CityWarden.withinDarkness(0, 10));
            assertTrue(CityWarden.withinDarkness(10, 10));
            assertFalse(CityWarden.withinDarkness(10.01, 10));
        }
    }
}
