package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.WarEntitySnapshotRow;

/** {@code war_entity_snapshots}, SPEC 11.8.3. */
public final class WarEntitySnapshotDao extends Dao<WarEntitySnapshotRow> {

    private static final String COLUMNS = "id, war_id, entity_uuid, entity_type, world, "
            + "x, y, z, payload, died_at, snapshot_at";

    public WarEntitySnapshotDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "war_entity_snapshots";
    }

    @Override
    protected WarEntitySnapshotRow map(ResultSet rs) throws SQLException {
        return new WarEntitySnapshotRow(
                rs.getLong("id"),
                rs.getInt("war_id"),
                uuid(rs, "entity_uuid"),
                rs.getString("entity_type"),
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getBytes("payload"),
                nullableLong(rs, "died_at"),
                rs.getLong("snapshot_at"));
    }

    /**
     * Writes a war's whole snapshot in one batch.
     *
     * <p>{@code INSERT OR IGNORE} semantics through the unique index: taking the snapshot twice
     * for the same war, which a restart during {@code beginActive} could cause, must not double
     * the entities it brings back.
     */
    public CompletableFuture<Integer> insertAll(List<WarEntitySnapshotRow> rows) {
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return db.call(connection -> insertAll(connection, rows));
    }

    public int insertAll(Connection connection, List<WarEntitySnapshotRow> rows)
            throws SQLException {
        String sql = dialect().insertIgnore("war_entity_snapshots",
                "war_id, entity_uuid, entity_type, world, x, y, z, payload, died_at, snapshot_at",
                "?, ?, ?, ?, ?, ?, ?, ?, ?, ?");
        int written = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (WarEntitySnapshotRow row : rows) {
                bind(statement, row.warId(), row.entityUuid(), row.entityType(), row.world(),
                        row.x(), row.y(), row.z(), row.payload(), row.diedAt(),
                        row.snapshotAt());
                statement.addBatch();
            }
            for (int result : statement.executeBatch()) {
                if (result > 0) {
                    written += result;
                }
            }
        }
        return written;
    }

    /** Marks one entity as having died during the war. */
    public CompletableFuture<Integer> markDead(int warId, UUID entity, long when) {
        return db.call(connection -> updateSync(connection,
                "UPDATE war_entity_snapshots SET died_at = ? WHERE war_id = ? "
                        + "AND entity_uuid = ? AND died_at IS NULL",
                when, warId, entity));
    }

    /** Everything that was killed during this war, for SPEC 11.8.3's respawn. */
    public CompletableFuture<List<WarEntitySnapshotRow>> findDead(int warId) {
        return queryList("SELECT " + COLUMNS + " FROM war_entity_snapshots WHERE war_id = ? "
                + "AND died_at IS NOT NULL ORDER BY died_at", warId);
    }

    public CompletableFuture<List<WarEntitySnapshotRow>> findByWar(int warId) {
        return queryList("SELECT " + COLUMNS + " FROM war_entity_snapshots WHERE war_id = ? "
                + "ORDER BY id", warId);
    }

    /** Whether this war has already been snapshotted, so a restart does not do it twice. */
    public CompletableFuture<Long> countForWar(int warId) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT COUNT(*) AS total FROM war_entity_snapshots WHERE war_id = ?",
                rs -> rs.getLong("total"), warId).orElse(0L));
    }

    public CompletableFuture<Integer> deleteByWar(int warId) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM war_entity_snapshots WHERE war_id = ?", warId));
    }
}
