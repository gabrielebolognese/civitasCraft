package dev.civitas.storage.row;

/**
 * A row of {@code server_stats}, SPEC 36.6.
 *
 * @param dayStart midnight of the day this describes, which is also the primary key — a day is
 *                 recorded once, and a second sweep on the same day replaces it rather than
 *                 adding a second reading of the same thing
 */
public record ServerStatsRow(long dayStart, int registered, int active7d, int active30d,
                             int cities, int claims, double averageCitySize, int warsStarted,
                             int contestEntries) {
}
