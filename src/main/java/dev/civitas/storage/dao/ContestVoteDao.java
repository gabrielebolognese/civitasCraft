package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.ContestVoteRow;

/**
 * {@code contest_votes}, SPEC 3.9, with the three SPEC 13.4 axes V9 added.
 *
 * <p>A vote is stored with the weight SPEC 13.4's rules gave it, including zero. Nothing here
 * deletes a vote for being discarded: a zero-weight row counts for nothing in the tally and
 * still shows an admin what was cast.
 */
public final class ContestVoteDao extends Dao<ContestVoteRow> {

    private static final String COLUMNS = "id, contest_id, voter_uuid, entry_id, "
            + "creativity, technical_skill, theme_fit, score, weight";

    public ContestVoteDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "contest_votes";
    }

    @Override
    protected ContestVoteRow map(ResultSet rs) throws SQLException {
        return new ContestVoteRow(
                rs.getInt("id"),
                rs.getInt("contest_id"),
                uuid(rs, "voter_uuid"),
                rs.getInt("entry_id"),
                rs.getInt("creativity"),
                rs.getInt("technical_skill"),
                rs.getInt("theme_fit"),
                rs.getDouble("score"),
                rs.getDouble("weight"));
    }

    public CompletableFuture<List<ContestVoteRow>> findByEntry(int entryId) {
        return queryList("SELECT " + COLUMNS + " FROM contest_votes WHERE entry_id = ?", entryId);
    }

    public List<ContestVoteRow> findByEntry(Connection connection, int entryId) throws SQLException {
        return queryListSync(connection,
                "SELECT " + COLUMNS + " FROM contest_votes WHERE entry_id = ?", this::map, entryId);
    }

    public CompletableFuture<List<ContestVoteRow>> findByVoter(int contestId, UUID voter) {
        return queryList("SELECT " + COLUMNS + " FROM contest_votes "
                + "WHERE contest_id = ? AND voter_uuid = ?", contestId, voter);
    }

    public CompletableFuture<List<ContestVoteRow>> findByContest(int contestId) {
        return queryList("SELECT " + COLUMNS + " FROM contest_votes WHERE contest_id = ?", contestId);
    }

    /**
     * Records a vote, replacing any earlier one by the same player on the same entry.
     *
     * <p>SPEC 13.4 does not say whether a voter may change their mind, and the unique index on
     * {@code (contest_id, voter_uuid, entry_id)} settles it: one ballot per voter per entry,
     * and a second submission is a correction rather than a second vote.
     */
    public CompletableFuture<Integer> upsert(int contestId, UUID voter, int entryId,
                                             int creativity, int technicalSkill, int themeFit,
                                             double score, double weight) {
        return db.transaction(connection -> {
            int updated = updateSync(connection,
                    "UPDATE contest_votes SET creativity = ?, technical_skill = ?, theme_fit = ?, "
                            + "score = ?, weight = ? "
                            + "WHERE contest_id = ? AND voter_uuid = ? AND entry_id = ?",
                    creativity, technicalSkill, themeFit, score, weight,
                    contestId, voter, entryId);
            if (updated > 0) {
                return updated;
            }
            return updateSync(connection,
                    "INSERT INTO contest_votes (contest_id, voter_uuid, entry_id, "
                            + "creativity, technical_skill, theme_fit, score, weight) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    contestId, voter, entryId, creativity, technicalSkill, themeFit, score, weight);
        });
    }

    public CompletableFuture<Integer> deleteByVoter(int contestId, UUID voter) {
        return db.call(connection -> deleteByVoter(connection, contestId, voter));
    }

    public int deleteByVoter(Connection connection, int contestId, UUID voter) throws SQLException {
        return updateSync(connection,
                "DELETE FROM contest_votes WHERE contest_id = ? AND voter_uuid = ?", contestId, voter);
    }

    public CompletableFuture<Integer> deleteByEntry(int entryId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM contest_votes WHERE entry_id = ?", entryId));
    }
}
