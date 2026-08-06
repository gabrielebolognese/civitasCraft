package dev.civitas.storage.row;

/**
 * A row of {@code war_rollback_issues}, added by V11.
 *
 * <p>What went wrong while putting a war's damage back. SPEC 17.4 case 57 requires a failed
 * verification sample to be logged and surfaced rather than silently ignored, and SPEC 11.8.4
 * requires the same of a chunk whose hash does not match.
 *
 * @param kind  one of {@code VERIFY_MISMATCH}, {@code CHUNK_HASH_MISMATCH},
 *              {@code APPLY_FAILED}, {@code LOG_UNREADABLE}
 * @param world null for an issue with no single position, such as an unreadable log
 */
public record WarRollbackIssueRow(
        long id,
        int warId,
        String kind,
        String world,
        Integer x,
        Integer y,
        Integer z,
        String detail,
        long detectedAt) {
}
