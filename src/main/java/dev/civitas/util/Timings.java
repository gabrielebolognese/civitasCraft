package dev.civitas.util;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * The two timings SPEC 9.4.6 asks {@code /ca perf} for and nothing measured until M23.
 *
 * <p>M21 built the command and printed a line naming average claim lookup and GUI open time as
 * unmeasured, on the grounds that a plausible-looking figure nobody took is worse than an
 * admission — an operator diagnosing a stall would chase it. This class is the other half of
 * that: the figures are real now, and the line is gone.
 *
 * <h2>Why it is sampled</h2>
 *
 * <p>SPEC 17.7 case 81 puts claim lookup on every block event, which is the one path in this
 * plugin where measurement can plausibly cost more than the work. It genuinely can:
 * {@code System.nanoTime()} is roughly 20ns a call on both platforms, twice per measurement,
 * against a {@code Long2ObjectMap} get of well under ten. Timing every lookup would triple the
 * cost of the thing being timed, and the number reported would then be a number about the
 * profiler.
 *
 * <p>So each metric carries a sample rate and only one call in {@code rate} is timed. At the
 * shipped 1-in-64 the amortised cost of instrumenting a claim lookup is under a nanosecond,
 * comfortably below the lookup itself, while a server doing 2,000 block events a second still
 * collects thirty samples a second — far more than enough for an average. GUI opens are timed
 * every time, because SPEC 17.7 case 86's worst case is 500 of them and there is nothing to
 * protect.
 *
 * <h2>Why it defaults on</h2>
 *
 * <p>A profiler that ships disabled is one an operator turns on <i>after</i> the incident, by
 * which time the thing they wanted to measure has stopped happening. Sampling is what makes
 * leaving it on defensible. {@code performance.timings-enabled} turns it off for an operator
 * who wants the path untouched, and when it is off the caller does not even read the clock —
 * {@link #start()} returns {@link #NOT_TIMED} and {@link #stop} does nothing with it.
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link LongAdder} throughout. A claim lookup happens on the server thread and on the
 * database threads both, and this must never be the reason two of them contend.
 */
public final class Timings {

    /** Returned by {@link #start()} when this call is not being timed. */
    public static final long NOT_TIMED = 0L;

    /** What is measured, and how often. */
    public enum Metric {

        /**
         * {@code ClaimRegistry.at}, SPEC 17.7 case 81's hot path.
         *
         * <p>Sampled at 1 in 64. The rate is a power of two so the sampling test is a bitmask
         * rather than a division.
         */
        CLAIM_LOOKUP(64),

        /**
         * Building and showing a menu, SPEC 17.7 case 86.
         *
         * <p>Every one, because 500 simultaneous opens is the stated worst case and a menu
         * costs microseconds rather than nanoseconds, so the clock is noise beside it.
         */
        GUI_OPEN(1);

        private final int sampleRate;

        Metric(int sampleRate) {
            this.sampleRate = sampleRate;
        }

        /** One call in this many is timed. */
        public int sampleRate() {
            return sampleRate;
        }
    }

    private record Counter(LongAdder calls, LongAdder sampled, LongAdder nanos) {
        Counter() {
            this(new LongAdder(), new LongAdder(), new LongAdder());
        }
    }

    /** A metric's collected figures. Nanoseconds, because that is what was measured. */
    public record Snapshot(Metric metric, long calls, long samples, long totalNanos) {

        /** Mean nanoseconds per call, or empty when nothing was sampled. */
        public java.util.OptionalDouble averageNanos() {
            return samples == 0
                    ? java.util.OptionalDouble.empty()
                    : java.util.OptionalDouble.of((double) totalNanos / samples);
        }
    }

    private final Map<Metric, Counter> counters = new EnumMap<>(Metric.class);
    private final boolean enabled;

    public Timings(boolean enabled) {
        this.enabled = enabled;
        for (Metric metric : Metric.values()) {
            counters.put(metric, new Counter());
        }
    }

    /** A disabled instance, for tests and for services built before config is read. */
    public static Timings disabled() {
        return new Timings(false);
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Opens a measurement, or declines to.
     *
     * <p>Returns {@link #NOT_TIMED} when timings are off or this call is not in the sample, in
     * which case the clock is never read. Callers pass the result straight to {@link #stop}
     * and need not check it.
     */
    public long start(Metric metric) {
        if (!enabled) {
            return NOT_TIMED;
        }
        Counter counter = counters.get(metric);
        counter.calls().increment();
        if (metric.sampleRate() > 1
                && (counter.calls().sum() & (metric.sampleRate() - 1L)) != 0L) {
            return NOT_TIMED;
        }
        long now = System.nanoTime();
        // nanoTime may legitimately return 0, which would otherwise read as "not timed" and
        // silently drop that one sample. One nanosecond of error, once, beats a lost branch.
        return now == NOT_TIMED ? 1L : now;
    }

    /** Closes a measurement opened by {@link #start}. A no-op if that call was not sampled. */
    public void stop(Metric metric, long started) {
        if (started == NOT_TIMED) {
            return;
        }
        Counter counter = counters.get(metric);
        counter.sampled().increment();
        counter.nanos().add(System.nanoTime() - started);
    }

    /**
     * Records a call that was measured elsewhere, or one whose duration is already known.
     *
     * <p>Used where the work being timed spans a callback rather than a block.
     */
    public void record(Metric metric, long nanos) {
        if (!enabled) {
            return;
        }
        Counter counter = counters.get(metric);
        counter.calls().increment();
        counter.sampled().increment();
        counter.nanos().add(nanos);
    }

    public Snapshot snapshot(Metric metric) {
        Counter counter = counters.get(Objects.requireNonNull(metric, "metric"));
        return new Snapshot(metric, counter.calls().sum(), counter.sampled().sum(),
                counter.nanos().sum());
    }

    /** Every metric, in declaration order. */
    public java.util.List<Snapshot> snapshots() {
        java.util.List<Snapshot> all = new java.util.ArrayList<>(Metric.values().length);
        for (Metric metric : Metric.values()) {
            all.add(snapshot(metric));
        }
        return java.util.List.copyOf(all);
    }

    /** Clears every counter, so an operator can measure one interval rather than uptime. */
    public void reset() {
        counters.values().forEach(counter -> {
            counter.calls().reset();
            counter.sampled().reset();
            counter.nanos().reset();
        });
    }
}
