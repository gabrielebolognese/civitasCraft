package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.SiegeUnitRow;

/**
 * {@code siege_units}, SPEC 29.4.
 *
 * <p>A dead unit's row survives, because SPEC 29.4 refunds nothing: the points it cost stay spent
 * for the rest of the war. {@link #spentPoints} therefore sums every row of the war rather than
 * only the living ones, which is the difference between a siege that can be replaced as fast as it
 * dies and one an attacker has to commit to.
 */
public final class SiegeUnitDao extends Dao<SiegeUnitRow> {

    private static final String COLUMNS =
            "id, war_id, city_id, type, points, world, x, y, z, alive, bought_at";

    public SiegeUnitDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "siege_units";
    }

    @Override
    protected SiegeUnitRow map(ResultSet rs) throws SQLException {
        return new SiegeUnitRow(
                rs.getInt("id"),
                rs.getInt("war_id"),
                rs.getInt("city_id"),
                rs.getString("type"),
                rs.getInt("points"),
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getBoolean("alive"),
                rs.getLong("bought_at"));
    }

    public CompletableFuture<List<SiegeUnitRow>> findByWar(int warId) {
        return queryList("SELECT " + COLUMNS + " FROM siege_units WHERE war_id = ?", warId);
    }

    /** Every unit of every unresolved war, for the startup cache. */
    public CompletableFuture<List<SiegeUnitRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM siege_units ORDER BY id");
    }

    /**
     * What a city has committed to this war, dead units included.
     *
     * <p>Summing only the living would let an attacker replace losses indefinitely inside one
     * budget, which turns SPEC 29.2's cap into a rate limit.
     */
    public CompletableFuture<Integer> spentPoints(int warId, int cityId) {
        return db.call(connection -> spentPoints(connection, warId, cityId));
    }

    public int spentPoints(Connection connection, int warId, int cityId) throws SQLException {
        return queryOneSync(connection,
                "SELECT COALESCE(SUM(points), 0) AS total FROM siege_units "
                        + "WHERE war_id = ? AND city_id = ?",
                rs -> rs.getInt("total"), warId, cityId).orElse(0);
    }

    public CompletableFuture<Integer> insert(SiegeUnitRow row) {
        return db.call(connection -> insert(connection, row));
    }

    public int insert(Connection connection, SiegeUnitRow row) throws SQLException {
        long id = insertSync(connection,
                "INSERT INTO siege_units (war_id, city_id, type, points, world, x, y, z, alive, "
                        + "bought_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.warId(), row.cityId(), row.type(), row.points(), row.world(), row.x(),
                row.y(), row.z(), row.alive(), row.boughtAt());
        return Math.toIntExact(id);
    }

    public CompletableFuture<Integer> markDead(int id) {
        return db.call(connection -> updateSync(connection,
                "UPDATE siege_units SET alive = ? WHERE id = ?", false, id));
    }

    /** SPEC 29.5: a fallen camp takes that city's whole siege with it. */
    public CompletableFuture<Integer> markCityDead(int warId, int cityId) {
        return db.call(connection -> updateSync(connection,
                "UPDATE siege_units SET alive = ? WHERE war_id = ? AND city_id = ? AND alive = ?",
                false, warId, cityId, true));
    }

    /** SPEC 29.4: nothing outlives its war. */
    public CompletableFuture<Integer> deleteByWar(int warId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM siege_units WHERE war_id = ?", warId));
    }
}
