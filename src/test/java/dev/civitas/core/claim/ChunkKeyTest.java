package dev.civitas.core.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The packed chunk key from SPEC 2.3.
 *
 * <p>The stake here is ownership: this map is the authority on who owns what, so two
 * distinct chunks aliasing onto one key would hand a city another city's land. Everything
 * below is about proving that cannot happen quietly.
 */
class ChunkKeyTest {

    @ParameterizedTest(name = "world {0}, chunk {1},{2}")
    @CsvSource({
            "0, 0, 0",
            "0, 1, 1",
            "0, -1, -1",
            "1, 100, -250",
            "7, -33554432, 33554431",
            "4095, 1874999, -1874999",
            "0, 33554431, 33554431",
            "0, -33554432, -33554432"})
    @DisplayName("every component survives packing and unpacking")
    void roundTrip(int worldIndex, int chunkX, int chunkZ) {
        long key = ChunkKey.pack(worldIndex, chunkX, chunkZ);

        assertEquals(worldIndex, ChunkKey.worldIndex(key), "world index");
        assertEquals(chunkX, ChunkKey.chunkX(key), "chunk X");
        assertEquals(chunkZ, ChunkKey.chunkZ(key), "chunk Z");
    }

    @Test
    @DisplayName("no two distinct positions share a key")
    void keysAreUnique() {
        Set<Long> seen = new HashSet<>();

        for (int world = 0; world < 3; world++) {
            for (int x = -40; x <= 40; x++) {
                for (int z = -40; z <= 40; z++) {
                    assertTrue(seen.add(ChunkKey.pack(world, x, z)),
                            "collision at world " + world + " chunk " + x + "," + z);
                }
            }
        }
    }

    @Test
    @DisplayName("the same chunk in two worlds is two different keys, SPEC 17.2 case 23")
    void worldIsPartOfTheKey() {
        assertNotEquals(ChunkKey.pack(0, 5, 5), ChunkKey.pack(1, 5, 5));
    }

    @Test
    @DisplayName("negative coordinates do not alias onto positive ones")
    void negativesDoNotAlias() {
        assertNotEquals(ChunkKey.pack(0, -1, 0), ChunkKey.pack(0, 1, 0));
        assertNotEquals(ChunkKey.pack(0, 0, -1), ChunkKey.pack(0, 0, 1));
        assertNotEquals(ChunkKey.pack(0, -1, -1), ChunkKey.pack(0, 1, 1));
    }

    @Test
    @DisplayName("the representable range comfortably exceeds any real world border")
    void rangeCoversTheGame() {
        // Minecraft's largest world border is 29,999,984 blocks, or 1,874,999 chunks.
        int maxGameChunk = 1_874_999;

        assertTrue(ChunkKey.isInRange(maxGameChunk));
        assertTrue(ChunkKey.isInRange(-maxGameChunk));
        assertTrue(ChunkKey.MAX_COORD > maxGameChunk * 10L,
                "the key should have an order of magnitude in hand");
    }

    @Test
    @DisplayName("a coordinate outside the range throws rather than wrapping")
    void outOfRangeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ChunkKey.pack(0, ChunkKey.MAX_COORD + 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ChunkKey.pack(0, 0, ChunkKey.MIN_COORD - 1));
        assertThrows(IllegalArgumentException.class,
                () -> ChunkKey.pack(0, Integer.MAX_VALUE, 0));

        assertFalse(ChunkKey.isInRange(ChunkKey.MAX_COORD + 1));
        assertFalse(ChunkKey.isInRange(ChunkKey.MIN_COORD - 1));
    }

    @Test
    @DisplayName("a world index beyond the budget throws rather than aliasing two worlds")
    void tooManyWorldsThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ChunkKey.pack(ChunkKey.MAX_WORLDS, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ChunkKey.pack(-1, 0, 0));

        assertEquals(4096, ChunkKey.MAX_WORLDS);
    }

    // ==================================================================================
    // The geometry helpers the claim rules are written in terms of
    // ==================================================================================

    @ParameterizedTest(name = "({0},{1}) to ({2},{3}) is {4}")
    @CsvSource({
            "0, 0, 0, 0, 0",
            "0, 0, 1, 0, 1",
            "0, 0, 0, 1, 1",
            "0, 0, 1, 1, 1",
            "0, 0, 3, 4, 4",
            "0, 0, -5, 2, 5",
            "10, 10, 7, 7, 3"})
    @DisplayName("Chebyshev distance is the larger of the two axis distances")
    void chebyshev(int fromX, int fromZ, int toX, int toZ, int expected) {
        assertEquals(expected, ChunkKey.chebyshev(fromX, fromZ, toX, toZ));
        assertEquals(expected, ChunkKey.chebyshev(toX, toZ, fromX, fromZ), "symmetric");
    }

    @Test
    @DisplayName("edge adjacency accepts the four neighbours and rejects diagonals")
    void edgeAdjacency() {
        assertTrue(ChunkKey.isEdgeAdjacent(0, 0, 1, 0));
        assertTrue(ChunkKey.isEdgeAdjacent(0, 0, -1, 0));
        assertTrue(ChunkKey.isEdgeAdjacent(0, 0, 0, 1));
        assertTrue(ChunkKey.isEdgeAdjacent(0, 0, 0, -1));

        assertFalse(ChunkKey.isEdgeAdjacent(0, 0, 1, 1), "a corner is not an edge, SPEC 6.1");
        assertFalse(ChunkKey.isEdgeAdjacent(0, 0, 0, 0), "a chunk is not its own neighbour");
        assertFalse(ChunkKey.isEdgeAdjacent(0, 0, 2, 0));
    }

    @ParameterizedTest(name = "block {0} is in chunk {1}")
    @CsvSource({"0, 0", "15, 0", "16, 1", "31, 1", "-1, -1", "-16, -1", "-17, -2", "1000, 62"})
    @DisplayName("block coordinates floor to chunk coordinates, including negatives")
    void blockToChunk(int block, int chunk) {
        assertEquals(chunk, ChunkKey.toChunk(block));
    }

    @Test
    @DisplayName("block -1 is chunk -1, not chunk 0, which integer division would get wrong")
    void negativeBlocksFloorCorrectly() {
        // -1 / 16 == 0 in Java, which would put the block just west of origin in the wrong
        // chunk and let a player build one block outside a claim without protection.
        assertEquals(-1, ChunkKey.toChunk(-1));
        assertNotEquals(-1 / 16, ChunkKey.toChunk(-1));
    }
}
