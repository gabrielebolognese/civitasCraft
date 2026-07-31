package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.CityRankRow;

/** {@code city_ranks}, SPEC 3.3. */
public final class CityRankDao extends Dao<CityRankRow> {

    private static final String COLUMNS = "id, city_id, name, weight, permissions, is_default";

    public CityRankDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "city_ranks";
    }

    @Override
    protected CityRankRow map(ResultSet rs) throws SQLException {
        return new CityRankRow(
                rs.getInt("id"),
                rs.getInt("city_id"),
                rs.getString("name"),
                rs.getInt("weight"),
                rs.getLong("permissions"),
                rs.getBoolean("is_default"));
    }

    public CompletableFuture<Optional<CityRankRow>> findById(int id) {
        return db.call(connection ->
                queryOneSync(connection, "SELECT " + COLUMNS + " FROM city_ranks WHERE id = ?", this::map, id));
    }

    /** Every rank of every city, read once at startup to populate the city cache. */
    public CompletableFuture<List<CityRankRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM city_ranks ORDER BY city_id, weight DESC");
    }

    /** Highest weight first, so the caller sees Mayor before Recruit. */
    public CompletableFuture<List<CityRankRow>> findByCity(int cityId) {
        return db.call(connection -> findByCity(connection, cityId));
    }

    public List<CityRankRow> findByCity(Connection connection, int cityId) throws SQLException {
        return queryListSync(connection,
                "SELECT " + COLUMNS + " FROM city_ranks WHERE city_id = ? ORDER BY weight DESC",
                this::map, cityId);
    }

    /** The rank new joiners receive, SPEC 3.3. */
    public CompletableFuture<Optional<CityRankRow>> findDefault(int cityId) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM city_ranks WHERE city_id = ? AND is_default = ? LIMIT 1",
                this::map, cityId, true));
    }

    public CompletableFuture<Integer> insert(CityRankRow row) {
        return db.call(connection -> insert(connection, row));
    }

    /** @return the generated rank id */
    public int insert(Connection connection, CityRankRow row) throws SQLException {
        long id = insertSync(connection,
                "INSERT INTO city_ranks (city_id, name, weight, permissions, is_default) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.cityId(), row.name(), row.weight(), row.permissions(), row.isDefault());
        return Math.toIntExact(id);
    }

    public CompletableFuture<Integer> update(CityRankRow row) {
        return db.call(connection -> update(connection, row));
    }

    public int update(Connection connection, CityRankRow row) throws SQLException {
        return updateSync(connection,
                "UPDATE city_ranks SET name = ?, weight = ?, permissions = ?, is_default = ? WHERE id = ?",
                row.name(), row.weight(), row.permissions(), row.isDefault(), row.id());
    }

    /**
     * Writes only the bitmask.
     *
     * <p>SPEC 17.5 case 65 relies on permissions being a single column: two members editing
     * the same rank cannot half-apply each other's change, the later write simply wins.
     */
    public CompletableFuture<Integer> updatePermissions(int rankId, long permissions) {
        return db.call(connection -> updatePermissions(connection, rankId, permissions));
    }

    public int updatePermissions(Connection connection, int rankId, long permissions)
            throws SQLException {
        return updateSync(connection,
                "UPDATE city_ranks SET permissions = ? WHERE id = ?", permissions, rankId);
    }

    public CompletableFuture<Integer> delete(int rankId) {
        return db.call(connection -> delete(connection, rankId));
    }

    public int delete(Connection connection, int rankId) throws SQLException {
        return updateSync(connection, "DELETE FROM city_ranks WHERE id = ?", rankId);
    }

    public CompletableFuture<Integer> deleteByCity(int cityId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM city_ranks WHERE city_id = ?", cityId));
    }
}
