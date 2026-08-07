package dev.civitas.storage.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.ReportRow;

/** {@code reports}, SPEC 15.3. */
public final class ReportDao extends Dao<ReportRow> {

    private static final String COLUMNS = "id, reporter_uuid, target_uuid, reason, created_at, "
            + "state, handled_by, handled_at, resolution";

    public static final String OPEN = "OPEN";
    public static final String RESOLVED = "RESOLVED";
    public static final String DISMISSED = "DISMISSED";

    public ReportDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "reports";
    }

    @Override
    protected ReportRow map(ResultSet rs) throws SQLException {
        return new ReportRow(
                rs.getLong("id"),
                uuid(rs, "reporter_uuid"),
                uuid(rs, "target_uuid"),
                rs.getString("reason"),
                rs.getLong("created_at"),
                rs.getString("state"),
                nullableUuid(rs, "handled_by"),
                nullableLong(rs, "handled_at"),
                rs.getString("resolution"));
    }

    public CompletableFuture<Long> insert(ReportRow row) {
        return db.call(connection -> insertSync(connection,
                "INSERT INTO reports (reporter_uuid, target_uuid, reason, created_at, state, "
                        + "handled_by, handled_at, resolution) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.reporterUuid(), row.targetUuid(), row.reason(), row.createdAt(),
                row.state(), row.handledBy(), row.handledAt(), row.resolution()));
    }

    /** The queue, oldest first, because the oldest complaint has waited longest. */
    public CompletableFuture<List<ReportRow>> findOpen(int limit) {
        return queryList("SELECT " + COLUMNS + " FROM reports WHERE state = ? "
                + "ORDER BY created_at LIMIT ?", OPEN, limit);
    }

    public CompletableFuture<List<ReportRow>> findAbout(UUID target, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM reports WHERE target_uuid = ? "
                + "ORDER BY created_at DESC LIMIT ?", target, limit);
    }

    /** How many open reports this player has already filed, for the spam guard. */
    public CompletableFuture<Long> countRecentBy(UUID reporter, long since) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT COUNT(*) AS total FROM reports WHERE reporter_uuid = ? "
                        + "AND created_at >= ?",
                rs -> rs.getLong("total"), reporter, since).orElse(0L));
    }

    public CompletableFuture<Integer> handle(long id, String state, UUID handler, long when,
                                             String resolution) {
        // The state is in the WHERE clause, so two moderators cannot both close the same
        // report and the second one is told it was already handled.
        return db.call(connection -> updateSync(connection,
                "UPDATE reports SET state = ?, handled_by = ?, handled_at = ?, resolution = ? "
                        + "WHERE id = ? AND state = ?",
                state, handler, when, resolution, id, OPEN));
    }
}
