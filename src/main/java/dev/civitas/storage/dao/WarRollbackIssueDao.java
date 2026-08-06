package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.WarRollbackIssueRow;

/**
 * {@code war_rollback_issues}, added by V11.
 *
 * <p>Append-only, like the SPEC 3.6 ledger and for the same reason: these rows are the record
 * that a restore was imperfect, and there is no method here that edits or removes one except
 * the wholesale delete a finished war's cleanup uses.
 */
public final class WarRollbackIssueDao extends Dao<WarRollbackIssueRow> {

    private static final String COLUMNS =
            "id, war_id, kind, world, x, y, z, detail, detected_at";

    public WarRollbackIssueDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "war_rollback_issues";
    }

    @Override
    protected WarRollbackIssueRow map(ResultSet rs) throws SQLException {
        return new WarRollbackIssueRow(
                rs.getLong("id"),
                rs.getInt("war_id"),
                rs.getString("kind"),
                rs.getString("world"),
                nullableInt(rs, "x"),
                nullableInt(rs, "y"),
                nullableInt(rs, "z"),
                rs.getString("detail"),
                rs.getLong("detected_at"));
    }

    public CompletableFuture<List<WarRollbackIssueRow>> findByWar(int warId, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM war_rollback_issues "
                + "WHERE war_id = ? ORDER BY id LIMIT ?", warId, limit);
    }

    public CompletableFuture<Long> countByWar(int warId) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT COUNT(*) AS total FROM war_rollback_issues WHERE war_id = ?",
                rs -> rs.getLong("total"), warId).orElse(0L));
    }

    public CompletableFuture<Integer> insert(WarRollbackIssueRow row) {
        return db.call(connection -> insert(connection, row));
    }

    public int insert(Connection connection, WarRollbackIssueRow row) throws SQLException {
        return updateSync(connection,
                "INSERT INTO war_rollback_issues (war_id, kind, world, x, y, z, detail, detected_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.warId(), row.kind(), row.world(), row.x(), row.y(), row.z(),
                row.detail(), row.detectedAt());
    }

    /** Writes a batch of findings, for a verification pass that turned several up. */
    public CompletableFuture<Integer> insertAll(List<WarRollbackIssueRow> rows) {
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return db.transaction(connection -> {
            int written = 0;
            for (WarRollbackIssueRow row : rows) {
                written += insert(connection, row);
            }
            return written;
        });
    }

    public CompletableFuture<Integer> deleteByWar(int warId) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM war_rollback_issues WHERE war_id = ?", warId));
    }
}
