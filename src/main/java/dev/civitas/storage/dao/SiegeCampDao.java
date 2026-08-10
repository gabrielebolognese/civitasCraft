package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.SiegeCampRow;

/**
 * {@code siege_camps}, SPEC 29.5.
 *
 * <p>A destroyed camp is <b>kept</b> rather than deleted, with {@code destroyed_at} stamped. SPEC
 * 29.5 allows exactly one rebuild per war, and the only record that a city has already used it is
 * the row of the camp that was destroyed. Deleting on destruction would hand every attacker
 * unlimited rebuilds, which is the shape of defect the ledger's append-only rule exists to avoid.
 */
public final class SiegeCampDao extends Dao<SiegeCampRow> {

    private static final String COLUMNS =
            "id, war_id, city_id, world, x, y, z, health, placed_at, destroyed_at, rebuilt";

    public SiegeCampDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "siege_camps";
    }

    @Override
    protected SiegeCampRow map(ResultSet rs) throws SQLException {
        return new SiegeCampRow(
                rs.getInt("id"),
                rs.getInt("war_id"),
                rs.getInt("city_id"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getDouble("health"),
                rs.getLong("placed_at"),
                nullableLong(rs, "destroyed_at"),
                rs.getBoolean("rebuilt"));
    }

    /** Every camp of every unresolved war, read at startup to rebuild the cache. */
    public CompletableFuture<List<SiegeCampRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM siege_camps ORDER BY id");
    }

    public CompletableFuture<List<SiegeCampRow>> findByWar(int warId) {
        return queryList("SELECT " + COLUMNS + " FROM siege_camps WHERE war_id = ?", warId);
    }

    public CompletableFuture<Optional<SiegeCampRow>> find(int warId, int cityId) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM siege_camps WHERE war_id = ? AND city_id = ?",
                this::map, warId, cityId));
    }

    public CompletableFuture<Integer> insert(SiegeCampRow row) {
        return db.call(connection -> insert(connection, row));
    }

    public int insert(Connection connection, SiegeCampRow row) throws SQLException {
        long id = insertSync(connection,
                "INSERT INTO siege_camps (war_id, city_id, world, x, y, z, health, placed_at, "
                        + "destroyed_at, rebuilt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.warId(), row.cityId(), row.world(), row.x(), row.y(), row.z(), row.health(),
                row.placedAt(), row.destroyedAt(), row.rebuilt());
        return Math.toIntExact(id);
    }

    /** Checkpoints damage, so a camp half-destroyed before a restart is still half-destroyed. */
    public CompletableFuture<Integer> saveHealth(int id, double health) {
        return db.call(connection -> updateSync(connection,
                "UPDATE siege_camps SET health = ? WHERE id = ?", health, id));
    }

    /**
     * Stamps a camp destroyed, keeping the row.
     *
     * <p>Guarded on {@code destroyed_at IS NULL} so two players landing the killing blow in one
     * tick cannot both score SPEC 29.5's 40 points: the second update changes no rows and the
     * caller sees zero.
     */
    public CompletableFuture<Integer> markDestroyed(int id, long when) {
        return db.call(connection -> updateSync(connection,
                "UPDATE siege_camps SET destroyed_at = ?, health = 0 "
                        + "WHERE id = ? AND destroyed_at IS NULL", when, id));
    }

    /** Reoccupies the row on a rebuild, which is what spends the once-per-war allowance. */
    public CompletableFuture<Integer> rebuild(int id, String world, int x, int y, int z,
                                              double health, long when) {
        return db.call(connection -> updateSync(connection,
                "UPDATE siege_camps SET world = ?, x = ?, y = ?, z = ?, health = ?, "
                        + "placed_at = ?, destroyed_at = NULL, rebuilt = ? WHERE id = ?",
                world, x, y, z, health, when, true, id));
    }

    /** A war that never happened leaves no camp behind, per SPEC 29.4's war-end despawn. */
    public CompletableFuture<Integer> deleteByWar(int warId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM siege_camps WHERE war_id = ?", warId));
    }
}
