package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.AuditLogRow;

/**
 * {@code audit_log}, SPEC 3.9 and SPEC 17.6 case 80.
 *
 * <p>Admin actions only, separate from the ledger. Like the ledger it offers no update and
 * no delete: SPEC 17.6 case 80 requires that it cannot be cleared in game, and the simplest
 * way to guarantee that is to give the plugin no code that could.
 */
public final class AuditLogDao extends Dao<AuditLogRow> {

    private static final String COLUMNS =
            "id, timestamp, actor_uuid, action, target, reason, metadata";

    public AuditLogDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "audit_log";
    }

    @Override
    protected AuditLogRow map(ResultSet rs) throws SQLException {
        return new AuditLogRow(
                rs.getLong("id"),
                rs.getLong("timestamp"),
                nullableUuid(rs, "actor_uuid"),
                rs.getString("action"),
                rs.getString("target"),
                rs.getString("reason"),
                rs.getString("metadata"));
    }

    public CompletableFuture<Long> insert(AuditLogRow row) {
        return db.call(connection -> insert(connection, row));
    }

    public long insert(Connection connection, AuditLogRow row) throws SQLException {
        return insertSync(connection,
                "INSERT INTO audit_log (timestamp, actor_uuid, action, target, reason, metadata) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                row.timestamp(), row.actorUuid(), row.action(), row.target(), row.reason(),
                row.metadata());
    }

    public CompletableFuture<List<AuditLogRow>> findRecent(long since, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM audit_log WHERE timestamp >= ? "
                + "ORDER BY timestamp DESC LIMIT ?", since, limit);
    }

    public CompletableFuture<List<AuditLogRow>> findByActor(UUID actor, long since, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM audit_log "
                + "WHERE actor_uuid = ? AND timestamp >= ? ORDER BY timestamp DESC LIMIT ?",
                actor, since, limit);
    }

    public CompletableFuture<List<AuditLogRow>> findByTarget(String target, long since, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM audit_log "
                + "WHERE target = ? AND timestamp >= ? ORDER BY timestamp DESC LIMIT ?",
                target, since, limit);
    }
}
