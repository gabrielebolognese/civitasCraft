package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.PlayerNoticeRow;

/**
 * {@code player_notices}, SPEC 17.1 case 1's "notified on next login".
 *
 * <p>Delivered notices are deleted rather than flagged. A notice exists to be shown once, and
 * a table of rows that have already served their purpose is a table that only grows.
 */
public final class PlayerNoticeDao extends Dao<PlayerNoticeRow> {

    private static final String COLUMNS = "id, uuid, message_key, placeholders, created_at";

    public PlayerNoticeDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "player_notices";
    }

    @Override
    protected PlayerNoticeRow map(ResultSet rs) throws SQLException {
        return new PlayerNoticeRow(
                rs.getLong("id"),
                uuid(rs, "uuid"),
                rs.getString("message_key"),
                rs.getString("placeholders"),
                rs.getLong("created_at"));
    }

    public long insert(Connection connection, PlayerNoticeRow row) throws SQLException {
        return insertSync(connection,
                "INSERT INTO player_notices (uuid, message_key, placeholders, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                row.uuid(), row.messageKey(), row.placeholders(), row.createdAt());
    }

    public CompletableFuture<Long> insert(PlayerNoticeRow row) {
        return db.call(connection -> insert(connection, row));
    }

    /** Everything waiting for one player, oldest first so they read in the order they happened. */
    public CompletableFuture<List<PlayerNoticeRow>> findFor(UUID uuid) {
        return queryList("SELECT " + COLUMNS + " FROM player_notices WHERE uuid = ? "
                + "ORDER BY created_at, id", uuid);
    }

    /** Clears a player's queue once it has been shown to them. */
    public CompletableFuture<Integer> deleteFor(UUID uuid) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM player_notices WHERE uuid = ?", uuid));
    }
}
