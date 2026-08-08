package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.PlayerToggleRow;

/**
 * {@code player_toggles}, SPEC 23.6's notification preferences.
 *
 * <p>Only rows a player has actually changed. Everything else is the category's default, which
 * lives in {@code ToggleCategory} rather than being written eighteen times per player.
 */
public final class PlayerToggleDao extends Dao<PlayerToggleRow> {

    private static final String COLUMNS = "uuid, category, enabled";

    public PlayerToggleDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "player_toggles";
    }

    @Override
    protected PlayerToggleRow map(ResultSet rs) throws SQLException {
        return new PlayerToggleRow(uuid(rs, "uuid"), rs.getString("category"),
                rs.getBoolean("enabled"));
    }

    /** Everything one player has changed. */
    public CompletableFuture<List<PlayerToggleRow>> findFor(UUID player) {
        return queryList("SELECT " + COLUMNS + " FROM player_toggles WHERE uuid = ?",
                player.toString());
    }

    /** Update first, insert if that changed nothing — the dialect-safe upsert. */
    public int upsertSync(Connection connection, PlayerToggleRow row) throws SQLException {
        int updated = updateSync(connection,
                "UPDATE player_toggles SET enabled = ? WHERE uuid = ? AND category = ?",
                row.enabled(), row.uuid().toString(), row.category());
        if (updated > 0) {
            return updated;
        }
        return updateSync(connection,
                "INSERT INTO player_toggles (" + COLUMNS + ") VALUES (?, ?, ?)",
                row.uuid().toString(), row.category(), row.enabled());
    }

    public CompletableFuture<Integer> upsert(PlayerToggleRow row) {
        return db.call(connection -> upsertSync(connection, row));
    }

    /** Puts a category back to its default by forgetting the override. */
    public CompletableFuture<Integer> clear(UUID player, String category) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM player_toggles WHERE uuid = ? AND category = ?",
                player.toString(), category));
    }
}
