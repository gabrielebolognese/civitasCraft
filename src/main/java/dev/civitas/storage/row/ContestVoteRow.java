package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code contest_votes}, SPEC 3.9.
 *
 * @param score the vote after SPEC 13.4 weighting is applied
 */
public record ContestVoteRow(
        int id,
        int contestId,
        UUID voterUuid,
        int entryId,
        double score) {
}
