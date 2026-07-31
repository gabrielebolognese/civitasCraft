package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.CityBanRow;

/** {@code city_bans}, migration V2. Backs the SPEC 5.2 join precondition. */
public final class CityBanDao extends Dao<CityBanRow> {

    private static final String COLUMNS = "city_id, banned_uuid, banned_by, reason, banned_at";

    public CityBanDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "city_bans";
    }

    @Override
    protected CityBanRow map(ResultSet rs) throws SQLException {
        return new CityBanRow(
                rs.getInt("city_id"),
                uuid(rs, "banned_uuid"),
                uuid(rs, "banned_by"),
                rs.getString("reason"),
                rs.getLong("banned_at"));
    }

    public CompletableFuture<List<CityBanRow>> findByCity(int cityId) {
        return db.call(connection -> findByCity(connection, cityId));
    }

    public List<CityBanRow> findByCity(Connection connection, int cityId) throws SQLException {
        return queryListSync(connection,
                "SELECT " + COLUMNS + " FROM city_bans WHERE city_id = ? ORDER BY banned_at DESC",
                this::map, cityId);
    }

    public CompletableFuture<Optional<CityBanRow>> find(int cityId, UUID player) {
        return db.call(connection -> find(connection, cityId, player));
    }

    public Optional<CityBanRow> find(Connection connection, int cityId, UUID player) throws SQLException {
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM city_bans WHERE city_id = ? AND banned_uuid = ?",
                this::map, cityId, player);
    }

    /** Every ban is loaded once at startup so a join check never touches the database. */
    public CompletableFuture<List<CityBanRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM city_bans");
    }

    /** Re-banning an already-banned player refreshes the reason rather than failing. */
    public CompletableFuture<Integer> upsert(CityBanRow row) {
        return db.transaction(connection -> upsert(connection, row));
    }

    public int upsert(Connection connection, CityBanRow row) throws SQLException {
        int updated = updateSync(connection,
                "UPDATE city_bans SET banned_by = ?, reason = ?, banned_at = ? "
                        + "WHERE city_id = ? AND banned_uuid = ?",
                row.bannedBy(), row.reason(), row.bannedAt(), row.cityId(), row.bannedUuid());
        if (updated > 0) {
            return updated;
        }
        return updateSync(connection,
                "INSERT INTO city_bans (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)",
                row.cityId(), row.bannedUuid(), row.bannedBy(), row.reason(), row.bannedAt());
    }

    public CompletableFuture<Integer> delete(int cityId, UUID player) {
        return db.call(connection -> delete(connection, cityId, player));
    }

    public int delete(Connection connection, int cityId, UUID player) throws SQLException {
        return updateSync(connection,
                "DELETE FROM city_bans WHERE city_id = ? AND banned_uuid = ?", cityId, player);
    }

    public CompletableFuture<Integer> deleteByCity(int cityId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM city_bans WHERE city_id = ?", cityId));
    }
}
