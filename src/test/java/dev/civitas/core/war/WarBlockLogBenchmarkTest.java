package dev.civitas.core.war;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.core.city.CityTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 11.8.1's throughput target, which PLAN.md makes a gate on this milestone.
 *
 * <p>"Target: sustain 2,000 block changes per second with zero main-thread impact." The two
 * halves are measured separately, because they fail differently:
 *
 * <ul>
 *   <li><strong>The record path</strong> is what runs on the server thread, once per block.
 *       It must cost almost nothing, since 100 players breaking blocks (SPEC 17.7 case 82)
 *       all go through it. Measured on its own, with no I/O involved.</li>
 *   <li><strong>The flush path</strong> is what the database has to keep up with. Measured
 *       end to end against a real SQLite file, because the question is whether the batching
 *       is enough, and a mock cannot answer that.</li>
 * </ul>
 *
 * <p>The thresholds are deliberately generous against SPEC's 2,000/sec: a CI machine under
 * load is slower than a game server, and a benchmark that fails on a busy build server teaches
 * people to ignore it. What it is really guarding is a regression of an order of magnitude,
 * which is what a per-block insert or a lock in the wrong place would cost.
 */
class WarBlockLogBenchmarkTest {

    @TempDir
    Path directory;

    private static final String WORLD = "world";
    private static final int WAR = 1;

    /** SPEC 11.8.1's number. */
    private static final int TARGET_PER_SECOND = 2_000;

    private CityTestSupport support;
    private WarBlockLogger log;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        support.configs.get(dev.civitas.config.ConfigFile.WAR)
                .set("block-log.max-buffered-entries", 1_000_000);
        log = new WarBlockLogger(support.daos.warBlockLog(),
                new BukkitTilePayloadCodec(CityTestSupport.quietLogger()),
                support.configs, CityTestSupport.quietLogger());
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    @Test
    @DisplayName("the record path sustains far more than 2,000 changes a second")
    void recordPathIsCheap() {
        int changes = 200_000;
        UUID actor = UUID.randomUUID();

        // Warm up, so the measurement is of the code rather than of the JIT.
        for (int index = 0; index < 20_000; index++) {
            log.record(WAR, WORLD, index, 64, 0, "minecraft:stone", "minecraft:air", null,
                    actor, 1L);
        }
        drain();

        long start = System.nanoTime();
        for (int index = 0; index < changes; index++) {
            log.record(WAR, WORLD, index, 64, 0, "minecraft:stone", "minecraft:air", null,
                    actor, 1L);
        }
        long elapsedNanos = System.nanoTime() - start;

        double perSecond = changes / (elapsedNanos / 1_000_000_000.0);
        System.out.printf("war block log: record path %,.0f changes/sec%n", perSecond);

        assertTrue(perSecond > TARGET_PER_SECOND * 10L,
                "the server-thread path managed only " + (long) perSecond + " changes/sec, "
                        + "which is too close to SPEC 11.8.1's " + TARGET_PER_SECOND
                        + "/sec target for the path that runs while players are mid-swing");
        drain();
    }

    @Test
    @DisplayName("recording and flushing together clear SPEC 11.8.1's 2,000 a second")
    void endToEndMeetsTheTarget() {
        int changes = 40_000;
        UUID actor = UUID.randomUUID();

        long start = System.nanoTime();
        for (int index = 0; index < changes; index++) {
            log.record(WAR, WORLD, index % 4096, 64, index / 4096, "minecraft:stone",
                    "minecraft:air", null, actor, 1L);
        }
        drain();
        long elapsedNanos = System.nanoTime() - start;

        double perSecond = changes / (elapsedNanos / 1_000_000_000.0);
        System.out.printf("war block log: end to end %,.0f changes/sec (%,d rows)%n",
                perSecond, changes);

        assertEquals(changes, await(support.daos.warBlockLog().countByWar(WAR)),
                "every change must reach storage; a benchmark that loses rows measures nothing");
        assertTrue(perSecond > TARGET_PER_SECOND,
                "end to end managed " + (long) perSecond + " changes/sec against SPEC 11.8.1's "
                        + TARGET_PER_SECOND + "/sec target");
    }

    @Test
    @DisplayName("a 40,000 block explosion is absorbed without throttling")
    void explosionSpike() {
        // SPEC 17.4 case 45, at its stated size.
        java.util.List<WarBlockLogger.PendingChange> blast = new java.util.ArrayList<>(40_000);
        for (int index = 0; index < 40_000; index++) {
            blast.add(new WarBlockLogger.PendingChange(WAR, WORLD, index % 4096, 64,
                    index / 4096, "minecraft:stone", "minecraft:air", null, null, 1L));
        }

        long start = System.nanoTime();
        int recorded = log.recordAll(blast);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        System.out.printf("war block log: 40,000 block explosion recorded in %d ms%n",
                elapsedMillis);

        assertEquals(40_000, recorded, "the whole blast must be logged, per SPEC 17.4 case 45");
        assertTrue(elapsedMillis < 1_000,
                "logging one explosion took " + elapsedMillis + " ms on the server thread");
        drain();
    }

    private void drain() {
        int guard = 0;
        while (log.bufferedCount() > 0 && guard++ < 10_000) {
            await(log.flush());
        }
    }
}
