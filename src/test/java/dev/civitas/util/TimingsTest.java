package dev.civitas.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.civitas.core.admin.PerfReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The sampling profiler behind SPEC 9.4.6's two previously unmeasured figures.
 *
 * <p>The tests that matter are the ones about <i>not</i> measuring: that a disabled instance
 * never reads the clock, and that an enabled one only reads it one call in {@code sampleRate}.
 * SPEC 17.7 case 81 puts the claim lookup on every block event, so a profiler that measured
 * every call would cost more than the lookup and the number it reported would be a number
 * about the profiler.
 */
class TimingsTest {

    // ==================================================================================
    // Off
    // ==================================================================================

    @Nested
    @DisplayName("disabled")
    class Disabled {

        @Test
        @DisplayName("never opens a measurement")
        void neverStarts() {
            Timings timings = Timings.disabled();

            assertFalse(timings.enabled());
            assertEquals(Timings.NOT_TIMED, timings.start(Timings.Metric.CLAIM_LOOKUP));
        }

        @Test
        @DisplayName("counts nothing, so /ca perf reports a dash rather than a zero")
        void countsNothing() {
            Timings timings = Timings.disabled();
            for (int i = 0; i < 1000; i++) {
                timings.stop(Timings.Metric.CLAIM_LOOKUP,
                        timings.start(Timings.Metric.CLAIM_LOOKUP));
            }
            Timings.Snapshot snapshot = timings.snapshot(Timings.Metric.CLAIM_LOOKUP);

            assertEquals(0, snapshot.calls());
            assertEquals(0, snapshot.samples());
            assertTrue(snapshot.averageNanos().isEmpty(),
                    "empty, not zero: 'nothing was measured' and 'it took no time' are "
                            + "different claims and an operator must be able to tell them apart");
        }

        @Test
        @DisplayName("record is ignored too")
        void recordIgnored() {
            Timings timings = Timings.disabled();
            timings.record(Timings.Metric.GUI_OPEN, 5_000_000L);

            assertEquals(0, timings.snapshot(Timings.Metric.GUI_OPEN).calls());
        }
    }

    // ==================================================================================
    // Sampling
    // ==================================================================================

    @Nested
    @DisplayName("sampling")
    class Sampling {

        @Test
        @DisplayName("every call is counted, but only one in sampleRate is timed")
        void samples() {
            // The whole design in one assertion. The call count must be exact — it is what
            // tells an operator how busy the path is — while the sample count is a small
            // fraction of it, which is what keeps the measurement cheap.
            Timings timings = new Timings(true);
            int calls = 64 * 20;

            for (int i = 0; i < calls; i++) {
                timings.stop(Timings.Metric.CLAIM_LOOKUP,
                        timings.start(Timings.Metric.CLAIM_LOOKUP));
            }
            Timings.Snapshot snapshot = timings.snapshot(Timings.Metric.CLAIM_LOOKUP);

            assertEquals(calls, snapshot.calls(), "every call counted");
            assertEquals(calls / 64, snapshot.samples(), "one in 64 timed");
            assertTrue(snapshot.averageNanos().isPresent());
        }

        @Test
        @DisplayName("a metric with a rate of one is timed every call")
        void unsampledMetric() {
            // GUI opens. SPEC 17.7 case 86's worst case is 500 of them, and a menu costs
            // microseconds, so there is nothing to protect and no reason to lose precision.
            Timings timings = new Timings(true);
            for (int i = 0; i < 50; i++) {
                timings.stop(Timings.Metric.GUI_OPEN, timings.start(Timings.Metric.GUI_OPEN));
            }
            Timings.Snapshot snapshot = timings.snapshot(Timings.Metric.GUI_OPEN);

            assertEquals(50, snapshot.calls());
            assertEquals(50, snapshot.samples());
        }

        @Test
        @DisplayName("every sample rate is a power of two, or the bitmask is wrong")
        void ratesArePowersOfTwo() {
            // start() tests membership of the sample with `& (rate - 1)`, which is only
            // equivalent to `% rate` for powers of two. A rate of 100 would silently sample
            // a different fraction than the one written down.
            for (Timings.Metric metric : Timings.Metric.values()) {
                int rate = metric.sampleRate();
                assertTrue(rate > 0, metric + " has a non-positive rate");
                assertEquals(0, rate & (rate - 1),
                        metric + " samples at " + rate + ", which is not a power of two");
            }
        }

        @Test
        @DisplayName("an unsampled call does not read the clock")
        void unsampledCallsAreFree() {
            // start() returning NOT_TIMED is the mechanism: the caller's finally block hands
            // it straight back to stop(), which returns immediately without a second
            // nanoTime. If this stopped holding, the sampling would save nothing.
            Timings timings = new Timings(true);
            int timed = 0;

            for (int i = 0; i < 64; i++) {
                if (timings.start(Timings.Metric.CLAIM_LOOKUP) != Timings.NOT_TIMED) {
                    timed++;
                }
            }

            assertEquals(1, timed, "63 of 64 calls never reached System.nanoTime()");
        }
    }

    // ==================================================================================
    // Reporting
    // ==================================================================================

    @Nested
    @DisplayName("reporting")
    class Reporting {

        @Test
        @DisplayName("the average is over what was sampled, not over what was called")
        void averageIsOverSamples() {
            // Dividing the total by the call count instead would report an average 64 times
            // too small — a claim lookup would appear to take a fraction of a nanosecond,
            // which is fast enough that nobody would question it.
            Timings timings = new Timings(true);
            timings.record(Timings.Metric.GUI_OPEN, 1000L);
            timings.record(Timings.Metric.GUI_OPEN, 3000L);

            OptionalDouble average = timings.snapshot(Timings.Metric.GUI_OPEN).averageNanos();

            assertTrue(average.isPresent());
            assertEquals(2000.0, average.getAsDouble(), 0.0001);
        }

        @Test
        @DisplayName("microseconds are nanoseconds divided by a thousand")
        void micros() {
            Timings timings = new Timings(true);
            timings.record(Timings.Metric.GUI_OPEN, 2_500_000L);

            assertEquals(2500.0,
                    PerfReport.averageMicros(timings.snapshot(Timings.Metric.GUI_OPEN))
                            .getAsDouble(), 0.0001);
        }

        @Test
        @DisplayName("snapshots cover every metric, in declaration order")
        void snapshotsAreComplete() {
            List<Timings.Snapshot> snapshots = new Timings(true).snapshots();

            assertEquals(Timings.Metric.values().length, snapshots.size());
            for (int i = 0; i < snapshots.size(); i++) {
                assertEquals(Timings.Metric.values()[i], snapshots.get(i).metric());
            }
        }

        @Test
        @DisplayName("reset clears the counters so an interval can be measured")
        void reset() {
            Timings timings = new Timings(true);
            timings.record(Timings.Metric.GUI_OPEN, 1000L);
            timings.reset();

            assertEquals(0, timings.snapshot(Timings.Metric.GUI_OPEN).calls());
            assertTrue(timings.snapshot(Timings.Metric.GUI_OPEN).averageNanos().isEmpty());
        }
    }

    // ==================================================================================
    // Threading
    // ==================================================================================

    @Nested
    @DisplayName("threading")
    class Threading {

        @Test
        @DisplayName("counts survive concurrent callers")
        void concurrent() throws Exception {
            // A claim lookup happens on the server thread and on the database threads both,
            // and this class must never be the reason two of them contend or lose a count.
            Timings timings = new Timings(true);
            int threads = 8;
            int each = 1000;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < each; i++) {
                            timings.record(Timings.Metric.GUI_OPEN, 100L);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "workers finished");
            pool.shutdownNow();

            assertEquals((long) threads * each,
                    timings.snapshot(Timings.Metric.GUI_OPEN).calls());
            assertEquals(100.0,
                    timings.snapshot(Timings.Metric.GUI_OPEN).averageNanos().getAsDouble(),
                    0.0001);
        }
    }
}
