package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.AllianceRow;

/**
 * {@code alliances}, SPEC 3.9.
 *
 * <p>An alliance is symmetric, so it is stored once with the lower city id first. Every
 * method normalises its arguments, which means a caller cannot accidentally create the same
 * alliance twice by passing the ids the other way round.
 */
public final class AllianceDao extends Dao<AllianceRow> {

    private static final String COLUMNS = "city_a_id, city_b_id, state, formed_at";

    public AllianceDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "alliances";
    }

    @Override
    protected AllianceRow map(ResultSet rs) throws SQLException {
        return new AllianceRow(
                rs.getInt("city_a_id"),
                rs.getInt("city_b_id"),
                rs.getString("state"),
                rs.getLong("formed_at"));
    }

    public CompletableFuture<Optional<AllianceRow>> find(int cityId, int otherCityId) {
        return db.call(connection -> find(connection, cityId, otherCityId));
    }

    public Optional<AllianceRow> find(Connection connection, int cityId, int otherCityId)
            throws SQLException {
        int[] pair = AllianceRow.normalisedPair(cityId, otherCityId);
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM alliances WHERE city_a_id = ? AND city_b_id = ?",
                this::map, pair[0], pair[1]);
    }

    /** Every alliance a city is part of, whichever side of the pair it sits on. */
    public CompletableFuture<List<AllianceRow>> findByCity(int cityId) {
        return queryList("SELECT " + COLUMNS + " FROM alliances WHERE city_a_id = ? OR city_b_id = ?",
                cityId, cityId);
    }

    public CompletableFuture<Integer> insert(int cityId, int otherCityId, String state, long formedAt) {
        return db.call(connection -> insert(connection, cityId, otherCityId, state, formedAt));
    }

    public int insert(Connection connection, int cityId, int otherCityId, String state, long formedAt)
            throws SQLException {
        int[] pair = AllianceRow.normalisedPair(cityId, otherCityId);
        return updateSync(connection,
                "INSERT INTO alliances (" + COLUMNS + ") VALUES (?, ?, ?, ?)",
                pair[0], pair[1], state, formedAt);
    }

    /** Used for the SPEC 14.2 break notice period, which changes state before removal. */
    public CompletableFuture<Integer> updateState(int cityId, int otherCityId, String state) {
        return db.call(connection -> {
            int[] pair = AllianceRow.normalisedPair(cityId, otherCityId);
            return updateSync(connection,
                    "UPDATE alliances SET state = ? WHERE city_a_id = ? AND city_b_id = ?",
                    state, pair[0], pair[1]);
        });
    }

    public CompletableFuture<Integer> delete(int cityId, int otherCityId) {
        return db.call(connection -> delete(connection, cityId, otherCityId));
    }

    public int delete(Connection connection, int cityId, int otherCityId) throws SQLException {
        int[] pair = AllianceRow.normalisedPair(cityId, otherCityId);
        return updateSync(connection,
                "DELETE FROM alliances WHERE city_a_id = ? AND city_b_id = ?", pair[0], pair[1]);
    }

    public CompletableFuture<Integer> deleteByCity(int cityId) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM alliances WHERE city_a_id = ? OR city_b_id = ?", cityId, cityId));
    }
}
