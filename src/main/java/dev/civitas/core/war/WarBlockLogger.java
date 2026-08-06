package dev.civitas.core.war;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.dao.WarBlockLogDao;
import dev.civitas.storage.row.WarBlockLogRow;

/**
 * The write side of the rollback engine, SPEC 11.8.1.
 *
 * <h2>What this promises</h2>
 * Every block change inside a war zone becomes a row saying what the block was, what it
 * became, and who did it. M18 replays those rows backwards to undo a war. The promise is
 * therefore blunt: <strong>a change that is not logged can never be undone</strong>. That is
 * why the failure policy below prefers refusing grief to losing a record.
 *
 * <h2>Buffering</h2>
 * SPEC 11.8.1 wants 2,000 changes a second with no main-thread cost, which one insert per
 * block cannot reach. Entries land in an in-memory queue and are flushed in batches, every
 * {@code flush-interval-seconds} or every {@code flush-batch-size} entries, whichever comes
 * first. {@link #record} does no I/O, takes no lock a query waits behind, and allocates one
 * row.
 *
 * <h2>When it cannot keep up</h2>
 * Three limits, all from SPEC, all resolving the same way:
 * <ul>
 *   <li>SPEC 17.7 case 85: the database is gone. Entries buffer up to
 *       {@code max-buffered-entries} (100,000) and the batch is retried. Beyond that the
 *       logger reports itself unable to accept work.</li>
 *   <li>SPEC 17.4 case 58: a war's log reaches {@code max-rows-per-war} (5,000,000). Admins
 *       are warned at 80%, and at 100% the logger stops accepting.</li>
 *   <li>SPEC 17.7 case 84: the plugin is disabling. The buffer is flushed synchronously,
 *       because there is no later opportunity.</li>
 * </ul>
 * In the first two cases {@link #isAcceptingChanges} turns false and the listeners cancel the
 * event instead. SPEC 17.4 case 58 states the reasoning outright: "Correctness over
 * gameplay." A player who cannot break a block is annoyed; a player whose house does not come
 * back has quit.
 */
public final class WarBlockLogger {

    private final WarBlockLogDao dao;
    private final TilePayloadCodec codec;
    private final ConfigManager configs;
    private final Logger logger;

    /** Guards {@link #buffer}. Held only to add or drain, never across a query. */
    private final Object lock = new Object();

    /** Entries waiting to be written, oldest first. */
    private final Deque<WarBlockLogRow> buffer = new ArrayDeque<>();

    /** Next sequence number per war. SPEC 3.8 makes it monotonic per war. */
    private final Map<Integer, AtomicLong> sequences = new ConcurrentHashMap<>();

    /** Rows already committed per war, for the SPEC 17.4 case 58 ceiling. */
    private final Map<Integer, AtomicLong> written = new ConcurrentHashMap<>();

    /**
     * Entries buffered but not yet written, per war.
     *
     * <p>Counted per war rather than taken from the buffer's size, because SPEC 17.4 case 58's
     * limit is per war: measuring one war's budget against the whole buffer would let a busy
     * war exhaust a quiet one's allowance and refuse grief in a war that had logged nothing.
     */
    private final Map<Integer, AtomicLong> pending = new ConcurrentHashMap<>();

    /** Wars already warned about, so the 80% warning is logged once rather than per block. */
    private final java.util.Set<Integer> warned = ConcurrentHashMap.newKeySet();

    /** Wars whose log is closed because a rollback is replaying it, SPEC 11.8.2 step 2. */
    private final java.util.Set<Integer> frozen = ConcurrentHashMap.newKeySet();

    /** True while a flush is in flight, so two do not race for the same entries. */
    private final AtomicBoolean flushing = new AtomicBoolean();

    /** Set when the buffer or a war's log is full; cleared when the pressure comes off. */
    private final AtomicBoolean refusing = new AtomicBoolean();

    public WarBlockLogger(WarBlockLogDao dao, TilePayloadCodec codec, ConfigManager configs,
                          Logger logger) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public TilePayloadCodec codec() {
        return codec;
    }

    // ==================================================================================
    // Recording
    // ==================================================================================

    /**
     * Records one block change.
     *
     * <p>Called from the event path, on the server thread, potentially thousands of times a
     * second. It does no I/O.
     *
     * @param oldNbt the tile payload from {@link TilePayloadCodec#capture}, or null
     * @return false if the change could not be recorded, in which case the caller must cancel
     *         the event rather than let an unloggable change happen
     */
    public boolean record(int warId, String world, int x, int y, int z,
                          String oldBlockData, String newBlockData, byte[] oldNbt,
                          UUID actor, long timestamp) {
        if (!isAcceptingChanges(warId)) {
            return false;
        }

        long sequence = sequences.computeIfAbsent(warId, ignored -> new AtomicLong())
                .incrementAndGet();
        WarBlockLogRow row = new WarBlockLogRow(0, warId, sequence, world, x, y, z,
                oldBlockData, newBlockData, oldNbt, actor, timestamp);

        synchronized (lock) {
            buffer.addLast(row);
        }
        pending.computeIfAbsent(warId, ignored -> new AtomicLong()).incrementAndGet();
        return true;
    }

    /**
     * Records every block of one explosion in a single call.
     *
     * <p>SPEC 17.4 case 45: a TNT chain can hand over 40,000 blocks in one event, and the
     * instruction is to "log all of them in one batch. Throttle nothing during logging, only
     * during rollback." So this deliberately does not rate-limit; the buffer absorbs the spike
     * and the flush drains it.
     *
     * @return how many were recorded, which is fewer than asked for only if a limit was hit
     */
    public int recordAll(List<PendingChange> changes) {
        int recorded = 0;
        for (PendingChange change : changes) {
            if (!record(change.warId(), change.world(), change.x(), change.y(), change.z(),
                    change.oldBlockData(), change.newBlockData(), change.oldNbt(),
                    change.actor(), change.timestamp())) {
                break;
            }
            recorded++;
        }
        return recorded;
    }

    /** One change on its way into the log. */
    public record PendingChange(int warId, String world, int x, int y, int z,
                                String oldBlockData, String newBlockData, byte[] oldNbt,
                                UUID actor, long timestamp) { }

    // ==================================================================================
    // The limits, SPEC 17.4 case 58 and SPEC 17.7 case 85
    // ==================================================================================

    /**
     * Whether the logger can take another change for this war.
     *
     * <p>The listeners consult this before allowing grief. False means cancel the event.
     */
    public boolean isAcceptingChanges(int warId) {
        if (frozen.contains(warId)) {
            // SPEC 11.8.2 step 2: once a rollback starts, the log takes nothing more for that
            // war. An entry arriving mid-replay would be a change the replay has already gone
            // past, so it would survive the rollback that was meant to undo it.
            return false;
        }
        if (bufferedCount() >= maxBufferedEntries()) {
            if (refusing.compareAndSet(false, true)) {
                logger.severe("The war block log buffer is full (" + maxBufferedEntries()
                        + " entries). Grief is being refused until it drains, because SPEC 17.4 "
                        + "case 58 puts a correct rollback above uninterrupted play.");
            }
            return false;
        }

        long rows = written.computeIfAbsent(warId, ignored -> new AtomicLong()).get()
                + pending.computeIfAbsent(warId, ignored -> new AtomicLong()).get();
        long ceiling = maxRowsPerWar();
        if (ceiling > 0 && rows >= ceiling) {
            if (refusing.compareAndSet(false, true)) {
                logger.severe("War " + warId + " has reached the block log limit of " + ceiling
                        + " rows. Grief is refused for the rest of this war.");
            }
            return false;
        }

        warnIfNearLimit(warId, rows, ceiling);
        return true;
    }

    private void warnIfNearLimit(int warId, long rows, long ceiling) {
        if (ceiling <= 0 || warned.contains(warId)) {
            return;
        }
        double percent = warnAtPercent();
        if (rows >= ceiling * (percent / 100.0)) {
            warned.add(warId);
            logger.warning("War " + warId + " has logged " + rows + " block changes, past "
                    + percent + "% of the " + ceiling + " row limit. At the limit, grief will "
                    + "be refused rather than risk an incomplete rollback.");
        }
    }

    /** Whether the logger is currently refusing work, for {@code /ca perf} and the tests. */
    public boolean isRefusing() {
        return refusing.get();
    }

    public int bufferedCount() {
        synchronized (lock) {
            return buffer.size();
        }
    }

    /** Rows committed for a war, which is what the SPEC 17.4 case 58 ceiling counts. */
    public long writtenFor(int warId) {
        AtomicLong count = written.get(warId);
        return count == null ? 0L : count.get();
    }

    /** The highest sequence handed out for a war. */
    public long sequenceFor(int warId) {
        AtomicLong sequence = sequences.get(warId);
        return sequence == null ? 0L : sequence.get();
    }

    /**
     * Continues a war's sequence from what is already on disk.
     *
     * <p>Called when a war is loaded after a restart. Without it the logger would start again
     * at 1 and hand out sequence numbers that already exist, and rollback would then replay
     * two different changes in an order that is not the order they happened.
     */
    public void resume(int warId, long lastSequence, long rowsAlready) {
        sequences.put(warId, new AtomicLong(Math.max(0L, lastSequence)));
        written.put(warId, new AtomicLong(Math.max(0L, rowsAlready)));
        pending.put(warId, new AtomicLong());
    }

    /**
     * Closes a war's log, SPEC 11.8.2 step 2.
     *
     * <p>Called when a rollback begins. Anything still buffered is kept and will be written:
     * those entries describe damage that happened before the freeze and the replay has not
     * reached them yet.
     */
    public void freeze(int warId) {
        frozen.add(warId);
    }

    public boolean isFrozen(int warId) {
        return frozen.contains(warId);
    }

    /** Forgets a finished war's counters, once its rollback has completed. */
    public void forget(int warId) {
        frozen.remove(warId);
        sequences.remove(warId);
        written.remove(warId);
        pending.remove(warId);
        warned.remove(warId);
    }

    // ==================================================================================
    // Flushing
    // ==================================================================================

    /**
     * Writes one batch.
     *
     * <p>A failed batch is put back at the <em>front</em> of the buffer, so the log keeps the
     * order changes happened in. Appending it instead would interleave a retried batch with
     * newer entries and break the sequence rollback depends on.
     *
     * @return how many rows were written
     */
    public CompletableFuture<Integer> flush() {
        if (!flushing.compareAndSet(false, true)) {
            // A flush is already running. Its own completion schedules the next one.
            return CompletableFuture.completedFuture(0);
        }

        List<WarBlockLogRow> batch = drain(flushBatchSize());
        if (batch.isEmpty()) {
            flushing.set(false);
            return CompletableFuture.completedFuture(0);
        }

        try {
            return dao.insertBatch(batch)
                    .thenApply(count -> {
                        onWritten(batch);
                        flushing.set(false);
                        return count;
                    })
                    .exceptionally(error -> {
                        putBack(batch);
                        flushing.set(false);
                        logger.log(Level.WARNING, "Could not flush " + batch.size()
                                + " war block log entries; they are kept and will be retried.",
                                error);
                        return 0;
                    });
        } catch (RuntimeException e) {
            // A closed pool throws from the call rather than failing the future, and without
            // this the drained batch would be lost. StatsService hit the same thing at M14.
            putBack(batch);
            flushing.set(false);
            logger.log(Level.WARNING, "Could not flush war block log entries; retrying.", e);
            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * Writes everything and waits, for {@code onDisable}.
     *
     * <p>SPEC 17.7 case 84: "Flush the log buffer synchronously in {@code onDisable()}. Never
     * lose buffered entries." Blocking here is not a compromise, it is the requirement.
     */
    public void flushBlocking() {
        int guard = 0;
        while (bufferedCount() > 0 && guard++ < 1000) {
            try {
                flushing.set(false);
                int written = flush().join();
                if (written == 0) {
                    // Nothing is going in. Retrying forever would hang the shutdown.
                    logger.severe("The war block log could not be fully flushed on shutdown; "
                            + bufferedCount() + " entries remain and that war's rollback will "
                            + "be incomplete. This must be investigated before the next war.");
                    return;
                }
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "The final war block log flush failed with "
                        + bufferedCount() + " entries buffered.", e);
                return;
            }
        }
    }

    private List<WarBlockLogRow> drain(int limit) {
        synchronized (lock) {
            int take = Math.min(limit, buffer.size());
            List<WarBlockLogRow> batch = new ArrayList<>(take);
            for (int index = 0; index < take; index++) {
                batch.add(buffer.pollFirst());
            }
            return batch;
        }
    }

    private void putBack(List<WarBlockLogRow> batch) {
        synchronized (lock) {
            for (int index = batch.size() - 1; index >= 0; index--) {
                buffer.addFirst(batch.get(index));
            }
        }
        refusing.set(bufferedCount() >= maxBufferedEntries());
    }

    private void onWritten(List<WarBlockLogRow> batch) {
        for (WarBlockLogRow row : batch) {
            written.computeIfAbsent(row.warId(), ignored -> new AtomicLong()).incrementAndGet();
            // Only now: a row moves from pending to written when it is actually on disk, so a
            // failed batch that goes back into the buffer still counts against its war.
            pending.computeIfAbsent(row.warId(), ignored -> new AtomicLong()).decrementAndGet();
        }
        if (refusing.get() && bufferedCount() < maxBufferedEntries()) {
            refusing.set(false);
            logger.info("The war block log buffer has drained; grief is accepted again.");
        }
    }

    // ==================================================================================
    // Configuration, SPEC 16.3
    // ==================================================================================

    public int flushBatchSize() {
        return Math.max(1, war().getInt("block-log.flush-batch-size", 500));
    }

    public long flushIntervalSeconds() {
        return Math.max(1L, war().getLong("block-log.flush-interval-seconds", 2));
    }

    public int maxBufferedEntries() {
        return Math.max(1, war().getInt("block-log.max-buffered-entries", 100_000));
    }

    public long maxRowsPerWar() {
        return war().getLong("block-log.max-rows-per-war", 5_000_000L);
    }

    public double warnAtPercent() {
        return war().getDouble("block-log.warn-at-percent", 80.0);
    }

    private org.bukkit.configuration.file.FileConfiguration war() {
        return configs.get(ConfigFile.WAR);
    }
}
