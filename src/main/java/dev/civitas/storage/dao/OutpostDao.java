package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.OutpostRow;

/** {@code outposts}, SPEC 3.5. */
public final class OutpostDao extends Dao<OutpostRow> {

    private static final String COLUMNS =
            "id, city_id, name, tp_x, tp_y, tp_z, tp_yaw, tp_pitch, created_at";

    public OutpostDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "outposts";
    }

    @Override
    protected OutpostRow map(ResultSet rs) throws SQLException {
        return new OutpostRow(
                rs.getInt("id"),
                rs.getInt("city_id"),
                rs.getString("name"),
                rs.getDouble("tp_x"),
                rs.getDouble("tp_y"),
                rs.getDouble("tp_z"),
                rs.getFloat("tp_yaw"),
                rs.getFloat("tp_pitch"),
                rs.getLong("created_at"));
    }

    /** Every outpost on the server, for the startup cache. */
    public CompletableFuture<List<OutpostRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM outposts ORDER BY id");
    }

    public CompletableFuture<Optional<OutpostRow>> findById(int id) {
        return db.call(connection ->
                queryOneSync(connection, "SELECT " + COLUMNS + " FROM outposts WHERE id = ?", this::map, id));
    }

    public CompletableFuture<List<OutpostRow>> findByCity(int cityId) {
        return db.call(connection -> findByCity(connection, cityId));
    }

    public List<OutpostRow> findByCity(Connection connection, int cityId) throws SQLException {
        return queryListSync(connection,
                "SELECT " + COLUMNS + " FROM outposts WHERE city_id = ? ORDER BY created_at",
                this::map, cityId);
    }

    public CompletableFuture<Optional<OutpostRow>> findByName(int cityId, String name) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM outposts WHERE city_id = ? AND LOWER(name) = LOWER(?)",
                this::map, cityId, name));
    }

    public CompletableFuture<Integer> insert(OutpostRow row) {
        return db.call(connection -> insert(connection, row));
    }

    /** @return the generated outpost id */
    public int insert(Connection connection, OutpostRow row) throws SQLException {
        long id = insertSync(connection,
                "INSERT INTO outposts (city_id, name, tp_x, tp_y, tp_z, tp_yaw, tp_pitch, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.cityId(), row.name(), row.tpX(), row.tpY(), row.tpZ(),
                row.tpYaw(), row.tpPitch(), row.createdAt());
        return Math.toIntExact(id);
    }

    public CompletableFuture<Integer> update(OutpostRow row) {
        return db.call(connection -> update(connection, row));
    }

    public int update(Connection connection, OutpostRow row) throws SQLException {
        return updateSync(connection,
                "UPDATE outposts SET name = ?, tp_x = ?, tp_y = ?, tp_z = ?, tp_yaw = ?, tp_pitch = ? "
                        + "WHERE id = ?",
                row.name(), row.tpX(), row.tpY(), row.tpZ(), row.tpYaw(), row.tpPitch(), row.id());
    }

    public CompletableFuture<Integer> delete(int outpostId) {
        return db.call(connection -> delete(connection, outpostId));
    }

    public int delete(Connection connection, int outpostId) throws SQLException {
        return updateSync(connection, "DELETE FROM outposts WHERE id = ?", outpostId);
    }

    public CompletableFuture<Integer> deleteByCity(int cityId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM outposts WHERE city_id = ?", cityId));
    }
}
