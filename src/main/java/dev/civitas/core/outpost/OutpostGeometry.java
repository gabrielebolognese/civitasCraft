package dev.civitas.core.outpost;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The shape rules for SPEC 39's multi-chunk outposts.
 *
 * <p>Pure: everything here takes chunk coordinates and returns an answer, so the whole of SPEC
 * 39.6 and 39.7 is testable without a server, a city or a database. That matters because merging
 * is the part of this rework most likely to be got subtly wrong — SPEC 39.7 has two directions
 * and one of them <b>blocks a claim</b> rather than performing it.
 */
public final class OutpostGeometry {

    private OutpostGeometry() {
    }

    /** One chunk. Equality by value, so these work as set members. */
    public record Chunk(int x, int z) {

        /** Whether this chunk shares an edge with {@code other}. Corners do not count. */
        public boolean touches(Chunk other) {
            int dx = Math.abs(x - other.x);
            int dz = Math.abs(z - other.z);
            return dx + dz == 1;
        }

        /** Chebyshev distance in chunks, the measure SPEC 6.2 and 39.6 both use. */
        public int chebyshev(Chunk other) {
            return Math.max(Math.abs(x - other.x), Math.abs(z - other.z));
        }
    }

    // ==================================================================================
    // SPEC 39.6, internal contiguity
    // ==================================================================================

    /**
     * Whether a set of chunks forms one edge-connected piece.
     *
     * <p>SPEC 39.6: "All chunks of one outpost must be edge-connected — an outpost is a place,
     * not scattered tiles." The same flood-fill SPEC 6.1 uses for a city body, applied inside an
     * outpost, which is what SPEC 18.1's contiguity line now covers here as well.
     *
     * <p>An empty set is contiguous, and so is a single chunk: neither is scattered.
     */
    public static boolean isContiguous(Collection<Chunk> chunks) {
        if (chunks.size() <= 1) {
            return true;
        }
        Set<Chunk> remaining = new HashSet<>(chunks);
        Chunk start = remaining.iterator().next();

        Deque<Chunk> queue = new ArrayDeque<>();
        queue.add(start);
        remaining.remove(start);

        while (!queue.isEmpty()) {
            Chunk at = queue.poll();
            for (Chunk neighbour : neighbours(at)) {
                if (remaining.remove(neighbour)) {
                    queue.add(neighbour);
                }
            }
        }
        return remaining.isEmpty();
    }

    /**
     * Whether removing {@code candidate} would leave the rest in one piece.
     *
     * <p>SPEC 39.14 case 133: "Unclaiming an outpost chunk would split the outpost in two —
     * rejected, same contiguity logic as SPEC 6.1 applied within the outpost."
     */
    public static boolean survivesRemoval(Collection<Chunk> chunks, Chunk candidate) {
        Set<Chunk> without = new HashSet<>(chunks);
        without.remove(candidate);
        return isContiguous(without);
    }

    /** Whether {@code candidate} would extend {@code chunks} rather than float beside them. */
    public static boolean extendsOutpost(Collection<Chunk> chunks, Chunk candidate) {
        if (chunks.isEmpty()) {
            return true;
        }
        return chunks.stream().anyMatch(chunk -> chunk.touches(candidate));
    }

    private static List<Chunk> neighbours(Chunk at) {
        return List.of(
                new Chunk(at.x() + 1, at.z()),
                new Chunk(at.x() - 1, at.z()),
                new Chunk(at.x(), at.z() + 1),
                new Chunk(at.x(), at.z() - 1));
    }

    // ==================================================================================
    // SPEC 39.6, placement distances
    // ==================================================================================

    /**
     * The shortest Chebyshev distance in chunks from {@code candidate} to any of {@code others}.
     *
     * <p>{@link Integer#MAX_VALUE} when there is nothing to measure against, so a first outpost
     * is never refused for being too close to outposts a city does not have.
     */
    public static int nearestDistance(Chunk candidate, Collection<Chunk> others) {
        int nearest = Integer.MAX_VALUE;
        for (Chunk other : others) {
            nearest = Math.min(nearest, candidate.chebyshev(other));
        }
        return nearest;
    }

    /**
     * Whether {@code candidate} is at least {@code minimum} chunks from all of {@code others}.
     *
     * <p>A minimum of zero or less passes anything, which is how an operator switches one of
     * SPEC 39.6's three distances off.
     */
    public static boolean isAtLeast(int minimum, Chunk candidate, Collection<Chunk> others) {
        return minimum <= 0 || nearestDistance(candidate, others) >= minimum;
    }

    // ==================================================================================
    // SPEC 39.7, merging
    // ==================================================================================

    /**
     * What claiming {@code candidate} would do to a city's shape.
     *
     * <p>SPEC 39.7 has three outcomes and they are easy to conflate, so they are named:
     *
     * <ul>
     *   <li>{@link Merge#NONE} — nothing touches, the claim is an ordinary one.
     *   <li>{@link Merge#INTO_CITY} — the chunk bridges an outpost to the city body, so "the
     *       entire outpost merges into the city", every chunk becomes NORMAL, the slot frees and
     *       nothing is refunded.
     *   <li>{@link Merge#OUTPOSTS} — it bridges two of the city's own outposts, which merge
     *       keeping the older one's name and warp.
     *   <li>{@link Merge#BLOCKED_TOO_LARGE} — the same bridge, but the result would exceed the
     *       four-chunk maximum. SPEC 39.7 is explicit that this <b>blocks the claim</b> rather
     *       than merging and truncating.
     * </ul>
     */
    public enum Merge {
        NONE,
        INTO_CITY,
        OUTPOSTS,
        BLOCKED_TOO_LARGE
    }

    /**
     * Works out which of those a claim would cause.
     *
     * @param candidate    the chunk about to be claimed
     * @param cityBody     the city's non-outpost chunks
     * @param outposts     each of the city's outposts, as its own set of chunks
     * @param maxChunks    SPEC 39.6's four
     */
    public static Merge judge(Chunk candidate, Collection<Chunk> cityBody,
                              List<? extends Collection<Chunk>> outposts, int maxChunks) {
        boolean touchesCity = extendsAny(candidate, cityBody);

        List<Collection<Chunk>> touched = outposts.stream()
                .filter(outpost -> extendsAny(candidate, outpost))
                .map(outpost -> (Collection<Chunk>) outpost)
                .toList();

        if (touchesCity && !touched.isEmpty()) {
            // The chunk bridges the body and an outpost. SPEC 39.7 absorbs the outpost whole,
            // and its size is irrelevant because the result is city land rather than an outpost.
            return Merge.INTO_CITY;
        }
        if (touched.size() >= 2) {
            int combined = 1 + touched.stream().mapToInt(Collection::size).sum();
            return combined > maxChunks ? Merge.BLOCKED_TOO_LARGE : Merge.OUTPOSTS;
        }
        return Merge.NONE;
    }

    /**
     * Whether an existing outpost has come to border the city body without a new claim.
     *
     * <p>SPEC 39.14 case 132: a city expands toward its own outpost until they touch. The
     * bridging claim is a <b>city</b> claim, so {@link #judge} sees it from the other side —
     * this is the check the claim path runs after the fact.
     */
    public static boolean bordersCity(Collection<Chunk> outpost, Collection<Chunk> cityBody) {
        for (Chunk chunk : outpost) {
            if (extendsAny(chunk, cityBody)) {
                return true;
            }
        }
        return false;
    }

    private static boolean extendsAny(Chunk candidate, Collection<Chunk> chunks) {
        return chunks.stream().anyMatch(chunk -> chunk.touches(candidate));
    }
}
