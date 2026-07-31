package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.LedgerRow;

/**
 * {@code ledger}, SPEC 3.6.
 *
 * <p>Append-only by design and by omission: this class offers no update and no delete,
 * because SPEC 1.5 makes the ledger the record admins reconstruct disputes from, and a
 * record that can be edited is not evidence. Reversing a transaction (SPEC 9.4.4
 * {@code /ca eco rollback}) writes compensating entries instead.
 */
public final class LedgerDao extends Dao<LedgerRow> {

    private static final String COLUMNS =
            "id, timestamp, type, actor_uuid, target_uuid, city_id, amount, balance_after, metadata";

    private static final String INSERT_COLUMNS =
            "timestamp, type, actor_uuid, target_uuid, city_id, amount, balance_after, metadata";

    public LedgerDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "ledger";
    }

    @Override
    protected LedgerRow map(ResultSet rs) throws SQLException {
        return new LedgerRow(
                rs.getLong("id"),
                rs.getLong("timestamp"),
                rs.getString("type"),
                nullableUuid(rs, "actor_uuid"),
                nullableUuid(rs, "target_uuid"),
                nullableInt(rs, "city_id"),
                money(rs, "amount"),
                money(rs, "balance_after"),
                rs.getString("metadata"));
    }

    public CompletableFuture<Optional<LedgerRow>> findById(long id) {
        return db.call(connection ->
                queryOneSync(connection, "SELECT " + COLUMNS + " FROM ledger WHERE id = ?", this::map, id));
    }

    /** Every transaction a player was party to, newest first. SPEC 9.4.1. */
    public CompletableFuture<List<LedgerRow>> findByPlayer(UUID player, long since, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM ledger "
                + "WHERE (actor_uuid = ? OR target_uuid = ?) AND timestamp >= ? "
                + "ORDER BY timestamp DESC LIMIT ?", player, player, since, limit);
    }

    public CompletableFuture<List<LedgerRow>> findByCity(int cityId, long since, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM ledger "
                + "WHERE city_id = ? AND timestamp >= ? ORDER BY timestamp DESC LIMIT ?",
                cityId, since, limit);
    }

    public CompletableFuture<List<LedgerRow>> findByType(String type, long since, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM ledger "
                + "WHERE type = ? AND timestamp >= ? ORDER BY timestamp DESC LIMIT ?",
                type, since, limit);
    }

    public CompletableFuture<Long> insert(LedgerRow row) {
        return db.call(connection -> insert(connection, row));
    }

    /** @return the generated ledger id */
    public long insert(Connection connection, LedgerRow row) throws SQLException {
        return insertSync(connection,
                "INSERT INTO ledger (" + INSERT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.timestamp(), row.type(), row.actorUuid(), row.targetUuid(), row.cityId(),
                row.amount(), row.balanceAfter(), row.metadata());
    }

    /**
     * Writes many entries in one transaction.
     *
     * <p>Used by batch sinks such as the daily upkeep sweep, where one row per city would
     * otherwise mean hundreds of separate commits.
     */
    public CompletableFuture<Integer> insertAll(List<LedgerRow> rows) {
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return db.transaction(connection -> {
            for (LedgerRow row : rows) {
                insert(connection, row);
            }
            return rows.size();
        });
    }
}
