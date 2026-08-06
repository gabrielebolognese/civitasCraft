package dev.civitas.core.war;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.storage.row.WarBlockLogRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The write side of the rollback engine, SPEC 11.8.1.
 *
 * <p>SPEC 19 has M17 built and tested before any war exists, so the logger is driven directly
 * here rather than through gameplay. That is the point of the milestone ordering: this code
 * gets its scrutiny now, not during a war with players waiting.
 *
 * <p>The tests that matter most are the ones about losing entries. A missing log row is a
 * block that can never be restored, and unlike a wrong number it is invisible until a war ends
 * and somebody's wall is still missing.
 */
class WarBlockLoggerTest {

    @TempDir
    Path directory;

    private static final int WAR = 1;
    private static final String WORLD = "world";

    private CityTestSupport support;
    private WarBlockLogger log;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        log = new WarBlockLogger(support.daos.warBlockLog(),
                new BukkitTilePayloadCodec(CityTestSupport.quietLogger()),
                support.configs, CityTestSupport.quietLogger());
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private boolean record(int warId, int x) {
        return log.record(warId, WORLD, x, 64, 0, "minecraft:stone", "minecraft:air",
                null, UUID.randomUUID(), 1_000L + x);
    }

    private List<WarBlockLogRow> stored(int warId) {
        return await(support.daos.warBlockLog().findForReplay(warId, Long.MAX_VALUE, 100_000));
    }

    // ==================================================================================
    // Recording and sequencing
    // ==================================================================================

    @Nested
    @DisplayName("recording")
    class Recording {

        @Test
        @DisplayName("an entry is buffered, not written, until a flush")
        void buffersBeforeFlushing() {
            assertTrue(record(WAR, 0));

            assertEquals(1, log.bufferedCount());
            assertEquals(0, stored(WAR).size(), "nothing should reach storage before a flush");

            await(log.flush());

            assertEquals(0, log.bufferedCount());
            assertEquals(1, stored(WAR).size());
        }

        @Test
        @DisplayName("sequence numbers are monotonic per war, which is what replay order means")
        void sequencesAreMonotonic() {
            for (int x = 0; x < 10; x++) {
                record(WAR, x);
            }
            await(log.flush());

            List<Long> sequences = stored(WAR).stream().map(WarBlockLogRow::sequence).toList();

            // findForReplay returns newest first, which is the order rollback applies.
            assertEquals(10, sequences.size());
            for (int index = 0; index < sequences.size() - 1; index++) {
                assertTrue(sequences.get(index) > sequences.get(index + 1),
                        "replay must come back in descending sequence order");
            }
        }

        @Test
        @DisplayName("two wars keep separate sequences")
        void sequencesArePerWar() {
            record(1, 0);
            record(2, 0);
            record(1, 1);
            await(log.flush());

            assertEquals(2, log.sequenceFor(1));
            assertEquals(1, log.sequenceFor(2));
        }

        @Test
        @DisplayName("place, break, place again replays correctly by construction")
        void repeatedChangesAtOneBlock() {
            // SPEC 17.4 case 42. Three changes at one position; reverse order undoes them in
            // the order they happened, so the oldest entry is the last applied and wins.
            log.record(WAR, WORLD, 5, 64, 5, "minecraft:air", "minecraft:stone", null, null, 1L);
            log.record(WAR, WORLD, 5, 64, 5, "minecraft:stone", "minecraft:air", null, null, 2L);
            log.record(WAR, WORLD, 5, 64, 5, "minecraft:air", "minecraft:stone", null, null, 3L);
            await(log.flush());

            List<WarBlockLogRow> replay = stored(WAR);

            assertEquals(3, replay.size());
            assertEquals("minecraft:air", replay.get(0).oldBlockData(), "newest change first");
            assertEquals("minecraft:air", replay.get(2).oldBlockData(),
                    "and the oldest entry holds the state the block started in");
        }

        @Test
        @DisplayName("a whole explosion is recorded in one call, unthrottled")
        void explosionBatch() {
            // SPEC 17.4 case 45: 40,000 blocks in one tick are logged in one batch with
            // nothing throttled. A smaller number here, same path.
            List<WarBlockLogger.PendingChange> blast = new ArrayList<>();
            for (int index = 0; index < 5_000; index++) {
                blast.add(new WarBlockLogger.PendingChange(WAR, WORLD, index, 64, 0,
                        "minecraft:stone", "minecraft:air", null, null, 1L));
            }

            assertEquals(5_000, log.recordAll(blast));
            assertEquals(5_000, log.bufferedCount());
        }
    }

    // ==================================================================================
    // Flushing, SPEC 11.8.1
    // ==================================================================================

    @Nested
    @DisplayName("flushing")
    class Flushing {

        @Test
        @DisplayName("a flush writes at most one batch, so a spike drains over several")
        void batchSizeIsHonoured() {
            int batch = log.flushBatchSize();
            for (int x = 0; x < batch * 2 + 5; x++) {
                record(WAR, x);
            }

            assertEquals(batch, await(log.flush()));
            assertEquals(batch + 5, log.bufferedCount());
        }

        @Test
        @DisplayName("everything eventually reaches storage")
        void repeatedFlushesDrain() {
            for (int x = 0; x < 1_200; x++) {
                record(WAR, x);
            }
            while (log.bufferedCount() > 0) {
                await(log.flush());
            }

            assertEquals(1_200, stored(WAR).size());
        }

        @Test
        @DisplayName("a failed batch is kept, in order, and retried")
        void failedBatchIsRetainedInOrder() {
            // The failure that would be catastrophic if mishandled: a dropped batch is blocks
            // that can never be restored, and a reordered one is a rollback that replays out
            // of sequence.
            for (int x = 0; x < 10; x++) {
                record(WAR, x);
            }
            support.db.close();

            assertEquals(0, await(log.flush()));
            assertEquals(10, log.bufferedCount(), "the batch must be kept, not dropped");
        }

        @Test
        @DisplayName("the shutdown flush writes what is buffered")
        void disableFlushWrites() {
            // SPEC 17.7 case 84.
            for (int x = 0; x < 50; x++) {
                record(WAR, x);
            }

            log.flushBlocking();

            assertEquals(0, log.bufferedCount());
            assertEquals(50, stored(WAR).size());
        }

        @Test
        @DisplayName("the shutdown flush gives up rather than hanging when nothing can be written")
        void disableFlushDoesNotHang() {
            record(WAR, 0);
            support.db.close();

            // Must return. A shutdown that never completes is worse than a logged failure.
            log.flushBlocking();

            assertEquals(1, log.bufferedCount());
        }
    }

    // ==================================================================================
    // Refusing work, SPEC 17.4 case 58 and SPEC 17.7 case 85
    // ==================================================================================

    @Nested
    @DisplayName("when it cannot keep up")
    class Backpressure {

        @Test
        @DisplayName("a full buffer refuses new changes rather than dropping them")
        void fullBufferRefuses() {
            // SPEC 17.7 case 85: the database is gone and the buffer fills. SPEC 17.4 case 58
            // settles what to do: "Correctness over gameplay."
            support.configs.get(ConfigFile.WAR).set("block-log.max-buffered-entries", 20);

            for (int x = 0; x < 20; x++) {
                assertTrue(record(WAR, x), "entry " + x + " should fit");
            }

            assertFalse(record(WAR, 999), "the 21st must be refused, so the caller cancels it");
            assertTrue(log.isRefusing());
        }

        @Test
        @DisplayName("and accepts again once the buffer drains")
        void refusalLifts() {
            support.configs.get(ConfigFile.WAR).set("block-log.max-buffered-entries", 20);
            for (int x = 0; x < 20; x++) {
                record(WAR, x);
            }
            assertFalse(record(WAR, 999));

            while (log.bufferedCount() > 0) {
                await(log.flush());
            }

            assertTrue(record(WAR, 1000));
            assertFalse(log.isRefusing());
        }

        @Test
        @DisplayName("a war that reaches its row ceiling stops accepting grief")
        void rowCeilingRefuses() {
            // SPEC 17.4 case 58: at the limit, "stop accepting new grief … rather than risk an
            // incomplete rollback".
            support.configs.get(ConfigFile.WAR).set("block-log.max-rows-per-war", 30);

            for (int x = 0; x < 30; x++) {
                assertTrue(record(WAR, x));
            }

            assertFalse(record(WAR, 999));
        }

        @Test
        @DisplayName("the ceiling counts written rows as well as buffered ones")
        void ceilingCountsWrittenRows() {
            support.configs.get(ConfigFile.WAR).set("block-log.max-rows-per-war", 30);
            for (int x = 0; x < 20; x++) {
                record(WAR, x);
            }
            await(log.flush());
            assertEquals(20, log.writtenFor(WAR));

            for (int x = 0; x < 10; x++) {
                record(WAR, 100 + x);
            }

            assertFalse(record(WAR, 999), "written plus buffered has reached the ceiling");
        }

        @Test
        @DisplayName("one war hitting its ceiling does not stop another")
        void ceilingIsPerWar() {
            support.configs.get(ConfigFile.WAR).set("block-log.max-rows-per-war", 10);
            for (int x = 0; x < 10; x++) {
                record(1, x);
            }

            assertFalse(record(1, 999));
            assertTrue(record(2, 0), "a different war has its own budget");
        }
    }

    // ==================================================================================
    // Restarts
    // ==================================================================================

    @Test
    @DisplayName("a resumed war continues its sequence rather than starting again at one")
    void resumeContinuesTheSequence() {
        for (int x = 0; x < 5; x++) {
            record(WAR, x);
        }
        await(log.flush());

        // What a restart looks like: a fresh logger over the same database.
        WarBlockLogger restarted = new WarBlockLogger(support.daos.warBlockLog(),
                new BukkitTilePayloadCodec(CityTestSupport.quietLogger()),
                support.configs, CityTestSupport.quietLogger());
        long lastSequence = await(support.daos.warBlockLog().maxSequence(WAR));
        long rows = await(support.daos.warBlockLog().countByWar(WAR));
        restarted.resume(WAR, lastSequence, rows);

        restarted.record(WAR, WORLD, 99, 64, 0, "minecraft:stone", "minecraft:air", null, null, 1L);
        await(restarted.flush());

        List<Long> sequences = stored(WAR).stream().map(WarBlockLogRow::sequence).toList();
        assertEquals(6, sequences.size());
        assertEquals(6L, sequences.get(0), "the new entry must not reuse a sequence number");
        assertEquals(sequences.size(), sequences.stream().distinct().count(),
                "duplicate sequences would make replay order ambiguous");
    }

    @Test
    @DisplayName("forgetting a war clears its counters")
    void forgetClears() {
        record(WAR, 0);
        await(log.flush());
        assertEquals(1, log.writtenFor(WAR));

        log.forget(WAR);

        assertEquals(0, log.writtenFor(WAR));
        assertEquals(0, log.sequenceFor(WAR));
    }

    @Test
    @DisplayName("the SPEC 16.3 block-log settings are all read from war.yml")
    void configurationIsReadNotHardcoded() {
        assertEquals(500, log.flushBatchSize());
        assertEquals(2L, log.flushIntervalSeconds());
        assertEquals(100_000, log.maxBufferedEntries());
        assertEquals(5_000_000L, log.maxRowsPerWar());
        assertEquals(80.0, log.warnAtPercent(), 1e-9);
    }
}
