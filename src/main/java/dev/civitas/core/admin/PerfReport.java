package dev.civitas.core.admin;

import java.util.OptionalDouble;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.util.Timings;

/**
 * One reading of everything {@code /ca perf} prints, SPEC 9.4.6.
 *
 * <p>A record rather than four accessors on {@code CivitasServices} so that what the command
 * reports is decided in one place and can be asserted without a command source. SPEC 9.4.6
 * names four figures — "avg claim lookup, block-log write rate, GUI open time, DB pool status"
 * — and until M23 the command had none of them: it printed a claim <i>count</i>, a buffer
 * <i>depth</i>, and a line admitting the other two were never taken.
 *
 * <p>The counts are kept alongside the four, because they are what makes the timings readable:
 * an average claim lookup means something different over fifty claims than over fifty thousand.
 *
 * @param claimsCached     claims in the O(1) cache, SPEC 17.7 case 81
 * @param cities           cities those claims belong to
 * @param claimLookup      SPEC 9.4.6's "avg claim lookup"
 * @param guiOpen          SPEC 9.4.6's "GUI open time"
 * @param warLogBuffered   rows waiting to be written, SPEC 17.7 case 85's bounded buffer
 * @param warLogRate       SPEC 9.4.6's "block-log write rate", against SPEC 17.7 case 82's
 *                         target of 2,000 per second
 * @param warsTracked      wars the logger currently holds sequence state for
 * @param pool             SPEC 9.4.6's "DB pool status"
 * @param protectedChunks  admin-protected chunks, SPEC 9.4.3
 * @param timingsEnabled   false when the operator has turned sampling off, in which case the
 *                         two timing figures are empty and the command says so rather than
 *                         printing a zero that reads like a measurement
 */
public record PerfReport(
        int claimsCached,
        int cities,
        Timings.Snapshot claimLookup,
        Timings.Snapshot guiOpen,
        int warLogBuffered,
        double warLogRate,
        int warsTracked,
        DatabaseManager.PoolStatus pool,
        int protectedChunks,
        boolean timingsEnabled) {

    /**
     * A metric's average in microseconds, or empty when nothing has been sampled.
     *
     * <p>Microseconds because that is the scale an operator reasons in: a claim lookup is tens
     * of nanoseconds and a menu open is hundreds of microseconds, and one unit has to span
     * both without becoming either "0" or a number with six digits.
     */
    public static OptionalDouble averageMicros(Timings.Snapshot snapshot) {
        OptionalDouble nanos = snapshot.averageNanos();
        return nanos.isPresent()
                ? OptionalDouble.of(nanos.getAsDouble() / 1000.0)
                : OptionalDouble.empty();
    }
}
