package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.WarChunkHashRow;

/** {@code war_chunk_hashes}, added by V11 for the SPEC 11.8.4 failsafe. */
public final class WarChunkHashDao extends Dao<WarChunkHashRow> {

    private static final String COLUMNS =
            "war_id, world, chunk_x, chunk_z, hash_before, hash_after";

    public WarChunkHashDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "war_chunk_hashes";
    }

    @Override
    protected WarChunkHashRow map(ResultSet rs) throws SQLException {
        return new WarChunkHashRow(
                rs.getInt("war_id"),
                rs.getString("world"),
                rs.getInt("chunk_x"),
                rs.getInt("chunk_z"),
                rs.getLong("hash_before"),
                nullableLong(rs, "hash_after"));
    }

    public CompletableFuture<List<WarChunkHashRow>> findByWar(int warId) {
        return queryList("SELECT " + COLUMNS + " FROM war_chunk_hashes WHERE war_id = ?", warId);
    }

    /**
     * Chunks whose after-hash disagrees with their before-hash.
     *
     * <p>What {@code /ca war rollbackstatus} lists. A chunk with no after-hash is excluded:
     * it has not been checked yet, which is different from having failed.
     */
    public CompletableFuture<List<WarChunkHashRow>> findMismatched(int warId) {
        return queryList("SELECT " + COLUMNS + " FROM war_chunk_hashes "
                + "WHERE war_id = ? AND hash_after IS NOT NULL AND hash_after <> hash_before",
                warId);
    }

    /** Records the pre-war hashes for a whole zone, in one transaction. */
    public CompletableFuture<Integer> insertAll(List<WarChunkHashRow> rows) {
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return db.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO war_chunk_hashes (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)")) {
                for (WarChunkHashRow row : rows) {
                    bind(statement, row.warId(), row.world(), row.chunkX(), row.chunkZ(),
                            row.hashBefore(), row.hashAfter());
                    statement.addBatch();
                }
                int written = 0;
                for (int result : statement.executeBatch()) {
                    written += Math.max(result, 0);
                }
                return written;
            }
        });
    }

    public CompletableFuture<Integer> recordAfter(int warId, String world, int chunkX, int chunkZ,
                                                  long hashAfter) {
        return db.call(connection -> updateSync(connection,
                "UPDATE war_chunk_hashes SET hash_after = ? "
                        + "WHERE war_id = ? AND world = ? AND chunk_x = ? AND chunk_z = ?",
                hashAfter, warId, world, chunkX, chunkZ));
    }

    public CompletableFuture<Integer> deleteByWar(int warId) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM war_chunk_hashes WHERE war_id = ?", warId));
    }
}
