package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.CityInviteRow;

/** {@code city_invites}, SPEC 3.9. */
public final class CityInviteDao extends Dao<CityInviteRow> {

    private static final String COLUMNS = "city_id, invitee_uuid, inviter_uuid, expires_at";

    public CityInviteDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "city_invites";
    }

    @Override
    protected CityInviteRow map(ResultSet rs) throws SQLException {
        return new CityInviteRow(
                rs.getInt("city_id"),
                uuid(rs, "invitee_uuid"),
                uuid(rs, "inviter_uuid"),
                rs.getLong("expires_at"));
    }

    /** Unexpired invites only, so a stale row can never be accepted. */
    public CompletableFuture<List<CityInviteRow>> findPendingFor(UUID invitee, long now) {
        return queryList("SELECT " + COLUMNS + " FROM city_invites "
                + "WHERE invitee_uuid = ? AND expires_at > ?", invitee, now);
    }

    public CompletableFuture<Optional<CityInviteRow>> findPending(int cityId, UUID invitee, long now) {
        return db.call(connection -> findPending(connection, cityId, invitee, now));
    }

    public Optional<CityInviteRow> findPending(Connection connection, int cityId, UUID invitee,
                                               long now) throws SQLException {
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM city_invites "
                        + "WHERE city_id = ? AND invitee_uuid = ? AND expires_at > ?",
                this::map, cityId, invitee, now);
    }

    public CompletableFuture<List<CityInviteRow>> findByCity(int cityId) {
        return queryList("SELECT " + COLUMNS + " FROM city_invites WHERE city_id = ?", cityId);
    }

    /** Replaces any existing invite for the same city and player, refreshing its expiry. */
    public CompletableFuture<Integer> upsert(CityInviteRow row) {
        return db.transaction(connection -> {
            updateSync(connection,
                    "DELETE FROM city_invites WHERE city_id = ? AND invitee_uuid = ?",
                    row.cityId(), row.inviteeUuid());
            return updateSync(connection,
                    "INSERT INTO city_invites (" + COLUMNS + ") VALUES (?, ?, ?, ?)",
                    row.cityId(), row.inviteeUuid(), row.inviterUuid(), row.expiresAt());
        });
    }

    public CompletableFuture<Integer> delete(int cityId, UUID invitee) {
        return db.call(connection -> delete(connection, cityId, invitee));
    }

    public int delete(Connection connection, int cityId, UUID invitee) throws SQLException {
        return updateSync(connection,
                "DELETE FROM city_invites WHERE city_id = ? AND invitee_uuid = ?", cityId, invitee);
    }

    /** Housekeeping: drops invites past their expiry, SPEC 5.2. */
    public CompletableFuture<Integer> deleteExpired(long now) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM city_invites WHERE expires_at <= ?", now));
    }

    public CompletableFuture<Integer> deleteByCity(int cityId) {
        return db.call(connection -> deleteByCity(connection, cityId));
    }

    public int deleteByCity(Connection connection, int cityId) throws SQLException {
        return updateSync(connection, "DELETE FROM city_invites WHERE city_id = ?", cityId);
    }
}
