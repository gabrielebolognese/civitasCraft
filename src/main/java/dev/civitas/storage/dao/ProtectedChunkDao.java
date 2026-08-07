package dev.civitas.storage.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.ProtectedChunkRow;

/** {@code admin_protected_chunks}, SPEC 9.4.3. */
public final class ProtectedChunkDao extends Dao<ProtectedChunkRow> {

    private static final String COLUMNS =
            "world, chunk_x, chunk_z, protected_by, protected_at, reason";

    public ProtectedChunkDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "admin_protected_chunks";
    }

    @Override
    protected ProtectedChunkRow map(ResultSet rs) throws SQLException {
        return new ProtectedChunkRow(
                rs.getString("world"),
                rs.getInt("chunk_x"),
                rs.getInt("chunk_z"),
                nullableUuid(rs, "protected_by"),
                rs.getLong("protected_at"),
                rs.getString("reason"));
    }

    /**
     * Every protected chunk.
     *
     * <p>Read once at startup into memory, like every other hot lookup: this is consulted on
     * the claim path and on the war-damage path, neither of which may touch storage.
     */
    public CompletableFuture<List<ProtectedChunkRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM admin_protected_chunks");
    }

    public CompletableFuture<Integer> protect(ProtectedChunkRow row) {
        return db.call(connection -> updateSync(connection,
                dialect().insertIgnore("admin_protected_chunks", COLUMNS, "?, ?, ?, ?, ?, ?"),
                row.world(), row.chunkX(), row.chunkZ(), row.protectedBy(), row.protectedAt(),
                row.reason()));
    }

    public CompletableFuture<Integer> unprotect(String world, int chunkX, int chunkZ) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM admin_protected_chunks WHERE world = ? AND chunk_x = ? "
                        + "AND chunk_z = ?",
                world, chunkX, chunkZ));
    }
}
