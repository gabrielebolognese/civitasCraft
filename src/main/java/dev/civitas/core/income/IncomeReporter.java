package dev.civitas.core.income;

import java.util.UUID;

/**
 * How the economy tells the progression system that something happened.
 *
 * <p>Two SPEC 13.1 quest categories, Trading and Social, count coins rather than blocks:
 * "sell 5,000 C worth to the market" and "deposit 2,000 C to your city treasury". Neither has
 * a Bukkit event to listen to, so the market and the treasury report them directly.
 *
 * <p>An interface rather than a direct call so those two services do not depend on the
 * progression system: they are built first, they take a reporter that does nothing by
 * default, and M9 hands them a real one. A milestone that is not built cannot break one that
 * is.
 */
@FunctionalInterface
public interface IncomeReporter {

    /**
     * Something countable happened.
     *
     * @param amount whole units, or whole coins for a money metric
     */
    void report(UUID player, QuestMetric metric, long amount);

    /** The reporter used before M9 exists, and in tests that do not care. */
    static IncomeReporter noop() {
        return (player, metric, amount) -> { };
    }
}
