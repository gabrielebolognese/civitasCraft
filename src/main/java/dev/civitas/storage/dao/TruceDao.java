package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.AllianceRow;
import dev.civitas.storage.row.TruceRow;

/**
 * {@code truces}, SPEC 3.9.
 *
 * <p>Stored once per pair with the lower city id first, like {@link AllianceDao}, so a truce
 * blocks war declaration in both directions as SPEC 14.3 requires.
 */
public final class TruceDao extends Dao<TruceRow> {

    private static final String COLUMNS = "city_a_id, city_b_id, expires_at";

    public TruceDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "truces";
    }

    @Override
    protected TruceRow map(ResultSet rs) throws SQLException {
        return new TruceRow(
                rs.getInt("city_a_id"),
                rs.getInt("city_b_id"),
                rs.getLong("expires_at"));
    }

    /** An unexpired truce between the two cities, if one exists. */
    public CompletableFuture<Optional<TruceRow>> findActive(int cityId, int otherCityId, long now) {
        return db.call(connection -> findActive(connection, cityId, otherCityId, now));
    }

    public Optional<TruceRow> findActive(Connection connection, int cityId, int otherCityId, long now)
            throws SQLException {
        int[] pair = AllianceRow.normalisedPair(cityId, otherCityId);
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM truces "
                        + "WHERE city_a_id = ? AND city_b_id = ? AND expires_at > ?",
                this::map, pair[0], pair[1], now);
    }

    /** Every truce still running, for the startup cache. */
    public CompletableFuture<List<TruceRow>> findAllActive(long now) {
        return queryList("SELECT " + COLUMNS + " FROM truces WHERE expires_at > ?", now);
    }

    public CompletableFuture<List<TruceRow>> findByCity(int cityId, long now) {
        return queryList("SELECT " + COLUMNS + " FROM truces "
                + "WHERE (city_a_id = ? OR city_b_id = ?) AND expires_at > ?", cityId, cityId, now);
    }

    /**
     * Creates or extends a truce.
     *
     * <p>SPEC 14.3 says a truce cannot be cancelled early, so this never shortens an existing
     * one; a shorter expiry than the current one is ignored.
     */
    public CompletableFuture<Integer> upsert(int cityId, int otherCityId, long expiresAt) {
        return db.transaction(connection -> upsert(connection, cityId, otherCityId, expiresAt));
    }

    public int upsert(Connection connection, int cityId, int otherCityId, long expiresAt)
            throws SQLException {
        int[] pair = AllianceRow.normalisedPair(cityId, otherCityId);
        Optional<TruceRow> existing = queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM truces WHERE city_a_id = ? AND city_b_id = ?",
                this::map, pair[0], pair[1]);

        if (existing.isEmpty()) {
            return updateSync(connection,
                    "INSERT INTO truces (" + COLUMNS + ") VALUES (?, ?, ?)",
                    pair[0], pair[1], expiresAt);
        }
        if (existing.get().expiresAt() >= expiresAt) {
            return 0;
        }
        return updateSync(connection,
                "UPDATE truces SET expires_at = ? WHERE city_a_id = ? AND city_b_id = ?",
                expiresAt, pair[0], pair[1]);
    }

    /** Housekeeping for truces that have run out. */
    public CompletableFuture<Integer> deleteExpired(long now) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM truces WHERE expires_at <= ?", now));
    }

    public CompletableFuture<Integer> deleteByCity(int cityId) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM truces WHERE city_a_id = ? OR city_b_id = ?", cityId, cityId));
    }
}
