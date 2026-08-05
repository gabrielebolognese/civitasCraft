package dev.civitas.storage.row;

import java.util.UUID;

/**
 * A row of {@code contest_votes}, SPEC 3.9, with the three axes V9 added for SPEC 13.4.
 *
 * @param score  the combined figure the tally uses, the mean of the three axes
 * @param weight what SPEC 13.4's anti-abuse rules decided this vote is worth. A discarded vote
 *               is stored at zero rather than deleted, so that an admin investigating a result
 *               can see what was thrown away and why the totals do not match the ballot count
 */
public record ContestVoteRow(
        int id,
        int contestId,
        UUID voterUuid,
        int entryId,
        int creativity,
        int technicalSkill,
        int themeFit,
        double score,
        double weight) {
}
