package dev.civitas.storage.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.DefenseUnitRow;

/**
 * {@code defense_units}, SPEC 3.9 and SPEC 12.
 *
 * <p>The row is authoritative, not the entity. SPEC 12.5 stores this row's id in the mob's
 * persistent data so a unit lost to chunk corruption or {@code /kill} can be respawned from
 * here on chunk load.
 */
public final class DefenseUnitDao extends Dao<DefenseUnitRow> {

    private static final String COLUMNS =
            "id, city_id, type, world, spawn_x, spawn_y, spawn_z, upkeep, active";

    public DefenseUnitDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "defense_units";
    }

    @Override
    protected DefenseUnitRow map(ResultSet rs) throws SQLException {
        return new DefenseUnitRow(
                rs.getInt("id"),
                rs.getInt("city_id"),
                rs.getString("type"),
                rs.getString("world"),
                rs.getDouble("spawn_x"),
                rs.getDouble("spawn_y"),
                rs.getDouble("spawn_z"),
                money(rs, "upkeep"),
                rs.getBoolean("active"));
    }

    public CompletableFuture<Optional<DefenseUnitRow>> findById(int id) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM defense_units WHERE id = ?", this::map, id));
    }

    public CompletableFuture<List<DefenseUnitRow>> findByCity(int cityId) {
        return db.call(connection -> findByCity(connection, cityId));
    }

    public List<DefenseUnitRow> findByCity(Connection connection, int cityId) throws SQLException {
        return queryListSync(connection,
                "SELECT " + COLUMNS + " FROM defense_units WHERE city_id = ?", this::map, cityId);
    }

    /** Active units only, which is what counts against the SPEC 12.4 cap and costs upkeep. */
    public CompletableFuture<List<DefenseUnitRow>> findActiveByCity(int cityId) {
        return queryList("SELECT " + COLUMNS + " FROM defense_units WHERE city_id = ? AND active = ?",
                cityId, true);
    }

    /** Every active unit, read at startup to reconcile entities against the database. */
    public CompletableFuture<List<DefenseUnitRow>> findAllActive() {
        return queryList("SELECT " + COLUMNS + " FROM defense_units WHERE active = ?", true);
    }

    public CompletableFuture<Integer> insert(DefenseUnitRow row) {
        return db.call(connection -> insert(connection, row));
    }

    /** @return the generated unit id, which goes into the entity's persistent data */
    public int insert(Connection connection, DefenseUnitRow row) throws SQLException {
        long id = insertSync(connection,
                "INSERT INTO defense_units (city_id, type, world, spawn_x, spawn_y, spawn_z, "
                        + "upkeep, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.cityId(), row.type(), row.world(), row.spawnX(), row.spawnY(), row.spawnZ(),
                row.upkeep(), row.active());
        return Math.toIntExact(id);
    }

    /** Deactivates or reactivates a unit, SPEC 12.3's unpaid-upkeep behaviour. */
    public CompletableFuture<Integer> setActive(int unitId, boolean active) {
        return db.call(connection -> setActive(connection, unitId, active));
    }

    public int setActive(Connection connection, int unitId, boolean active) throws SQLException {
        return updateSync(connection,
                "UPDATE defense_units SET active = ? WHERE id = ?", active, unitId);
    }

    public CompletableFuture<Integer> setActiveByCity(int cityId, boolean active) {
        return db.call(connection -> updateSync(connection,
                "UPDATE defense_units SET active = ? WHERE city_id = ?", active, cityId));
    }

    /** Total daily upkeep owed by a city's active units. */
    public CompletableFuture<BigDecimal> totalUpkeep(int cityId) {
        return db.call(connection -> totalUpkeep(connection, cityId));
    }

    public BigDecimal totalUpkeep(Connection connection, int cityId) throws SQLException {
        return queryOneSync(connection,
                "SELECT COALESCE(SUM(upkeep), 0) AS total FROM defense_units "
                        + "WHERE city_id = ? AND active = ?",
                rs -> money(rs, "total"), cityId, true).orElse(BigDecimal.ZERO);
    }

    /** A killed unit is gone for good, SPEC 12.3. */
    public CompletableFuture<Integer> delete(int unitId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM defense_units WHERE id = ?", unitId));
    }

    public CompletableFuture<Integer> deleteByCity(int cityId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM defense_units WHERE city_id = ?", cityId));
    }
}
