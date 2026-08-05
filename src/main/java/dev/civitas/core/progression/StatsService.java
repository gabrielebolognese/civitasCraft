package dev.civitas.core.progression;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.storage.dao.PlayerStatDao;
import org.bukkit.Location;

/**
 * Keeps the lifetime counters SPEC 13.3's Builder and Farmer boards rank.
 *
 * <h2>Why this buffers</h2>
 * A counter moves on every block a player places. Writing that straight through would be one
 * database round trip per block, which on a server with twenty people building is thousands
 * of statements a minute for a number nobody reads until the leaderboard refreshes. So
 * increments accumulate in memory and are flushed in one transaction on a timer, in the same
 * shape SPEC 11.8.1 specifies for the war block log and for the same reason.
 *
 * <p>A failed flush puts its batch back rather than dropping it, and the plugin flushes once
 * more on disable. Losing a few blocks off a career total is not a disaster, but silently
 * losing them on every restart would make the board wrong in a way nobody could explain.
 */
public final class StatsService {

    private final PlayerStatDao dao;
    private final Logger logger;

    /** Guards {@link #pending}. Held only for the map operation, never across a query. */
    private final Object lock = new Object();

    private Map<UUID, EnumMap<PlayerStat, Long>> pending = new HashMap<>();

    public StatsService(PlayerStatDao dao, Logger logger) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Counts something a player did.
     *
     * <p>Called from the event path, so it does no I/O and takes no lock a query could be
     * waiting behind. Non-positive amounts are ignored: these counters only go up, and a
     * negative increment would be a way to rank by undoing work.
     */
    public void record(UUID player, PlayerStat stat, long amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(stat, "stat");
        if (amount <= 0L) {
            return;
        }
        synchronized (lock) {
            pending.computeIfAbsent(player, ignored -> new EnumMap<>(PlayerStat.class))
                    .merge(stat, amount, Long::sum);
        }
    }

    /**
     * Counts a block placement, unless it happened inside a war zone.
     *
     * <p>SPEC 13.3 defines the Builder metric as "blocks placed (excluding war zones)", which
     * exists so that demolishing and rebuilding an enemy city during a war cannot be farmed
     * into the top of a peacetime leaderboard.
     */
    public void recordPlacement(UUID player, Location location) {
        if (isInWarZone(location)) {
            return;
        }
        record(player, PlayerStat.BLOCKS_PLACED, 1L);
    }

    /** How much is buffered and not yet written. For tests and for {@code /ca perf}. */
    public int pendingPlayers() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /**
     * Writes everything buffered.
     *
     * @return how many statements were applied, zero if there was nothing to write
     */
    public CompletableFuture<Integer> flush(long now) {
        Map<UUID, EnumMap<PlayerStat, Long>> drained = drain();
        if (drained.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        try {
            return dao.addAll(toKeyed(drained), now).exceptionally(error -> {
                // Put it back. The next flush retries, and the disable flush is the last chance.
                restore(drained);
                logger.log(Level.WARNING,
                        "Could not flush player stats; retrying on the next sweep.", error);
                return 0;
            });
        } catch (RuntimeException e) {
            // Not every failure arrives as a failed future. A closed pool throws from the call
            // itself, before there is a future to fail, and without this the batch drained
            // above would be dropped on the floor rather than retried.
            restore(drained);
            logger.log(Level.WARNING, "Could not flush player stats; retrying on the next sweep.", e);
            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * Writes everything buffered and waits, for {@code onDisable}.
     *
     * <p>Blocking is correct here for the reason SPEC 17.7 case 84 gives about the war log:
     * there is no later opportunity, and the alternative is losing the buffer on every clean
     * shutdown.
     */
    public void flushBlocking(long now) {
        try {
            flush(now).join();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Final player-stats flush failed; counters may be short.", e);
        }
    }

    private Map<UUID, EnumMap<PlayerStat, Long>> drain() {
        synchronized (lock) {
            if (pending.isEmpty()) {
                return Map.of();
            }
            Map<UUID, EnumMap<PlayerStat, Long>> drained = pending;
            pending = new HashMap<>();
            return drained;
        }
    }

    /** Merges a failed batch back in, behind anything recorded while it was in flight. */
    private void restore(Map<UUID, EnumMap<PlayerStat, Long>> failed) {
        synchronized (lock) {
            for (Map.Entry<UUID, EnumMap<PlayerStat, Long>> player : failed.entrySet()) {
                EnumMap<PlayerStat, Long> counters =
                        pending.computeIfAbsent(player.getKey(),
                                ignored -> new EnumMap<>(PlayerStat.class));
                player.getValue().forEach((stat, amount) -> counters.merge(stat, amount, Long::sum));
            }
        }
    }

    private static Map<UUID, Map<String, Long>> toKeyed(
            Map<UUID, EnumMap<PlayerStat, Long>> source) {
        Map<UUID, Map<String, Long>> keyed = new HashMap<>();
        source.forEach((player, counters) -> {
            Map<String, Long> byName = new HashMap<>();
            counters.forEach((stat, amount) -> byName.put(stat.key(), amount));
            keyed.put(player, byName);
        });
        return keyed;
    }

    // ==================================================================================
    // Seams for milestones that do not exist yet
    // ==================================================================================

    /**
     * SPEC 13.3: whether this location is inside an active war zone, whose placements the
     * Builder board excludes.
     *
     * <p>Always false until M19 computes zones. Written out so the exclusion already has its
     * branch and M19 changes one method rather than remembering that a leaderboard depended
     * on it.
     */
    private boolean isInWarZone(Location location) {
        return false;
    }
}
