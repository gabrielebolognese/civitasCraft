package dev.civitas.core.claim;

/**
 * Packs a world and a chunk position into one {@code long}, SPEC 2.3.
 *
 * <h2>The bit budget</h2>
 * SPEC 2.3 asks for a single long holding {@code (worldId, chunkX, chunkZ)}, but two full
 * {@code int} coordinates already fill all 64 bits. The layout here spends
 * <strong>12 bits on the world index and 26 on each coordinate</strong>:
 *
 * <pre>
 *   63    52 51           26 25            0
 *   [ world ][    chunkX    ][    chunkZ    ]
 * </pre>
 *
 * <p>26 signed bits reach ±33,554,432 chunks. Minecraft's maximum world border is 29,999,984
 * blocks, which is 1,874,999 chunks, so the range is roughly eighteen times what the game can
 * produce. 12 bits allow 4,096 worlds on one server.
 *
 * <p>Both limits are checked rather than assumed. Silently wrapping would alias two distinct
 * chunks onto one key, and since this map is the authority on who owns what, that would hand
 * one city another city's land.
 */
public final class ChunkKey {

    private static final int WORLD_BITS = 12;
    private static final int COORD_BITS = 26;
    private static final long COORD_MASK = (1L << COORD_BITS) - 1L;

    /** Worlds representable in a key. */
    public static final int MAX_WORLDS = 1 << WORLD_BITS;

    /** Lowest representable chunk coordinate. */
    public static final int MIN_COORD = -(1 << (COORD_BITS - 1));

    /** Highest representable chunk coordinate. */
    public static final int MAX_COORD = (1 << (COORD_BITS - 1)) - 1;

    private ChunkKey() {
    }

    /**
     * @param worldIndex a per-server world index from {@link ClaimRegistry}
     * @param chunkX     chunk X, not block X
     * @param chunkZ     chunk Z, not block Z
     * @throws IllegalArgumentException if any component is outside the representable range
     */
    public static long pack(int worldIndex, int chunkX, int chunkZ) {
        if (worldIndex < 0 || worldIndex >= MAX_WORLDS) {
            throw new IllegalArgumentException("World index " + worldIndex
                    + " is outside 0.." + (MAX_WORLDS - 1)
                    + "; this server has more worlds than the chunk key can address.");
        }
        requireInRange(chunkX, "chunkX");
        requireInRange(chunkZ, "chunkZ");

        return ((long) worldIndex << (COORD_BITS * 2))
                | ((chunkX & COORD_MASK) << COORD_BITS)
                | (chunkZ & COORD_MASK);
    }

    public static int worldIndex(long key) {
        return (int) (key >>> (COORD_BITS * 2));
    }

    public static int chunkX(long key) {
        return signExtend((int) ((key >>> COORD_BITS) & COORD_MASK));
    }

    public static int chunkZ(long key) {
        return signExtend((int) (key & COORD_MASK));
    }

    /** Whether a coordinate fits, so a caller can refuse politely instead of throwing. */
    public static boolean isInRange(int coordinate) {
        return coordinate >= MIN_COORD && coordinate <= MAX_COORD;
    }

    /** Chebyshev distance in chunks, the metric SPEC 6.2 and 6.3 measure with. */
    public static int chebyshev(int fromX, int fromZ, int toX, int toZ) {
        return Math.max(Math.abs(fromX - toX), Math.abs(fromZ - toZ));
    }

    /** Whether two chunks share an edge. Diagonal neighbours do not, per SPEC 6.1. */
    public static boolean isEdgeAdjacent(int firstX, int firstZ, int secondX, int secondZ) {
        int dx = Math.abs(firstX - secondX);
        int dz = Math.abs(firstZ - secondZ);
        return dx + dz == 1;
    }

    /** Block coordinate to chunk coordinate, arithmetic shift so negatives floor correctly. */
    public static int toChunk(int blockCoordinate) {
        return blockCoordinate >> 4;
    }

    private static void requireInRange(int coordinate, String name) {
        if (!isInRange(coordinate)) {
            throw new IllegalArgumentException(name + " " + coordinate + " is outside "
                    + MIN_COORD + ".." + MAX_COORD + ", beyond any possible world border.");
        }
    }

    private static int signExtend(int value) {
        return (value << (32 - COORD_BITS)) >> (32 - COORD_BITS);
    }
}
