package dev.civitas.core.admin;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.storage.dao.ProtectedChunkDao;
import dev.civitas.storage.row.ProtectedChunkRow;

/**
 * SPEC 9.4.3's admin-protected chunks: "unclaimable, unbuildable, war-immune".
 *
 * <h2>The seam this closes</h2>
 * {@code ClaimService.isAdminProtected} has answered {@code false} since M3, which was honest
 * at the time — the command that would set it was assigned to this milestone. It is the last
 * seam in the plugin.
 *
 * <p>Three separate rules hang off one answer, and they are not the same rule:
 * <ul>
 *   <li><b>Unclaimable</b> — SPEC 6.3 precondition 10, checked when a city tries to expand.</li>
 *   <li><b>Unbuildable</b> — nobody places or breaks anything here, member or not. This is
 *       stronger than a claim, which is what makes it useful for a spawn area.</li>
 *   <li><b>War-immune</b> — SPEC 11.6 lists admin-protected chunks alongside the City Hall and
 *       defense unit spawners among the three things that stay protected <em>during</em> a war.
 *       Without this, a protected build inside a war zone would be flattened and restored,
 *       which is not the same as never being touched.</li>
 * </ul>
 *
 * <h2>Cache-first, like every other hot lookup</h2>
 * This is asked on every block event and on every claim attempt, so it lives in memory and the
 * database is a persistence target (SPEC 2.3). The set is tiny on any real server — a spawn
 * area and a few landmarks — so a {@code Set} of packed keys costs nothing and answers in
 * constant time.
 */
public final class AdminProtection {

    private final ProtectedChunkDao dao;
    private final Logger logger;

    /** {@code world:chunkX:chunkZ}, which needs no index and cannot collide. */
    private final Set<String> protectedChunks = ConcurrentHashMap.newKeySet();

    public AdminProtection(ProtectedChunkDao dao, Logger logger) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Loads the set at startup. */
    public CompletableFuture<Integer> loadAll() {
        return dao.findAll().thenApply(rows -> {
            protectedChunks.clear();
            for (ProtectedChunkRow row : rows) {
                remember(row.world(), row.chunkX(), row.chunkZ());
            }
            return rows.size();
        }).exceptionally(error -> {
            // Failing open here would silently unprotect a spawn area, so it is loud.
            logger.log(Level.SEVERE, "Could not load the admin-protected chunks. Protection "
                    + "for them is NOT in effect until this is resolved.", error);
            return 0;
        });
    }

    /**
     * Whether this chunk is protected.
     *
     * <p>The hot path. An out-of-range coordinate answers false rather than throwing: this is
     * consulted from block events, and a thrown exception there would cancel the event and
     * make the plugin look like it was blocking a legitimate action.
     */
    public boolean isProtected(String world, int chunkX, int chunkZ) {
        return !protectedChunks.isEmpty() && protectedChunks.contains(key(world, chunkX, chunkZ));
    }

    /** Whether the chunk containing a block is protected. */
    public boolean isProtectedAtBlock(String world, int blockX, int blockZ) {
        return isProtected(world, blockX >> 4, blockZ >> 4);
    }

    /**
     * Protects a chunk.
     *
     * <p>The cache is updated only once the row is stored, so a failed write cannot leave the
     * server enforcing a protection that will not survive a restart. That direction matters
     * more than the reverse: an admin who is told it worked and finds it gone tomorrow has
     * lost the build they were protecting.
     */
    public CompletableFuture<Boolean> protect(String world, int chunkX, int chunkZ, UUID by,
                                              String reason) {
        return dao.protect(new ProtectedChunkRow(world, chunkX, chunkZ, by,
                        System.currentTimeMillis(), reason))
                .thenApply(written -> {
                    if (written > 0) {
                        remember(world, chunkX, chunkZ);
                    }
                    // Zero written means it was already protected, which is not a failure.
                    return isProtected(world, chunkX, chunkZ);
                });
    }

    public CompletableFuture<Boolean> unprotect(String world, int chunkX, int chunkZ) {
        return dao.unprotect(world, chunkX, chunkZ).thenApply(removed -> {
            if (removed > 0) {
                protectedChunks.remove(key(world, chunkX, chunkZ));
            }
            return removed > 0;
        });
    }

    /** What is protected, for {@code /ca claim info}. */
    public CompletableFuture<Optional<ProtectedChunkRow>> details(String world, int chunkX,
                                                                  int chunkZ) {
        return dao.findAll().thenApply(rows -> rows.stream()
                .filter(row -> row.world().equals(world) && row.chunkX() == chunkX
                        && row.chunkZ() == chunkZ)
                .findFirst());
    }

    public int count() {
        return protectedChunks.size();
    }

    private void remember(String world, int chunkX, int chunkZ) {
        protectedChunks.add(key(world, chunkX, chunkZ));
    }

    private static String key(String world, int chunkX, int chunkZ) {
        return world + ':' + chunkX + ':' + chunkZ;
    }
}
