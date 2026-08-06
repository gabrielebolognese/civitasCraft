package dev.civitas.core.war;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import dev.civitas.storage.row.WarBlockLogRow;

/**
 * One war's rollback, in progress.
 *
 * <p>Holds the cursor into the log, the page currently being applied, and the counters
 * {@code /ca war rollbackstatus} reports. Nothing here touches the world or the database; the
 * engine owns both, and this owns only where it has got to.
 */
public final class RollbackJob {

    private final int warId;

    /**
     * Exclusive upper bound for the next page.
     *
     * <p>The replay runs in descending sequence, so this starts at {@link Long#MAX_VALUE} and
     * falls. On a resume it starts at the checkpoint instead, which is what stops a crash from
     * replaying work that was already done.
     */
    private long cursor;

    /** The page being applied, oldest-applied-last. */
    private final Deque<WarBlockLogRow> page = new ArrayDeque<>();

    /**
     * Positions chosen for the SPEC 11.8.2 step 8 sample, and what they should end up as.
     *
     * <p>The value is overwritten every time the position is applied. That is deliberate and
     * it is the subtle part: the replay runs newest to oldest, so the <em>last</em> value
     * written at a position is the oldest entry's, which is the state the block had before the
     * war. Verifying against a randomly chosen entry instead would fail for every block that
     * changed twice, and SPEC 17.4 case 42 guarantees those exist.
     */
    private final Map<String, Sample> sampled = new HashMap<>();

    /**
     * One position chosen for verification, and what it should read afterwards.
     *
     * <p>Carries its world. SPEC 20 decision 4 lets a city hold land in several worlds, so a
     * bare coordinate does not identify a block and checking it against whichever world came
     * first would report mismatches that are really just the wrong world.
     */
    public record Sample(String world, int x, int y, int z, String expected) {

        /** The identity a sample is stored under. */
        public String key() {
            return world + ":" + x + ":" + y + ":" + z;
        }
    }

    private long applied;
    private long skipped;
    private long lastCheckpoint;
    private RollbackStatus status = RollbackStatus.RUNNING;
    private String failureReason;

    public RollbackJob(int warId, long startCursor) {
        this.warId = warId;
        this.cursor = startCursor;
    }

    public int warId() {
        return warId;
    }

    public long cursor() {
        return cursor;
    }

    public void cursor(long value) {
        this.cursor = value;
    }

    public Deque<WarBlockLogRow> page() {
        return page;
    }

    public boolean hasPending() {
        return !page.isEmpty();
    }

    public long applied() {
        return applied;
    }

    public void countApplied() {
        applied++;
    }

    public long skipped() {
        return skipped;
    }

    public void countSkipped() {
        skipped++;
    }

    public long lastCheckpoint() {
        return lastCheckpoint;
    }

    public void checkpointed(long sequence) {
        this.lastCheckpoint = sequence;
    }

    /** The cursor last written to storage, or null if none has been. */
    public Long lastWrittenCursor() {
        return lastWrittenCursor;
    }

    public void wroteCursor(long cursor) {
        this.lastWrittenCursor = cursor;
    }

    private Long lastWrittenCursor;

    public Map<String, Sample> sampled() {
        return sampled;
    }

    public RollbackStatus status() {
        return status;
    }

    public String failureReason() {
        return failureReason;
    }

    public void complete() {
        this.status = RollbackStatus.COMPLETED;
    }

    /**
     * Marks the rollback as failed.
     *
     * <p>There is no way back from here inside the plugin. SPEC 11.8.5 requires an admin to
     * resolve it, and a method that cleared this state would be a way for the server to
     * decide on its own that a griefed city was fine.
     */
    public void fail(String reason) {
        this.status = RollbackStatus.FAILED;
        this.failureReason = Objects.requireNonNullElse(reason, "unknown");
    }
}
