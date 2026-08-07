package dev.civitas.core.war;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.storage.dao.WarBlockLogDao;
import dev.civitas.storage.row.WarBlockLogRow;

/**
 * SPEC 17.4 case 51, fixed where it is cheap to fix: at war start.
 *
 * <h2>The corruption this prevents</h2>
 * SPEC calls two geographically overlapping wars "the most likely source of a corrupt restore",
 * and the reason is sharper than it first looks. Each war's log records, per position, the
 * state before <em>that war</em> first touched it. When a second war starts on ground a first
 * war has already been fighting over, the second war's first record of a position is whatever
 * the first war had already left there.
 *
 * <p>So this happens, with both rollbacks behaving exactly as designed:
 *
 * <pre>
 *   war A starts.                       P is stone.
 *   an attacker breaks P.               A logs old=stone. P is air.
 *   war B starts; P is now in both.
 *   somebody places dirt at P.          A and B both log old=air.
 *   A ends and rolls back.              P returns to stone. Correct.
 *   B ends and rolls back.              B's oldest entry says air. P becomes a hole.
 * </pre>
 *
 * <p>B is not wrong about its own war. It is wrong about the world, because the state a
 * position must end at is the one before the <em>earliest</em> war that covered it.
 *
 * <h2>Why the fix belongs here and not in the replay</h2>
 * The alternative is to teach the replay to consult other wars' logs, which means a join across
 * the highest-write-volume table in the plugin, on every position, during the one operation
 * SPEC 11.1 says must be reliable above all else. Seeding instead makes B's log <em>complete</em>
 * — its oldest entry becomes the true pre-war state — and leaves the replay untouched. The
 * rollback engine needs no knowledge that overlapping wars exist.
 *
 * <p>It is also the cheaper moment. This runs once, when a war becomes ACTIVE, on the async
 * pool, and only when zones actually overlap; the replay runs hundreds of times a second while
 * players wait for their city back.
 *
 * <p>Damage the older war does <em>after</em> the second war starts needs no seeding: the shared
 * ground is inside both zones by then, so M17 logs it to both.
 */
public final class OverlapSeeder {

    private final WarBlockLogDao log;
    private final WarRegistry registry;
    private final Logger logger;

    public OverlapSeeder(WarBlockLogDao log, WarRegistry registry, Logger logger) {
        this.log = Objects.requireNonNull(log, "log");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Copies what older wars already know about the ground this one is about to be fought over.
     *
     * <p>Called when a war becomes ACTIVE, after its zone is computed and before anything can
     * be logged against it, so the copied rows take the lowest sequence numbers and the replay
     * reaches them last.
     *
     * @return how many rows were seeded, which is zero for the overwhelmingly common case of a
     *         war that shares ground with nothing
     */
    public CompletableFuture<Integer> seed(War starting) {
        List<War> older = overlappingOlderWars(starting);
        if (older.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        // Seeded rows take negative sequence numbers, below anything this war will log for
        // itself, so the replay reaches them last and their state is the one that survives.
        //
        // The list runs newest war first, which looks backwards and is not: the counter only
        // descends, so the war processed last gets the most negative numbers. Processing the
        // oldest war last therefore gives its history the final say, which is the whole point.
        java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
        CompletableFuture<Integer> total = CompletableFuture.completedFuture(0);
        for (War old : older) {
            for (String world : starting.zone().worlds()) {
                Bounds bounds = sharedBounds(starting, old, world);
                if (bounds == null) {
                    continue;
                }
                total = total.thenCombine(seedFrom(starting, old, world, bounds, sequence),
                        Integer::sum);
            }
        }
        return total.exceptionally(error -> {
            logger.log(Level.SEVERE, "Could not seed war " + starting.id()
                    + " from the overlapping wars already running. Its rollback may restore "
                    + "damage those wars did before it started.", error);
            return 0;
        });
    }

    private CompletableFuture<Integer> seedFrom(War starting, War old, String world,
                                                Bounds bounds,
                                                java.util.concurrent.atomic.AtomicLong sequence) {
        try {
            return log.oldestPerPositionIn(old.id(), world, bounds.minX(), bounds.maxX(),
                            bounds.minZ(), bounds.maxZ())
                    .thenCompose(rows -> {
                        List<WarBlockLogRow> seeds = new ArrayList<>();
                        for (WarBlockLogRow row : rows) {
                            // The bounding box is a rectangle; the zone is not. Positions
                            // inside the box but outside the new war's zone are not its
                            // business and would make its log claim ground it never covered.
                            if (!starting.zone().containsBlock(world, row.x(), row.z())) {
                                continue;
                            }
                            seeds.add(new WarBlockLogRow(0, starting.id(),
                                    sequence.decrementAndGet(),
                                    row.world(), row.x(), row.y(), row.z(),
                                    row.oldBlockData(), row.newBlockData(), row.oldNbt(),
                                    row.actorUuid(), row.timestamp()));
                        }
                        if (seeds.isEmpty()) {
                            return CompletableFuture.completedFuture(0);
                        }
                        logger.info("Seeding war " + starting.id() + " with " + seeds.size()
                                + " pre-existing state(s) from the overlapping war " + old.id()
                                + ", so its rollback restores the ground as it was before "
                                + "either war (SPEC 17.4 case 51).");
                        return log.insertBatch(seeds);
                    });
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not seed war " + starting.id() + " from war "
                    + old.id(), e);
            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * Wars already fighting over ground this one is about to cover.
     *
     * <p>Only wars that started earlier. One that starts later will be seeded from this one in
     * its turn, and the earliest war of all is the one whose log reaches furthest back.
     */
    List<War> overlappingOlderWars(War starting) {
        List<War> older = new ArrayList<>();
        for (War other : registry.all()) {
            if (other.id() == starting.id() || other.state() != WarState.ACTIVE) {
                continue;
            }
            if (other.prepEndsAt() >= starting.prepEndsAt()) {
                continue;
            }
            if (sharesGround(starting, other)) {
                older.add(other);
            }
        }
        // Newest first: see seed() for why the deepest history has to be written last.
        older.sort(java.util.Comparator.comparingLong(War::prepEndsAt).reversed());
        return older;
    }

    private static boolean sharesGround(War one, War other) {
        for (long[] chunk : one.zone().chunkList()) {
            String world = one.zone().worldOf(chunk[0]);
            if (world != null && other.zone().containsChunk(world, (int) chunk[1],
                    (int) chunk[2])) {
                return true;
            }
        }
        return false;
    }

    /** The block range covering every chunk the two wars share in one world. */
    private static Bounds sharedBounds(War one, War other, String world) {
        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;

        for (long[] chunk : one.zone().chunkList()) {
            if (!world.equals(one.zone().worldOf(chunk[0]))) {
                continue;
            }
            int chunkX = (int) chunk[1];
            int chunkZ = (int) chunk[2];
            if (!other.zone().containsChunk(world, chunkX, chunkZ)) {
                continue;
            }
            minChunkX = Math.min(minChunkX, chunkX);
            maxChunkX = Math.max(maxChunkX, chunkX);
            minChunkZ = Math.min(minChunkZ, chunkZ);
            maxChunkZ = Math.max(maxChunkZ, chunkZ);
        }
        if (minChunkX == Integer.MAX_VALUE) {
            return null;
        }
        return new Bounds(minChunkX << 4, (maxChunkX << 4) + 15,
                minChunkZ << 4, (maxChunkZ << 4) + 15);
    }

    /** A block-coordinate rectangle, used to keep the seeding query off the whole log. */
    record Bounds(int minX, int maxX, int minZ, int maxZ) { }
}
