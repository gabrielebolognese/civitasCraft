package dev.civitas.core.war;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.civitas.core.claim.Claim;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * The ground a war is fought over, SPEC 11.4.
 *
 * <h2>Computed once</h2>
 * SPEC 11.4 defines the zone as the claims of both cities, their outposts, and a one-chunk
 * perimeter around each. It is built at war start into a packed-key set and never rebuilt.
 * That is not an optimisation, it is the rule: SPEC 6.3 precondition 9 forbids claiming during
 * a war, and SPEC 17.4 case 53 says that even an admin unclaiming a chunk mid-war leaves it
 * inside the zone. A zone that moved would mean damage logged against ground that is no longer
 * being rolled back.
 *
 * <h2>Why a packed set</h2>
 * This is consulted on every block change of every player in the war, which is the hottest
 * path in the plugin after claim lookup. A {@code LongOpenHashSet} of packed chunk keys
 * answers in constant time with no allocation, which is what SPEC 11.4 asks for by name.
 */
public final class WarZone {

    private static final int COORD_BITS = 26;
    private static final long COORD_MASK = (1L << COORD_BITS) - 1L;

    private final LongOpenHashSet chunks;
    private final Map<String, Integer> worldIndices;
    private final List<String> worldNames;

    private WarZone(LongOpenHashSet chunks, Map<String, Integer> worldIndices,
                    List<String> worldNames) {
        this.chunks = chunks;
        this.worldIndices = worldIndices;
        this.worldNames = worldNames;
    }

    /**
     * Builds the zone from the claims of every participating city.
     *
     * @param perimeter how many chunks of buffer to add around each claim, SPEC 16.3's
     *                  {@code zone.perimeter-chunks}
     */
    public static WarZone of(Collection<Claim> claims, int perimeter) {
        Objects.requireNonNull(claims, "claims");
        LongOpenHashSet packed = new LongOpenHashSet();
        Map<String, Integer> indices = new HashMap<>();
        List<String> names = new ArrayList<>();
        int buffer = Math.max(0, perimeter);

        for (Claim claim : claims) {
            int worldIndex = indices.computeIfAbsent(claim.world(), world -> {
                names.add(world);
                return names.size() - 1;
            });
            for (int dx = -buffer; dx <= buffer; dx++) {
                for (int dz = -buffer; dz <= buffer; dz++) {
                    packed.add(pack(worldIndex, claim.chunkX() + dx, claim.chunkZ() + dz));
                }
            }
        }
        return new WarZone(packed, indices, names);
    }

    /** An empty zone, for a war whose cities somehow hold no land. */
    public static WarZone empty() {
        return new WarZone(new LongOpenHashSet(), new HashMap<>(), new ArrayList<>());
    }

    /** Whether a chunk is inside. The question asked on the block path. */
    public boolean containsChunk(String world, int chunkX, int chunkZ) {
        Integer index = worldIndices.get(world);
        if (index == null) {
            return false;
        }
        return chunks.contains(pack(index, chunkX, chunkZ));
    }

    /** Whether a block position is inside. */
    public boolean containsBlock(String world, int blockX, int blockZ) {
        return containsChunk(world, blockX >> 4, blockZ >> 4);
    }

    public int size() {
        return chunks.size();
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public List<String> worlds() {
        return List.copyOf(worldNames);
    }

    /** Every chunk in the zone, as {@code [worldIndex, chunkX, chunkZ]}, for the hash pass. */
    public List<long[]> chunkList() {
        List<long[]> all = new ArrayList<>(chunks.size());
        chunks.forEach(key -> all.add(new long[] {
                (key >>> (COORD_BITS * 2)),
                unpackX(key),
                unpackZ(key)}));
        return all;
    }

    public String worldOf(long worldIndex) {
        int index = (int) worldIndex;
        return index >= 0 && index < worldNames.size() ? worldNames.get(index) : null;
    }

    private static long pack(int worldIndex, int chunkX, int chunkZ) {
        return ((long) worldIndex << (COORD_BITS * 2))
                | ((chunkX & COORD_MASK) << COORD_BITS)
                | (chunkZ & COORD_MASK);
    }

    private static int unpackX(long key) {
        return sign((int) ((key >> COORD_BITS) & COORD_MASK));
    }

    private static int unpackZ(long key) {
        return sign((int) (key & COORD_MASK));
    }

    private static int sign(int value) {
        int shift = 32 - COORD_BITS;
        return (value << shift) >> shift;
    }
}
