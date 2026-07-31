package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.CityUpgradeRow;

/** {@code city_upgrades}, SPEC 3.9. Purchased upgrade levels, SPEC 5.7. */
public final class CityUpgradeDao extends Dao<CityUpgradeRow> {

    private static final String COLUMNS = "city_id, upgrade_key, level";

    public CityUpgradeDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "city_upgrades";
    }

    @Override
    protected CityUpgradeRow map(ResultSet rs) throws SQLException {
        return new CityUpgradeRow(
                rs.getInt("city_id"),
                rs.getString("upgrade_key"),
                rs.getInt("level"));
    }

    /**
     * Every purchased upgrade for a city.
     *
     * <p>An upgrade the city has never bought has no row at all, so callers treat a missing
     * key as level 0 rather than expecting a zero row to exist.
     */
    public CompletableFuture<List<CityUpgradeRow>> findByCity(int cityId) {
        return db.call(connection -> findByCity(connection, cityId));
    }

    public List<CityUpgradeRow> findByCity(Connection connection, int cityId) throws SQLException {
        return queryListSync(connection,
                "SELECT " + COLUMNS + " FROM city_upgrades WHERE city_id = ?", this::map, cityId);
    }

    public CompletableFuture<Integer> findLevel(int cityId, String upgradeKey) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT level FROM city_upgrades WHERE city_id = ? AND upgrade_key = ?",
                rs -> rs.getInt("level"), cityId, upgradeKey).orElse(0));
    }

    /** Sets a level, inserting the row on first purchase. */
    public CompletableFuture<Integer> setLevel(int cityId, String upgradeKey, int level) {
        return db.transaction(connection -> setLevel(connection, cityId, upgradeKey, level));
    }

    public int setLevel(Connection connection, int cityId, String upgradeKey, int level)
            throws SQLException {
        int updated = updateSync(connection,
                "UPDATE city_upgrades SET level = ? WHERE city_id = ? AND upgrade_key = ?",
                level, cityId, upgradeKey);
        if (updated > 0) {
            return updated;
        }
        return updateSync(connection,
                "INSERT INTO city_upgrades (" + COLUMNS + ") VALUES (?, ?, ?)",
                cityId, upgradeKey, level);
    }

    public CompletableFuture<Integer> deleteByCity(int cityId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM city_upgrades WHERE city_id = ?", cityId));
    }
}
