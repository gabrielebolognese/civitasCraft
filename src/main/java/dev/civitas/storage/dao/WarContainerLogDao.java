package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.WarContainerLogRow;

/**
 * {@code war_container_log}, SPEC 11.7.
 *
 * <p>Records what was taken from containers during a war. Looted items are permanently gone
 * (SPEC 11.7), so this log exists to answer "who took my diamonds", not to give them back.
 */
public final class WarContainerLogDao extends Dao<WarContainerLogRow> {

    private static final String COLUMNS =
            "id, war_id, world, x, y, z, actor_uuid, item, quantity, timestamp";

    private static final String INSERT_SQL =
            "INSERT INTO war_container_log (war_id, world, x, y, z, actor_uuid, item, quantity, "
                    + "timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    public WarContainerLogDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "war_container_log";
    }

    @Override
    protected WarContainerLogRow map(ResultSet rs) throws SQLException {
        return new WarContainerLogRow(
                rs.getLong("id"),
                rs.getInt("war_id"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                uuid(rs, "actor_uuid"),
                rs.getString("item"),
                rs.getInt("quantity"),
                rs.getLong("timestamp"));
    }

    public CompletableFuture<Long> insert(WarContainerLogRow row) {
        return db.call(connection -> insertSync(connection, INSERT_SQL,
                row.warId(), row.world(), row.x(), row.y(), row.z(), row.actorUuid(),
                row.item(), row.quantity(), row.timestamp()));
    }

    public CompletableFuture<Integer> insertBatch(List<WarContainerLogRow> rows) {
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return db.transaction(connection -> insertBatch(connection, rows));
    }

    public int insertBatch(Connection connection, List<WarContainerLogRow> rows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            for (WarContainerLogRow row : rows) {
                bind(statement, row.warId(), row.world(), row.x(), row.y(), row.z(),
                        row.actorUuid(), row.item(), row.quantity(), row.timestamp());
                statement.addBatch();
            }
            int written = 0;
            for (int result : statement.executeBatch()) {
                written += Math.max(result, 0);
            }
            return written;
        }
    }

    public CompletableFuture<List<WarContainerLogRow>> findByWar(int warId) {
        return queryList("SELECT " + COLUMNS + " FROM war_container_log WHERE war_id = ? "
                + "ORDER BY timestamp", warId);
    }

    public CompletableFuture<Integer> deleteByWar(int warId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM war_container_log WHERE war_id = ?", warId));
    }
}
