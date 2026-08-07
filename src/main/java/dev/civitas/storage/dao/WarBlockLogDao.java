package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.WarBlockLogRow;

/**
 * {@code war_block_log}, SPEC 3.8. The highest-write-volume table in the plugin and the
 * rollback engine's only source of truth.
 *
 * <p>Two access patterns matter and both are served by the {@code (war_id, sequence DESC)}
 * index: batched appends while a war is running, and reverse-order paged reads while it is
 * being undone. {@link #insertBatch} uses a single JDBC batch inside one transaction because
 * SPEC 11.8.1 targets 2,000 block changes per second, which one-statement-per-block cannot
 * reach.
 */
public final class WarBlockLogDao extends Dao<WarBlockLogRow> {

    private static final String COLUMNS =
            "id, war_id, sequence, world, x, y, z, old_block_data, new_block_data, old_nbt, "
                    + "actor_uuid, timestamp";

    private static final String INSERT_SQL =
            "INSERT INTO war_block_log (war_id, sequence, world, x, y, z, old_block_data, "
                    + "new_block_data, old_nbt, actor_uuid, timestamp) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    public WarBlockLogDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "war_block_log";
    }

    @Override
    protected WarBlockLogRow map(ResultSet rs) throws SQLException {
        return new WarBlockLogRow(
                rs.getLong("id"),
                rs.getInt("war_id"),
                rs.getLong("sequence"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getString("old_block_data"),
                rs.getString("new_block_data"),
                rs.getBytes("old_nbt"),
                nullableUuid(rs, "actor_uuid"),
                rs.getLong("timestamp"));
    }

    /**
     * Appends a batch of log entries in one transaction.
     *
     * @return the number of rows written
     */
    public CompletableFuture<Integer> insertBatch(List<WarBlockLogRow> rows) {
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return db.transaction(connection -> insertBatch(connection, rows));
    }

    public int insertBatch(Connection connection, List<WarBlockLogRow> rows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            for (WarBlockLogRow row : rows) {
                bind(statement,
                        row.warId(), row.sequence(), row.world(), row.x(), row.y(), row.z(),
                        row.oldBlockData(), row.newBlockData(), row.oldNbt(), row.actorUuid(),
                        row.timestamp());
                statement.addBatch();
            }
            int written = 0;
            for (int result : statement.executeBatch()) {
                written += Math.max(result, 0);
            }
            return written;
        }
    }

    /**
     * One page of the replay, in the order rollback applies it.
     *
     * @param warId          the war being undone
     * @param beforeSequence exclusive upper bound; pass {@link Long#MAX_VALUE} for the first
     *                       page, then the lowest sequence of the previous page
     * @param limit          page size, from {@code war.rollback.read-page-size}
     * @return entries ordered by sequence descending, newest change first
     */
    public CompletableFuture<List<WarBlockLogRow>> findForReplay(int warId, long beforeSequence, int limit) {
        return db.call(connection -> findForReplay(connection, warId, beforeSequence, limit));
    }

    public List<WarBlockLogRow> findForReplay(Connection connection, int warId, long beforeSequence,
                                              int limit) throws SQLException {
        return queryListSync(connection,
                "SELECT " + COLUMNS + " FROM war_block_log "
                        + "WHERE war_id = ? AND sequence < ? ORDER BY sequence DESC LIMIT ?",
                this::map, warId, beforeSequence, limit);
    }

    /** Total entries logged for a war, for progress reporting and the SPEC 17.4 case 58 cap. */
    public CompletableFuture<Long> countByWar(int warId) {
        return db.call(connection -> countByWar(connection, warId));
    }

    public long countByWar(Connection connection, int warId) throws SQLException {
        return queryOneSync(connection,
                "SELECT COUNT(*) AS total FROM war_block_log WHERE war_id = ?",
                rs -> rs.getLong("total"), warId).orElse(0L);
    }

    /**
     * The oldest entry this war holds for each position inside a block range.
     *
     * <p>For SPEC 17.4 case 51. When a war starts on ground an older war is already fighting
     * over, the new war has no record of what was there before the older one damaged it — and
     * its rollback would restore the damage. These rows are what the new war is seeded with,
     * so that its own oldest entry is the true pre-war state.
     *
     * <p>The oldest entry per position is the one with the lowest sequence, because a replay
     * runs newest to oldest and the last value written wins.
     *
     * <p>Bounded by a block range rather than run over the whole log: two zones overlap in a
     * perimeter, not in their entirety, and a war log can hold two million rows (SPEC 17.7
     * case 83).
     */
    public CompletableFuture<List<WarBlockLogRow>> oldestPerPositionIn(
            int warId, String world, int minX, int maxX, int minZ, int maxZ) {
        return queryList("SELECT " + COLUMNS + " FROM war_block_log WHERE sequence IN ("
                        + "SELECT MIN(sequence) FROM war_block_log "
                        + "WHERE war_id = ? AND world = ? "
                        + "AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? "
                        + "GROUP BY x, y, z)",
                warId, world, minX, maxX, minZ, maxZ);
    }

    /** The highest sequence written so far, so the logger resumes numbering after a restart. */
    public CompletableFuture<Long> maxSequence(int warId) {
        return db.call(connection -> maxSequence(connection, warId));
    }

    public long maxSequence(Connection connection, int warId) throws SQLException {
        return queryOneSync(connection,
                "SELECT COALESCE(MAX(sequence), 0) AS max_sequence FROM war_block_log WHERE war_id = ?",
                rs -> rs.getLong("max_sequence"), warId).orElse(0L);
    }

    /**
     * Drops a war's log.
     *
     * <p>Only safe once rollback has completed and been verified; until then this table is
     * the only record of what to restore.
     */
    public CompletableFuture<Integer> deleteByWar(int warId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM war_block_log WHERE war_id = ?", warId));
    }
}
