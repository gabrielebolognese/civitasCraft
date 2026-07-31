package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.ClaimRow;

/**
 * {@code claims}, SPEC 3.4.
 *
 * <p>The unique index on {@code (world, chunk_x, chunk_z)} is what makes SPEC 17.2 case 15
 * safe: if two cities try to claim the same chunk in the same tick, the second INSERT fails
 * at the database rather than relying on application-level locking. Callers must therefore
 * treat a constraint violation from {@link #insert} as "someone else got it first" and
 * refund, not as an internal error.
 */
public final class ClaimDao extends Dao<ClaimRow> {

    private static final String COLUMNS =
            "id, city_id, world, chunk_x, chunk_z, claimed_at, claimed_by, cost_paid, type, outpost_id";

    public ClaimDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "claims";
    }

    @Override
    protected ClaimRow map(ResultSet rs) throws SQLException {
        return new ClaimRow(
                rs.getLong("id"),
                rs.getInt("city_id"),
                rs.getString("world"),
                rs.getInt("chunk_x"),
                rs.getInt("chunk_z"),
                rs.getLong("claimed_at"),
                uuid(rs, "claimed_by"),
                money(rs, "cost_paid"),
                rs.getString("type"),
                nullableInt(rs, "outpost_id"));
    }

    /**
     * Every claim in the database.
     *
     * <p>Read once at startup to populate the in-memory chunk cache described in SPEC 2.3,
     * because claim lookup runs on every block event and must never touch the database.
     */
    public CompletableFuture<List<ClaimRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM claims");
    }

    public CompletableFuture<Optional<ClaimRow>> findAt(String world, int chunkX, int chunkZ) {
        return db.call(connection -> findAt(connection, world, chunkX, chunkZ));
    }

    public Optional<ClaimRow> findAt(Connection connection, String world, int chunkX, int chunkZ)
            throws SQLException {
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?",
                this::map, world, chunkX, chunkZ);
    }

    public CompletableFuture<List<ClaimRow>> findByCity(int cityId) {
        return db.call(connection -> findByCity(connection, cityId));
    }

    public List<ClaimRow> findByCity(Connection connection, int cityId) throws SQLException {
        return queryListSync(connection,
                "SELECT " + COLUMNS + " FROM claims WHERE city_id = ? ORDER BY claimed_at",
                this::map, cityId);
    }

    public CompletableFuture<List<ClaimRow>> findByOutpost(int outpostId) {
        return queryList("SELECT " + COLUMNS + " FROM claims WHERE outpost_id = ?", outpostId);
    }

    /** Claim count per city, the SPEC 13.3 Cities by Size metric. */
    public CompletableFuture<Integer> countByCity(int cityId) {
        return db.call(connection -> countByCity(connection, cityId));
    }

    public int countByCity(Connection connection, int cityId) throws SQLException {
        return queryOneSync(connection,
                "SELECT COUNT(*) AS total FROM claims WHERE city_id = ?",
                rs -> rs.getInt("total"), cityId).orElse(0);
    }

    public CompletableFuture<Long> insert(ClaimRow row) {
        return db.call(connection -> insert(connection, row));
    }

    /** @return the generated claim id */
    public long insert(Connection connection, ClaimRow row) throws SQLException {
        return insertSync(connection,
                "INSERT INTO claims (city_id, world, chunk_x, chunk_z, claimed_at, claimed_by, "
                        + "cost_paid, type, outpost_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.cityId(), row.world(), row.chunkX(), row.chunkZ(), row.claimedAt(),
                row.claimedBy(), row.costPaid(), row.type(), row.outpostId());
    }

    /** Changes a claim's type, used when an outpost converts to a normal claim (SPEC 7.4). */
    public CompletableFuture<Integer> updateType(long claimId, String type, Integer outpostId) {
        return db.call(connection -> updateType(connection, claimId, type, outpostId));
    }

    public int updateType(Connection connection, long claimId, String type, Integer outpostId)
            throws SQLException {
        return updateSync(connection,
                "UPDATE claims SET type = ?, outpost_id = ? WHERE id = ?", type, outpostId, claimId);
    }

    /** Moves a chunk to another city, SPEC 9.4.3 {@code /ca claim transfer}. */
    public CompletableFuture<Integer> updateCity(long claimId, int cityId) {
        return db.call(connection -> updateSync(connection,
                "UPDATE claims SET city_id = ? WHERE id = ?", cityId, claimId));
    }

    public CompletableFuture<Integer> deleteAt(String world, int chunkX, int chunkZ) {
        return db.call(connection -> deleteAt(connection, world, chunkX, chunkZ));
    }

    public int deleteAt(Connection connection, String world, int chunkX, int chunkZ) throws SQLException {
        return updateSync(connection,
                "DELETE FROM claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?",
                world, chunkX, chunkZ);
    }

    public CompletableFuture<Integer> deleteByCity(int cityId) {
        return db.call(connection -> deleteByCity(connection, cityId));
    }

    public int deleteByCity(Connection connection, int cityId) throws SQLException {
        return updateSync(connection, "DELETE FROM claims WHERE city_id = ?", cityId);
    }
}
