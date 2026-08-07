package dev.civitas.core.war;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.storage.dao.WarContainerLogDao;
import dev.civitas.storage.row.WarContainerLogRow;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * SPEC 11.7's container log: what was taken, by whom, from where.
 *
 * <h2>This log does not undo anything</h2>
 * SPEC 11.7 is explicit and deliberately harsh: "Items removed from containers during war are
 * NOT returned by rollback." It is the one stated exception to SPEC 1.2, and it exists so that
 * a war has material stakes and a raid has a point. This log is therefore evidence, not a
 * restore path — SPEC 11.7 says it is kept "purely for the post-war report and for admin
 * dispute resolution".
 *
 * <p>SPEC 17.4 case 44 is the asymmetry it documents: <b>destroying storage is pointless,
 * looting it is not.</b> A chest broken in war drops nothing and comes back with its contents
 * (SPEC 17.4 case 43); a chest opened by hand and emptied stays empty.
 *
 * <h2>Diffed across the open, not counted per click</h2>
 * The contents are snapshotted when the container is opened and compared when it is closed.
 * Counting clicks instead would mean handling shift-click, number-key swaps, drags, the
 * double-click sweep and the cursor stack separately, and getting any one of them wrong would
 * silently under-report a theft. A diff cannot miss a route, because it does not know or care
 * which route was taken.
 *
 * <p>Only net removals are recorded. A player who puts a stack in and takes it out again has
 * stolen nothing, and a swap logs the item that left rather than the one that arrived.
 */
public final class WarLootLog {

    private final WarContainerLogDao dao;
    private final Logger logger;

    /** What each open container held when it was opened, per viewer. */
    private final Map<UUID, Snapshot> open = new ConcurrentHashMap<>();

    /** Rows waiting to be written, drained on the flush tick. */
    private final List<WarContainerLogRow> pending = new ArrayList<>();

    public WarLootLog(WarContainerLogDao dao, Logger logger) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** What a container held, and where, at the moment somebody opened it. */
    private record Snapshot(List<Integer> warIds, String world, int x, int y, int z,
                            Map<String, Integer> contents) { }

    /**
     * Remembers a container's contents.
     *
     * @param warIds the wars whose zone this container is inside; nothing is remembered if empty
     */
    public void opened(UUID viewer, List<Integer> warIds, Location at, Inventory inventory) {
        if (warIds.isEmpty()) {
            return;
        }
        open.put(viewer, new Snapshot(List.copyOf(warIds), at.getWorld().getName(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ(), count(inventory)));
    }

    /**
     * Compares against the snapshot and records whatever left.
     *
     * @return how many distinct materials were taken
     */
    public int closed(UUID viewer, Inventory inventory, long now) {
        Snapshot before = open.remove(viewer);
        if (before == null) {
            return 0;
        }
        Map<String, Integer> after = count(inventory);

        Map<String, Integer> taken = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : before.contents().entrySet()) {
            int missing = entry.getValue() - after.getOrDefault(entry.getKey(), 0);
            if (missing > 0) {
                taken.put(entry.getKey(), missing);
            }
        }
        if (taken.isEmpty()) {
            return 0;
        }

        synchronized (pending) {
            for (Map.Entry<String, Integer> entry : taken.entrySet()) {
                // One row per war: a chunk can sit inside two overlapping zones (SPEC 17.4
                // case 51), and each war's report should show what happened inside it.
                for (int warId : before.warIds()) {
                    pending.add(new WarContainerLogRow(0, warId, before.world(), before.x(),
                            before.y(), before.z(), viewer, entry.getKey(), entry.getValue(),
                            now));
                }
            }
        }
        return taken.size();
    }

    /** Forgets a viewer without recording anything, for a disconnect mid-open. */
    public void forget(UUID viewer) {
        open.remove(viewer);
    }

    /**
     * Writes what has accumulated.
     *
     * <p>Async, and tolerant of a dead database in both shapes: {@code DatabaseManager.call}
     * throws synchronously when the pool is closed, so the throw is caught as well as the
     * failed future. This log is evidence rather than state, so a lost batch costs an admin
     * some detail and costs the game nothing.
     */
    public CompletableFuture<Integer> flush() {
        List<WarContainerLogRow> batch;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            batch = List.copyOf(pending);
            pending.clear();
        }
        try {
            return dao.insertBatch(batch).exceptionally(error -> {
                logger.log(Level.WARNING, "Could not write " + batch.size()
                        + " war container log row(s).", error);
                return 0;
            });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not write " + batch.size()
                    + " war container log row(s).", e);
            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * Flushes and waits, for {@code onDisable} and for tests.
     *
     * <p>Unlike the block log's blocking flush, losing this batch would cost an admin some
     * detail rather than a city its buildings, so this waits politely and gives up rather than
     * holding the shutdown open.
     */
    public int flushBlocking() {
        try {
            return flush().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException e) {
            logger.log(Level.WARNING, "Could not flush the war container log.", e);
            return 0;
        }
    }

    /** How many rows are waiting, for tests and for {@code /ca perf}. */
    public int pendingCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    /** How many containers are being watched right now. */
    public int watching() {
        return open.size();
    }

    private static Map<String, Integer> count(Inventory inventory) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack stack : inventory.getContents()) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            counts.merge(stack.getType().name(), stack.getAmount(), Integer::sum);
        }
        return counts;
    }
}
