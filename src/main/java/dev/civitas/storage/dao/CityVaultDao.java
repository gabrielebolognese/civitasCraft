package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.CityVaultRow;

/** {@code city_vault}, added in V6 for SPEC 5.7 and 9.2. */
public final class CityVaultDao extends Dao<CityVaultRow> {

    private static final String COLUMNS = "city_id, page, contents, updated_at";

    public CityVaultDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "city_vault";
    }

    @Override
    protected CityVaultRow map(ResultSet rs) throws SQLException {
        return new CityVaultRow(
                rs.getInt("city_id"),
                rs.getInt("page"),
                rs.getBytes("contents"),
                rs.getLong("updated_at"));
    }

    public CompletableFuture<Optional<CityVaultRow>> find(int cityId, int page) {
        return queryOne("SELECT " + COLUMNS + " FROM city_vault WHERE city_id = ? AND page = ?",
                cityId, page);
    }

    public CompletableFuture<List<CityVaultRow>> findByCity(int cityId) {
        return queryList("SELECT " + COLUMNS + " FROM city_vault WHERE city_id = ? ORDER BY page",
                cityId);
    }

    /** Writes a page, inserting it the first time it is used. */
    public CompletableFuture<Integer> save(CityVaultRow row) {
        return db.transaction(connection -> save(connection, row));
    }

    public int save(Connection connection, CityVaultRow row) throws SQLException {
        int updated = updateSync(connection,
                "UPDATE city_vault SET contents = ?, updated_at = ? WHERE city_id = ? AND page = ?",
                row.contents(), row.updatedAt(), row.cityId(), row.page());
        if (updated > 0) {
            return updated;
        }
        return updateSync(connection,
                "INSERT INTO city_vault (" + COLUMNS + ") VALUES (?, ?, ?, ?)",
                row.cityId(), row.page(), row.contents(), row.updatedAt());
    }

    public CompletableFuture<Integer> deleteByCity(int cityId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM city_vault WHERE city_id = ?", cityId));
    }
}
