package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.ContestVoteRow;

/** {@code contest_votes}, SPEC 3.9. Anti-abuse weighting is applied before the score lands here. */
public final class ContestVoteDao extends Dao<ContestVoteRow> {

    private static final String COLUMNS = "id, contest_id, voter_uuid, entry_id, score";

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
                rs.getDouble("score"));
    }

    public CompletableFuture<List<ContestVoteRow>> findByEntry(int entryId) {
        return queryList("SELECT " + COLUMNS + " FROM contest_votes WHERE entry_id = ?", entryId);
    }

    public CompletableFuture<List<ContestVoteRow>> findByVoter(int contestId, UUID voter) {
        return queryList("SELECT " + COLUMNS + " FROM contest_votes "
                + "WHERE contest_id = ? AND voter_uuid = ?", contestId, voter);
    }

    /** Replaces any earlier vote by the same player on the same entry. */
    public CompletableFuture<Integer> upsert(int contestId, UUID voter, int entryId, double score) {
        return db.transaction(connection -> {
            int updated = updateSync(connection,
                    "UPDATE contest_votes SET score = ? "
                            + "WHERE contest_id = ? AND voter_uuid = ? AND entry_id = ?",
                    score, contestId, voter, entryId);
            if (updated > 0) {
                return updated;
            }
            return updateSync(connection,
                    "INSERT INTO contest_votes (contest_id, voter_uuid, entry_id, score) "
                            + "VALUES (?, ?, ?, ?)",
                    contestId, voter, entryId, score);
        });
    }

    /** Discards a voter's ballots, used by the SPEC 13.4 IP-match rule. */
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
