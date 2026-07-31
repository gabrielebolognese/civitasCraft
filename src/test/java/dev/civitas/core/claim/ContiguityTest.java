package dev.civitas.core.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.civitas.core.claim.Contiguity.Chunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 18.1: "Contiguity flood-fill: a straight line, an L shape, a ring, a ring with the
 * connecting chunk removed (must fail), a city with outposts (outposts excluded from the
 * check)."
 *
 * <p>Each shape is built explicitly rather than generated, so a failure points at the shape
 * that broke rather than at a seed.
 */
class ContiguityTest {

    private static Set<Chunk> chunks(int... coordinates) {
        Set<Chunk> set = new LinkedHashSet<>();
        for (int i = 0; i < coordinates.length; i += 2) {
            set.add(new Chunk(coordinates[i], coordinates[i + 1]));
        }
        return set;
    }

    /** A horizontal run of {@code length} chunks starting at the origin. */
    private static Set<Chunk> line(int length) {
        Set<Chunk> set = new LinkedHashSet<>();
        for (int x = 0; x < length; x++) {
            set.add(new Chunk(x, 0));
        }
        return set;
    }

    /** The border of a square, hollow in the middle. */
    private static Set<Chunk> ring(int size) {
        Set<Chunk> set = new LinkedHashSet<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                if (x == 0 || z == 0 || x == size - 1 || z == size - 1) {
                    set.add(new Chunk(x, z));
                }
            }
        }
        return set;
    }

    // ==================================================================================
    // Adjacency, SPEC 6.1
    // ==================================================================================

    @Nested
    @DisplayName("Adjacency")
    class Adjacency {

        @Test
        @DisplayName("a chunk sharing an edge is adjacent")
        void sharesAnEdge() {
            Set<Chunk> owned = chunks(0, 0);

            assertTrue(Contiguity.isAdjacent(owned, new Chunk(1, 0)));
            assertTrue(Contiguity.isAdjacent(owned, new Chunk(-1, 0)));
            assertTrue(Contiguity.isAdjacent(owned, new Chunk(0, 1)));
            assertTrue(Contiguity.isAdjacent(owned, new Chunk(0, -1)));
        }

        @Test
        @DisplayName("a chunk touching only at a corner is NOT adjacent, SPEC 6.1")
        void diagonalIsNotAdjacent() {
            Set<Chunk> owned = chunks(0, 0);

            assertFalse(Contiguity.isAdjacent(owned, new Chunk(1, 1)));
            assertFalse(Contiguity.isAdjacent(owned, new Chunk(-1, 1)));
            assertFalse(Contiguity.isAdjacent(owned, new Chunk(1, -1)));
            assertFalse(Contiguity.isAdjacent(owned, new Chunk(-1, -1)));
        }

        @Test
        @DisplayName("a chunk two steps away is not adjacent")
        void distantIsNotAdjacent() {
            assertFalse(Contiguity.isAdjacent(chunks(0, 0), new Chunk(2, 0)));
            assertFalse(Contiguity.isAdjacent(chunks(0, 0), new Chunk(0, 5)));
        }

        @Test
        @DisplayName("the first chunk of a city is always adjacent, because it is the seed")
        void emptyCityAcceptsAnything() {
            assertTrue(Contiguity.isAdjacent(Set.of(), new Chunk(1000, -1000)));
        }

        @Test
        @DisplayName("adjacency to any owned chunk is enough, not to all of them")
        void anyNeighbourWillDo() {
            assertTrue(Contiguity.isAdjacent(line(10), new Chunk(9, 1)));
        }
    }

    // ==================================================================================
    // The SPEC 18.1 shapes
    // ==================================================================================

    @Test
    @DisplayName("a straight line is connected, and removing its end keeps it connected")
    void straightLine() {
        Set<Chunk> shape = line(5);
        Chunk core = new Chunk(0, 0);

        assertTrue(Contiguity.isConnected(shape, core));
        assertTrue(Contiguity.remainsConnected(shape, core, new Chunk(4, 0)),
                "removing the far end of a line strands nothing");
    }

    @Test
    @DisplayName("removing the middle of a line splits it, and names what was cut off")
    void lineCutInHalf() {
        Set<Chunk> shape = line(5);
        Chunk core = new Chunk(0, 0);

        assertFalse(Contiguity.remainsConnected(shape, core, new Chunk(2, 0)));

        List<Chunk> orphans = Contiguity.orphansAfterRemoving(shape, core, new Chunk(2, 0));
        assertEquals(2, orphans.size());
        assertTrue(orphans.contains(new Chunk(3, 0)));
        assertTrue(orphans.contains(new Chunk(4, 0)));
    }

    @Test
    @DisplayName("an L shape is connected, and its corner is what holds it together")
    void lShape() {
        // Three east, then three north from the corner.
        Set<Chunk> shape = chunks(0, 0, 1, 0, 2, 0, 2, 1, 2, 2);
        Chunk core = new Chunk(0, 0);

        assertTrue(Contiguity.isConnected(shape, core));
        assertTrue(Contiguity.remainsConnected(shape, core, new Chunk(2, 2)),
                "removing the tip of the short arm is fine");
        assertFalse(Contiguity.remainsConnected(shape, core, new Chunk(2, 0)),
                "removing the corner cuts the short arm off");

        assertEquals(2, Contiguity.orphansAfterRemoving(shape, core, new Chunk(2, 0)).size());
    }

    @Test
    @DisplayName("a ring is connected, and any single chunk can be removed from it")
    void ringSurvivesOneRemoval() {
        Set<Chunk> shape = ring(4);
        Chunk core = new Chunk(0, 0);

        assertTrue(Contiguity.isConnected(shape, core));

        // A ring has two paths between any two points, so no single removal can split it.
        for (Chunk chunk : shape) {
            if (chunk.equals(core)) {
                continue;
            }
            assertTrue(Contiguity.remainsConnected(shape, core, chunk),
                    "removing " + chunk + " from a ring should not split it");
        }
    }

    @Test
    @DisplayName("a ring with its connecting chunk already gone becomes a line that can be cut")
    void ringWithConnectorRemovedFails() {
        Set<Chunk> shape = ring(4);
        // Take one chunk out first, turning the ring into a horseshoe with one path.
        shape.remove(new Chunk(3, 1));
        Chunk core = new Chunk(0, 0);

        assertTrue(Contiguity.isConnected(shape, core), "a horseshoe is still one piece");

        // The horseshoe now has two dead-end arms meeting at the core. Chunk 3,0 sits at
        // the tip of the short one, reachable only through 2,0, so cutting 2,0 strands it.
        assertTrue(Contiguity.remainsConnected(shape, core, new Chunk(3, 0)),
                "removing the tip of an arm strands nothing");

        assertFalse(Contiguity.remainsConnected(shape, core, new Chunk(2, 0)),
                "cutting the only path to the tip must be refused");

        List<Chunk> orphans = Contiguity.orphansAfterRemoving(shape, core, new Chunk(2, 0));
        assertEquals(1, orphans.size());
        assertTrue(orphans.contains(new Chunk(3, 0)));
    }

    @Test
    @DisplayName("a disconnected set is never reported as connected")
    void disconnectedIsDetected() {
        Set<Chunk> shape = chunks(0, 0, 1, 0, 5, 5, 6, 5);

        assertFalse(Contiguity.isConnected(shape, new Chunk(0, 0)));
    }

    @Test
    @DisplayName("a set that does not contain its own core is not connected")
    void missingCore() {
        assertFalse(Contiguity.isConnected(chunks(1, 0, 2, 0), new Chunk(0, 0)));
    }

    // ==================================================================================
    // Edge cases the service depends on
    // ==================================================================================

    @Test
    @DisplayName("removing a chunk the city does not own changes nothing")
    void removingAnUnownedChunk() {
        Set<Chunk> shape = line(3);

        assertTrue(Contiguity.orphansAfterRemoving(shape, new Chunk(0, 0), new Chunk(9, 9)).isEmpty());
    }

    @Test
    @DisplayName("a city of one chunk can lose it without orphaning anything")
    void singleChunkCity() {
        Set<Chunk> shape = chunks(0, 0);

        assertTrue(Contiguity.orphansAfterRemoving(shape, new Chunk(0, 0), new Chunk(0, 0))
                .isEmpty());
    }

    @Test
    @DisplayName("removing the core reports every other chunk as stranded")
    void removingTheCore() {
        Set<Chunk> shape = line(4);

        List<Chunk> orphans =
                Contiguity.orphansAfterRemoving(shape, new Chunk(0, 0), new Chunk(0, 0));

        assertEquals(3, orphans.size(), "everything but the core is cut off from an absent anchor");
    }

    @Test
    @DisplayName("an empty city is trivially connected")
    void emptyIsConnected() {
        assertTrue(Contiguity.isConnected(Set.of(), new Chunk(0, 0)));
    }

    @Test
    @DisplayName("a solid block stays connected however it is trimmed at the edges")
    void solidBlock() {
        Set<Chunk> shape = new LinkedHashSet<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                shape.add(new Chunk(x, z));
            }
        }
        Chunk core = new Chunk(0, 0);

        assertTrue(Contiguity.isConnected(shape, core));
        assertTrue(Contiguity.remainsConnected(shape, core, new Chunk(3, 3)));
        assertTrue(Contiguity.remainsConnected(shape, core, new Chunk(1, 1)),
                "a hole in the middle of a solid block strands nothing");
    }

    @Test
    @DisplayName("the fill visits only edge neighbours, so a diagonal chain is four components")
    void diagonalChainIsNotConnected() {
        Set<Chunk> diagonal = chunks(0, 0, 1, 1, 2, 2, 3, 3);

        assertFalse(Contiguity.isConnected(diagonal, new Chunk(0, 0)));
    }

    @Test
    @DisplayName("neighbours are the four edge-sharing chunks and nothing else")
    void neighbourSet() {
        List<Chunk> neighbours = Contiguity.neighboursOf(new Chunk(5, -3));

        assertEquals(4, neighbours.size());
        assertTrue(neighbours.contains(new Chunk(6, -3)));
        assertTrue(neighbours.contains(new Chunk(4, -3)));
        assertTrue(neighbours.contains(new Chunk(5, -2)));
        assertTrue(neighbours.contains(new Chunk(5, -4)));
        assertFalse(neighbours.contains(new Chunk(6, -2)));
    }
}
