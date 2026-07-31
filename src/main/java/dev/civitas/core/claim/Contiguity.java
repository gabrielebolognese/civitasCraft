package dev.civitas.core.claim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The SPEC 6.1 contiguity invariant: a city's non-outpost claims must form a single
 * edge-connected component.
 *
 * <p>Enforced on <strong>unclaim</strong>, not only on claim. Adjacency alone guarantees a
 * city grows connected, but says nothing about it staying connected: removing a chunk from
 * the middle of a corridor splits the city in two, and SPEC 6.1 requires that to be refused
 * with the orphaned chunks named.
 *
 * <p>Per SPEC 20 decision 4, connectivity is judged <em>per world</em> from the core's world.
 * A city with land in two worlds is not one connected blob and was never meant to be.
 *
 * <p>Pure functions over chunk coordinates, with no dependency on the registry or the
 * database, so the SPEC 18.1 shapes can be tested directly.
 */
public final class Contiguity {

    private Contiguity() {
    }

    /** A chunk position, used as the unit of the flood-fill. */
    public record Chunk(int x, int z) {

        public boolean isEdgeAdjacentTo(Chunk other) {
            return ChunkKey.isEdgeAdjacent(x, z, other.x(), other.z());
        }

        @Override
        public String toString() {
            return x + "," + z;
        }
    }

    /**
     * Whether {@code candidate} may be added, SPEC 6.1.
     *
     * @param existing the city's current non-outpost chunks in the same world
     * @return true if the candidate shares an edge with at least one of them; a city with no
     *         chunks yet always passes, because its first chunk is the core seed
     */
    public static boolean isAdjacent(Collection<Chunk> existing, Chunk candidate) {
        if (existing.isEmpty()) {
            return true;
        }
        for (Chunk chunk : existing) {
            if (chunk.isEdgeAdjacentTo(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether removing {@code removed} leaves the rest connected to {@code core}.
     *
     * <p>Implemented exactly as SPEC 6.1 describes: flood-fill from the core over the claim
     * set minus the candidate, then compare the visited count to {@code claims.size() - 1}.
     *
     * @param all     every non-outpost chunk of the city in the core's world, including the core
     * @param core    the core chunk, which anchors the fill
     * @param removed the chunk being given up
     * @return true if what remains is still one component
     */
    public static boolean remainsConnected(Set<Chunk> all, Chunk core, Chunk removed) {
        return orphansAfterRemoving(all, core, removed).isEmpty();
    }

    /**
     * The chunks that would be cut off by removing {@code removed}.
     *
     * <p>Returning the orphans rather than a boolean is what lets the refusal message show
     * the player which of their land they were about to strand, which SPEC 6.1 asks for.
     *
     * @return the stranded chunks, empty if the city stays whole
     */
    public static List<Chunk> orphansAfterRemoving(Set<Chunk> all, Chunk core, Chunk removed) {
        if (removed.equals(core)) {
            // The core is never unclaimable (SPEC 6.4), so this is a caller error rather
            // than a contiguity answer. Report every other chunk as orphaned, which is the
            // truthful answer to "what happens if the anchor goes".
            List<Chunk> everythingElse = new ArrayList<>(all);
            everythingElse.remove(core);
            return everythingElse;
        }

        Set<Chunk> remaining = new LinkedHashSet<>(all);
        if (!remaining.remove(removed)) {
            return List.of();
        }
        if (remaining.isEmpty()) {
            return List.of();
        }

        Set<Chunk> reached = floodFill(remaining, core);

        List<Chunk> orphans = new ArrayList<>();
        for (Chunk chunk : remaining) {
            if (!reached.contains(chunk)) {
                orphans.add(chunk);
            }
        }
        return orphans;
    }

    /**
     * Whether a whole set is one edge-connected component anchored at {@code core}.
     *
     * <p>Used by the admin contiguity repair in SPEC 9.4.3 and to verify a city loaded from
     * storage is not already broken.
     */
    public static boolean isConnected(Set<Chunk> all, Chunk core) {
        if (all.isEmpty()) {
            return true;
        }
        if (!all.contains(core)) {
            return false;
        }
        return floodFill(all, core).size() == all.size();
    }

    /**
     * Breadth-first fill from {@code start} over edge-adjacent members of {@code within}.
     *
     * <p>Neighbours are computed and looked up rather than found by scanning the set, so the
     * cost is proportional to the number of chunks rather than to their number squared. SPEC
     * 6.1 says this is cheap enough to run synchronously under ~2,000 chunks; at four hash
     * lookups per chunk it is comfortably cheaper than that.
     */
    private static Set<Chunk> floodFill(Set<Chunk> within, Chunk start) {
        Set<Chunk> reached = new LinkedHashSet<>();
        if (!within.contains(start)) {
            return reached;
        }

        Deque<Chunk> queue = new ArrayDeque<>();
        queue.add(start);
        reached.add(start);

        while (!queue.isEmpty()) {
            Chunk current = queue.removeFirst();
            for (Chunk neighbour : neighboursOf(current)) {
                if (within.contains(neighbour) && reached.add(neighbour)) {
                    queue.addLast(neighbour);
                }
            }
        }
        return reached;
    }

    /** The four edge-sharing neighbours. Diagonals are deliberately absent, SPEC 6.1. */
    static List<Chunk> neighboursOf(Chunk chunk) {
        return List.of(
                new Chunk(chunk.x() + 1, chunk.z()),
                new Chunk(chunk.x() - 1, chunk.z()),
                new Chunk(chunk.x(), chunk.z() + 1),
                new Chunk(chunk.x(), chunk.z() - 1));
    }
}
