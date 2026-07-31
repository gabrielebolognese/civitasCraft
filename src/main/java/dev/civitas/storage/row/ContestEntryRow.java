package dev.civitas.storage.row;

/**
 * A row of {@code contest_entries}, SPEC 3.9.
 *
 * @param plotRegion  the marked region, serialized
 * @param submittedAt null until the city finalises its entry with {@code /contest submit}
 */
public record ContestEntryRow(
        int id,
        int contestId,
        int cityId,
        String plotRegion,
        Long submittedAt,
        double score) {
}
