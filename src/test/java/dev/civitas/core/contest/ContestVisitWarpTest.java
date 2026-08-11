package dev.civitas.core.contest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 40.1's contest visit warps.
 *
 * <p>SPEC 40.1 exists because SPEC 32.3 removed the world border: "An entry four hundred thousand
 * blocks out would receive zero votes, and the city that built it would be structurally excluded
 * from a core system." Voting is one of SPEC 13.3's nine ladders, so a city that cannot be visited
 * cannot compete on it at all.
 */
class ContestVisitWarpTest {

    /** What the plugin's publisher does, recorded rather than performed. */
    private record Published(String name, String world, double x, double y, double z,
                             Long expiresAt) {
    }

    private final List<Published> published = new ArrayList<>();

    private ContestService.Warps recorder() {
        return (name, world, x, y, z, expiresAt) ->
                published.add(new Published(name, world, x, y, z, expiresAt));
    }

    @Nested
    @DisplayName("the warp name")
    class Naming {

        @Test
        @DisplayName("prefixed, so the sweep can find every one without a table of its own")
        void prefixed() {
            assertTrue(ContestService.warpNameFor("Roma").startsWith(ContestService.WARP_PREFIX));
            assertEquals("contest-roma", ContestService.warpNameFor("Roma"));
        }

        @Test
        @DisplayName("lowercased, because a warp name is typed rather than clicked")
        void lowercased() {
            assertEquals(ContestService.warpNameFor("ROMA"),
                    ContestService.warpNameFor("roma"));
        }

        @Test
        @DisplayName("an operator can tell a contest warp from one they made")
        void distinguishable() {
            // The reason for the prefix rather than the bare city name: /warp lists them all
            // together, and "roma" beside "spawn" and "shop" tells nobody it is temporary.
            assertFalse(ContestService.warpNameFor("Roma").equals("roma"));
        }
    }

    @Nested
    @DisplayName("what the publisher is asked to do")
    class Publishing {

        @Test
        @DisplayName("a null world is the signal to remove, not a bad argument")
        void nullWorldRemoves() {
            // One seam for both directions, so a caller cannot publish a warp it has no way to
            // retract — which is how SPEC 40.1's "deleted when the contest closes" gets forgotten.
            ContestService.Warps warps = recorder();
            warps.publish("contest-roma", null, 0, 0, 0, null);

            assertEquals(1, published.size());
            assertEquals(null, published.get(0).world());
        }

        @Test
        @DisplayName("a published warp carries an expiry")
        void carriesExpiry() {
            // Temporary by construction: a contest that is never formally closed still stops
            // advertising its entries, which is WarpService's expiry column doing the job M3b
            // built it for.
            ContestService.Warps warps = recorder();
            warps.publish("contest-roma", "world", 100.5, 80, -200.5, 1_700_000_000_000L);

            Published entry = published.get(0);
            assertEquals("world", entry.world());
            assertEquals(1_700_000_000_000L, entry.expiresAt());
            assertEquals(100.5, entry.x(), 0.001);
        }
    }
}
