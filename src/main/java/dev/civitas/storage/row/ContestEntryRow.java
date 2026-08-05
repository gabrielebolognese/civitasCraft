package dev.civitas.storage.row;

/**
 * A row of {@code contest_entries}, SPEC 3.9, with the columns V9 added for SPEC 13.4.
 *
 * @param plotRegion          the marked region, serialized
 * @param submittedAt         null until the city finalises its entry with {@code /contest submit}
 * @param disqualified        SPEC 9.4.6. The entry is kept rather than deleted, so the decision
 *                            and the votes that led to it stay auditable
 * @param disqualifiedReason  why, which SPEC 9.4.6 makes mandatory on the command
 * @param placement           1, 2 or 3 for a paid place, null otherwise. Written once at
 *                            scoring so a published result cannot drift
 */
public record ContestEntryRow(
        int id,
        int contestId,
        int cityId,
        String plotRegion,
        Long submittedAt,
        double score,
        boolean disqualified,
        String disqualifiedReason,
        Integer placement) {
}
