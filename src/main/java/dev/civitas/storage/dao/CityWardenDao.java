package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.CityWardenRow;

/**
 * {@code city_wardens}, SPEC 28.
 *
 * <p>One row per city that owns a Warden, and the primary key on {@code city_id} is what makes
 * SPEC 28.2's "one per city" a physical fact rather than a check somebody might forget: two
 * members buying in the same tick cannot both insert.
 */
public final class CityWardenDao extends Dao<CityWardenRow> {

    private static final String COLUMNS = "city_id, unit_id, purchased_at, recovering_until";

    public CityWardenDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "city_wardens";
    }

    @Override
    protected CityWardenRow map(ResultSet rs) throws SQLException {
        return new CityWardenRow(
                rs.getInt("city_id"),
                rs.getInt("unit_id"),
                rs.getLong("purchased_at"),
                nullableLong(rs, "recovering_until"));
    }

    /** Every Warden on the server, for the startup cache. */
    public CompletableFuture<List<CityWardenRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM city_wardens ORDER BY city_id");
    }

    public CompletableFuture<Optional<CityWardenRow>> findByCity(int cityId) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM city_wardens WHERE city_id = ?", this::map, cityId));
    }

    /**
     * Claims the city's one Warden slot.
     *
     * <p>Takes a connection so the insert, the {@code defense_units} row and the 750,000 C charge
     * land in one transaction. A city charged for a Warden it did not get would be the single most
     * expensive failure available in this plugin.
     */
    public int insert(Connection connection, CityWardenRow row) throws SQLException {
        return updateSync(connection,
                "INSERT INTO city_wardens (city_id, unit_id, purchased_at, recovering_until) "
                        + "VALUES (?, ?, ?, ?)",
                row.cityId(), row.unitId(), row.purchasedAt(), row.recoveringUntil());
    }

    /** SPEC 28.6: driven underground, or back on the surface when the deadline passes. */
    public CompletableFuture<Integer> setRecoveringUntil(int cityId, Long until) {
        return db.call(connection -> updateSync(connection,
                "UPDATE city_wardens SET recovering_until = ? WHERE city_id = ?", until, cityId));
    }

    /**
     * Every Warden whose recovery has run out, so the sweep does not have to read them all.
     *
     * <p>{@code < ?} rather than {@code <= ?}: a Warden whose deadline is this exact millisecond
     * has not finished recovering, which is the same reading {@link
     * dev.civitas.core.defense.CityWarden.Owned#isRecovering} takes.
     */
    public CompletableFuture<List<CityWardenRow>> findRecovered(long now) {
        return queryList("SELECT " + COLUMNS + " FROM city_wardens "
                + "WHERE recovering_until IS NOT NULL AND recovering_until <= ?", now);
    }

    /** SPEC 28.6 and SPEC 30.2 case 97: killed in a war, so it must be bought again. */
    public CompletableFuture<Integer> delete(int cityId) {
        return db.call(connection -> deleteSync(connection, cityId));
    }

    public int deleteSync(Connection connection, int cityId) throws SQLException {
        return updateSync(connection, "DELETE FROM city_wardens WHERE city_id = ?", cityId);
    }
}
