package dev.civitas.core.outpost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import dev.civitas.core.outpost.OutpostGeometry.Chunk;
import dev.civitas.core.outpost.OutpostGeometry.Merge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 39.6's shape rules and SPEC 39.7's merging.
 *
 * <p>SPEC 18.1 asks for the contiguity flood-fill against "a straight line, an L shape, a ring, a
 * ring with the connecting chunk removed (must fail)". Those cases were written for a city body at
 * M3 and apply again inside an outpost, so they are repeated here rather than assumed to carry
 * over — a second implementation of a rule needs its own evidence.
 */
class OutpostGeometryTest {

    private static Chunk at(int x, int z) {
        return new Chunk(x, z);
    }

    // ==================================================================================
    // SPEC 18.1's contiguity cases, inside an outpost
    // ==================================================================================

    @Nested
    @DisplayName("internal contiguity, SPEC 39.6")
    class Contiguity {

        @Test
        @DisplayName("a straight line is contiguous")
        void line() {
            assertTrue(OutpostGeometry.isContiguous(
                    Set.of(at(0, 0), at(1, 0), at(2, 0), at(3, 0))));
        }

        @Test
        @DisplayName("an L shape is contiguous")
        void lShape() {
            assertTrue(OutpostGeometry.isContiguous(
                    Set.of(at(0, 0), at(1, 0), at(1, 1), at(1, 2))));
        }

        @Test
        @DisplayName("a 2x2 square is contiguous")
        void square() {
            assertTrue(OutpostGeometry.isContiguous(
                    Set.of(at(0, 0), at(1, 0), at(0, 1), at(1, 1))));
        }

        @Test
        @DisplayName("two chunks touching only at a corner are NOT contiguous")
        void diagonalIsNotAdjacency() {
            // SPEC 6.1's rule, which SPEC 39.6 inherits: "A new normal claim must share an edge
            // (not merely a corner)". A diagonal outpost would be two places, not one.
            assertFalse(OutpostGeometry.isContiguous(Set.of(at(0, 0), at(1, 1))));
        }

        @Test
        @DisplayName("a ring is contiguous, and a ring with its connector removed is not")
        void ringAndBrokenRing() {
            // SPEC 18.1 names this pair specifically. A ring is the shape where a naive
            // "each chunk touches the previous one" check passes and a flood fill is needed.
            Set<Chunk> ring = Set.of(
                    at(0, 0), at(1, 0), at(2, 0),
                    at(0, 1), at(2, 1),
                    at(0, 2), at(1, 2), at(2, 2));
            assertTrue(OutpostGeometry.isContiguous(ring));

            Set<Chunk> broken = Set.of(
                    at(0, 0), at(2, 0),
                    at(0, 1), at(2, 1),
                    at(0, 2), at(1, 2), at(2, 2));
            assertTrue(OutpostGeometry.isContiguous(broken),
                    "removing one of two connectors leaves a U, which is still one piece");

            Set<Chunk> severed = Set.of(at(0, 0), at(1, 0), at(3, 0), at(4, 0));
            assertFalse(OutpostGeometry.isContiguous(severed),
                    "a genuine gap must fail");
        }

        @Test
        @DisplayName("one chunk and no chunks are both contiguous")
        void degenerate() {
            assertTrue(OutpostGeometry.isContiguous(Set.of()));
            assertTrue(OutpostGeometry.isContiguous(Set.of(at(5, 5))));
        }

        @Test
        @DisplayName("SPEC 39.14 case 133: removing a middle chunk is refused")
        void removalThatSplits() {
            Set<Chunk> line = Set.of(at(0, 0), at(1, 0), at(2, 0));

            assertFalse(OutpostGeometry.survivesRemoval(line, at(1, 0)),
                    "removing the middle of a line splits it");
            assertTrue(OutpostGeometry.survivesRemoval(line, at(2, 0)),
                    "removing an end does not");
        }

        @Test
        @DisplayName("a new chunk must border the outpost it joins, SPEC 39.6")
        void expansionMustTouch() {
            Set<Chunk> outpost = Set.of(at(0, 0));

            assertTrue(OutpostGeometry.extendsOutpost(outpost, at(1, 0)));
            assertFalse(OutpostGeometry.extendsOutpost(outpost, at(1, 1)), "a corner");
            assertFalse(OutpostGeometry.extendsOutpost(outpost, at(5, 5)), "nowhere near");
            assertTrue(OutpostGeometry.extendsOutpost(Set.of(), at(9, 9)),
                    "the founding chunk of a new outpost touches nothing by definition");
        }
    }

    // ==================================================================================
    // SPEC 39.6's placement distances
    // ==================================================================================

    @Nested
    @DisplayName("placement distances, SPEC 39.6")
    class Placement {

        @Test
        @DisplayName("distance is Chebyshev, the same measure SPEC 6.2 uses")
        void chebyshev() {
            assertEquals(3, at(0, 0).chebyshev(at(3, 2)));
            assertEquals(3, at(0, 0).chebyshev(at(3, 3)),
                    "a diagonal is three chunks away, not four or 4.24");
        }

        @Test
        @DisplayName("the nearest of several is what counts")
        void nearest() {
            assertEquals(2, OutpostGeometry.nearestDistance(at(0, 0),
                    List.of(at(10, 10), at(2, 0), at(40, 40))));
        }

        @Test
        @DisplayName("with nothing to measure against, nothing is too close")
        void nothingToMeasure() {
            // A city's first outpost must not be refused for crowding outposts it does not have.
            assertEquals(Integer.MAX_VALUE,
                    OutpostGeometry.nearestDistance(at(0, 0), List.of()));
            assertTrue(OutpostGeometry.isAtLeast(24, at(0, 0), List.of()));
        }

        @Test
        @DisplayName("the 32-chunk rule from the city body, SPEC 39.6")
        void fromTheCityBody() {
            List<Chunk> body = List.of(at(0, 0));

            assertFalse(OutpostGeometry.isAtLeast(32, at(31, 0), body));
            assertTrue(OutpostGeometry.isAtLeast(32, at(32, 0), body),
                    "exactly 32 passes, because SPEC words it as a minimum");
        }

        @Test
        @DisplayName("the 24-chunk rule between a city's own outposts, SPEC 39.6")
        void betweenOwnOutposts() {
            // "Six outposts of four chunks each is twenty-four remote chunks. With a 24-chunk
            // minimum spacing between them, they cannot be arranged into a continuous road."
            List<Chunk> existing = List.of(at(100, 100));

            assertFalse(OutpostGeometry.isAtLeast(24, at(100, 123), existing));
            assertTrue(OutpostGeometry.isAtLeast(24, at(100, 124), existing));
        }

        @Test
        @DisplayName("a minimum of zero switches a distance rule off")
        void zeroDisables() {
            assertTrue(OutpostGeometry.isAtLeast(0, at(0, 0), List.of(at(0, 0))));
        }
    }

    // ==================================================================================
    // SPEC 39.7's merging
    // ==================================================================================

    @Nested
    @DisplayName("merging, SPEC 39.7")
    class Merging {

        private static final int MAX = 4;

        @Test
        @DisplayName("an ordinary claim touching nothing merges nothing")
        void nothingHappens() {
            assertEquals(Merge.NONE, OutpostGeometry.judge(at(50, 50),
                    List.of(at(0, 0)), List.of(Set.of(at(100, 100))), MAX));
        }

        @Test
        @DisplayName("a chunk bridging the city body and an outpost absorbs the outpost")
        void intoCity() {
            // SPEC 39.7: "The entire outpost merges into the city. All its chunks convert to
            // NORMAL, the outpost slot frees, the warp point is deleted, and nothing is
            // refunded."
            assertEquals(Merge.INTO_CITY, OutpostGeometry.judge(at(1, 0),
                    List.of(at(0, 0)), List.of(Set.of(at(2, 0))), MAX));
        }

        @Test
        @DisplayName("absorbing into the city ignores the four-chunk cap")
        void intoCityIgnoresSize() {
            // The result is city land rather than an outpost, so the outpost maximum does not
            // apply. A four-chunk outpost absorbed by the body is simply four more city chunks.
            Set<Chunk> full = Set.of(at(2, 0), at(3, 0), at(4, 0), at(5, 0));

            assertEquals(Merge.INTO_CITY, OutpostGeometry.judge(at(1, 0),
                    List.of(at(0, 0)), List.of(full), MAX));
        }

        @Test
        @DisplayName("a chunk bridging two of a city's own outposts merges them")
        void mergesOutposts() {
            assertEquals(Merge.OUTPOSTS, OutpostGeometry.judge(at(1, 0),
                    List.of(at(500, 500)),
                    List.of(Set.of(at(0, 0)), Set.of(at(2, 0))), MAX));
        }

        @Test
        @DisplayName("a merge that would exceed four chunks BLOCKS the claim")
        void blockedTooLarge() {
            // SPEC 39.7 is explicit: "the merge is blocked and the claim that would trigger it
            // is rejected with a clear message". Not merged-and-truncated, and not merged into
            // an oversized outpost.
            Set<Chunk> two = Set.of(at(0, 0), at(0, 1));
            Set<Chunk> three = Set.of(at(2, 0), at(3, 0), at(3, 1));

            assertEquals(Merge.BLOCKED_TOO_LARGE, OutpostGeometry.judge(at(1, 0),
                    List.of(at(500, 500)), List.of(two, three), MAX));
        }

        @Test
        @DisplayName("exactly four is allowed, five is not")
        void boundary() {
            Set<Chunk> one = Set.of(at(0, 0));
            Set<Chunk> twoChunks = Set.of(at(2, 0), at(2, 1));
            assertEquals(Merge.OUTPOSTS, OutpostGeometry.judge(at(1, 0),
                    List.of(at(500, 500)), List.of(one, twoChunks), MAX),
                    "1 + 1 + 2 is exactly four");

            Set<Chunk> threeChunks = Set.of(at(2, 0), at(2, 1), at(2, 2));
            assertEquals(Merge.BLOCKED_TOO_LARGE, OutpostGeometry.judge(at(1, 0),
                    List.of(at(500, 500)), List.of(one, threeChunks), MAX),
                    "1 + 1 + 3 is five");
        }

        @Test
        @DisplayName("touching one outpost is expansion, not a merge")
        void oneOutpostIsExpansion() {
            assertEquals(Merge.NONE, OutpostGeometry.judge(at(1, 0),
                    List.of(at(500, 500)), List.of(Set.of(at(0, 0))), MAX));
        }

        @Test
        @DisplayName("the city bridge wins over an outpost bridge when both apply")
        void cityWins() {
            // A chunk touching the body and two outposts. Absorbing into the city is the
            // outcome that frees slots and cannot be blocked, so it is the one to take.
            assertEquals(Merge.INTO_CITY, OutpostGeometry.judge(at(1, 0),
                    List.of(at(1, 1)),
                    List.of(Set.of(at(0, 0)), Set.of(at(2, 0))), MAX));
        }

        @Test
        @DisplayName("SPEC 39.14 case 132: a city that grows into its own outpost")
        void cityGrowsIntoOutpost() {
            // The other direction, where the bridging claim is a city claim and the outpost is
            // discovered to be adjacent afterwards.
            assertTrue(OutpostGeometry.bordersCity(Set.of(at(2, 0)), List.of(at(1, 0))));
            assertFalse(OutpostGeometry.bordersCity(Set.of(at(2, 0)), List.of(at(0, 0))),
                    "two chunks apart is not bordering");
            assertFalse(OutpostGeometry.bordersCity(Set.of(at(2, 0)), List.of(at(1, 1))),
                    "and a corner is not bordering either");
        }
    }
}
