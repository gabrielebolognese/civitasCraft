package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.WaystationChunkRow;
import dev.civitas.storage.row.WaystationRow;

/**
 * {@code waystations} and {@code waystation_chunks}, SPEC 39.10.
 *
 * <p>Two unique indexes carry rules the service also checks, deliberately. The one on
 * {@code (city_id, world)} is SPEC 39.10's "1 per city per resource world", and the one on
 * {@code (world, chunk_x, chunk_z)} is the same physical guarantee SPEC 3.4 gives city claims.
 * A service check settles the ordinary case with a good message; the index settles the race two
 * members can create in one tick, which no amount of checking in front of it can.
 */
public final class WaystationDao extends Dao<WaystationRow> {

    private static final String COLUMNS =
            "id, city_id, world, created_at, warp_x, warp_y, warp_z, warp_yaw, warp_pitch";

    private static final String CHUNK_COLUMNS =
            "id, waystation_id, world, chunk_x, chunk_z, claimed_at, cost_paid";

    public WaystationDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "waystations";
    }

    @Override
    protected WaystationRow map(ResultSet rs) throws SQLException {
        return new WaystationRow(
                rs.getInt("id"),
                rs.getInt("city_id"),
                rs.getString("world"),
                rs.getLong("created_at"),
                rs.getDouble("warp_x"),
                rs.getDouble("warp_y"),
                rs.getDouble("warp_z"),
                rs.getFloat("warp_yaw"),
                rs.getFloat("warp_pitch"));
    }

    private WaystationChunkRow mapChunk(ResultSet rs) throws SQLException {
        return new WaystationChunkRow(
                rs.getLong("id"),
                rs.getInt("waystation_id"),
                rs.getString("world"),
                rs.getInt("chunk_x"),
                rs.getInt("chunk_z"),
                rs.getLong("claimed_at"),
                money(rs, "cost_paid"));
    }

    // ==================================================================================
    // Waystations
    // ==================================================================================

    public CompletableFuture<List<WaystationRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM waystations ORDER BY id");
    }

    public Optional<WaystationRow> findByIdSync(Connection connection, int id)
            throws SQLException {
        return queryOneSync(connection, "SELECT " + COLUMNS + " FROM waystations WHERE id = ?",
                this::map, id);
    }

    public int insertSync(Connection connection, WaystationRow row) throws SQLException {
        return (int) insertSync(connection,
                "INSERT INTO waystations (city_id, world, created_at, warp_x, warp_y, warp_z, "
                        + "warp_yaw, warp_pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.cityId(), row.world(), row.createdAt(), row.warpX(), row.warpY(),
                row.warpZ(), row.warpYaw(), row.warpPitch());
    }

    public int updateWarpSync(Connection connection, WaystationRow row) throws SQLException {
        return updateSync(connection,
                "UPDATE waystations SET warp_x = ?, warp_y = ?, warp_z = ?, warp_yaw = ?, "
                        + "warp_pitch = ? WHERE id = ?",
                row.warpX(), row.warpY(), row.warpZ(), row.warpYaw(), row.warpPitch(), row.id());
    }

    public int deleteSync(Connection connection, int id) throws SQLException {
        return updateSync(connection, "DELETE FROM waystations WHERE id = ?", id);
    }

    /** Every waystation a city holds. Used to enforce SPEC 39.10's one-per-world limit. */
    public List<WaystationRow> findByCitySync(Connection connection, int cityId)
            throws SQLException {
        return queryListSync(connection,
                "SELECT " + COLUMNS + " FROM waystations WHERE city_id = ? ORDER BY id",
                this::map, cityId);
    }

    // ==================================================================================
    // Chunks
    // ==================================================================================

    public CompletableFuture<List<WaystationChunkRow>> findAllChunks() {
        return db.call(connection -> queryListSync(connection,
                "SELECT " + CHUNK_COLUMNS + " FROM waystation_chunks ORDER BY id",
                this::mapChunk));
    }

    public List<WaystationChunkRow> findChunksSync(Connection connection, int waystationId)
            throws SQLException {
        return queryListSync(connection,
                "SELECT " + CHUNK_COLUMNS + " FROM waystation_chunks "
                        + "WHERE waystation_id = ? ORDER BY id",
                this::mapChunk, waystationId);
    }

    public Optional<WaystationChunkRow> findChunkAtSync(Connection connection, String world,
                                                        int chunkX, int chunkZ)
            throws SQLException {
        return queryOneSync(connection,
                "SELECT " + CHUNK_COLUMNS + " FROM waystation_chunks "
                        + "WHERE world = ? AND chunk_x = ? AND chunk_z = ?",
                this::mapChunk, world, chunkX, chunkZ);
    }

    public long insertChunkSync(Connection connection, WaystationChunkRow row)
            throws SQLException {
        return insertSync(connection,
                "INSERT INTO waystation_chunks (waystation_id, world, chunk_x, chunk_z, "
                        + "claimed_at, cost_paid) VALUES (?, ?, ?, ?, ?, ?)",
                row.waystationId(), row.world(), row.chunkX(), row.chunkZ(),
                row.claimedAt(), row.costPaid());
    }

    public int deleteChunkSync(Connection connection, String world, int chunkX, int chunkZ)
            throws SQLException {
        return updateSync(connection,
                "DELETE FROM waystation_chunks WHERE world = ? AND chunk_x = ? AND chunk_z = ?",
                world, chunkX, chunkZ);
    }

    /** Drops every chunk of a waystation, for a delete. */
    public int deleteChunksOfSync(Connection connection, int waystationId) throws SQLException {
        return updateSync(connection,
                "DELETE FROM waystation_chunks WHERE waystation_id = ?", waystationId);
    }
}
