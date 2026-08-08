package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.WarpRow;

/**
 * {@code warps}, SPEC 32.7's public warps.
 *
 * <p>Small and read constantly, so {@code WarpService} caches the whole table and this is only
 * the persistence side — the same cache-first shape every other registry in this plugin uses.
 */
public final class WarpDao extends Dao<WarpRow> {

    private static final String COLUMNS =
            "name, world, x, y, z, yaw, pitch, created_by, created_at, expires_at";

    public WarpDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "warps";
    }

    @Override
    protected WarpRow map(ResultSet rs) throws SQLException {
        String creator = rs.getString("created_by");
        long expiry = rs.getLong("expires_at");
        return new WarpRow(
                rs.getString("name"),
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getFloat("yaw"),
                rs.getFloat("pitch"),
                creator == null ? null : java.util.UUID.fromString(creator),
                rs.getLong("created_at"),
                rs.wasNull() || expiry == 0 ? null : expiry);
    }

    public CompletableFuture<List<WarpRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM warps ORDER BY name");
    }

    public Optional<WarpRow> findSync(Connection connection, String name) throws SQLException {
        return queryOneSync(connection, "SELECT " + COLUMNS + " FROM warps WHERE name = ?",
                this::map, name);
    }

    /** Update first, insert if that changed nothing — the dialect-safe upsert. */
    public int upsertSync(Connection connection, WarpRow row) throws SQLException {
        int updated = updateSync(connection,
                "UPDATE warps SET world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?, "
                        + "created_by = ?, created_at = ?, expires_at = ? WHERE name = ?",
                row.world(), row.x(), row.y(), row.z(), row.yaw(), row.pitch(),
                row.createdBy() == null ? null : row.createdBy().toString(),
                row.createdAt(), row.expiresAt(), row.name());
        if (updated > 0) {
            return updated;
        }
        return updateSync(connection,
                "INSERT INTO warps (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.name(), row.world(), row.x(), row.y(), row.z(), row.yaw(), row.pitch(),
                row.createdBy() == null ? null : row.createdBy().toString(),
                row.createdAt(), row.expiresAt());
    }

    public CompletableFuture<Integer> upsert(WarpRow row) {
        return db.call(connection -> upsertSync(connection, row));
    }

    public CompletableFuture<Integer> delete(String name) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM warps WHERE name = ?", name));
    }

    /** Drops warps whose window has closed, SPEC 40.1. */
    public CompletableFuture<Integer> deleteExpired(long now) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM warps WHERE expires_at IS NOT NULL AND expires_at <= ?", now));
    }
}
