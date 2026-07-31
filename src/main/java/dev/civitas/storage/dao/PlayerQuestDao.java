package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.PlayerQuestRow;

/** {@code player_quests}, SPEC 3.9. Daily quests and weekly challenge progress, SPEC 13.1. */
public final class PlayerQuestDao extends Dao<PlayerQuestRow> {

    private static final String COLUMNS =
            "id, uuid, quest_id, progress, assigned_at, completed_at";

    public PlayerQuestDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "player_quests";
    }

    @Override
    protected PlayerQuestRow map(ResultSet rs) throws SQLException {
        return new PlayerQuestRow(
                rs.getLong("id"),
                uuid(rs, "uuid"),
                rs.getString("quest_id"),
                rs.getInt("progress"),
                rs.getLong("assigned_at"),
                nullableLong(rs, "completed_at"));
    }

    /** Quests assigned to a player at or after {@code assignedAfter}, newest first. */
    public CompletableFuture<List<PlayerQuestRow>> findForPlayer(UUID uuid, long assignedAfter) {
        return queryList("SELECT " + COLUMNS + " FROM player_quests "
                + "WHERE uuid = ? AND assigned_at >= ? ORDER BY assigned_at DESC", uuid, assignedAfter);
    }

    public CompletableFuture<Long> insert(PlayerQuestRow row) {
        return db.call(connection -> insert(connection, row));
    }

    public long insert(Connection connection, PlayerQuestRow row) throws SQLException {
        return insertSync(connection,
                "INSERT INTO player_quests (uuid, quest_id, progress, assigned_at, completed_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.uuid(), row.questId(), row.progress(), row.assignedAt(), row.completedAt());
    }

    /** Adds to progress in one statement, so two qualifying actions in the same tick both count. */
    public CompletableFuture<Integer> addProgress(long questRowId, int delta) {
        return db.call(connection -> updateSync(connection,
                "UPDATE player_quests SET progress = progress + ? WHERE id = ?", delta, questRowId));
    }

    public CompletableFuture<Integer> markCompleted(long questRowId, long completedAt) {
        return db.call(connection -> updateSync(connection,
                "UPDATE player_quests SET completed_at = ? WHERE id = ? AND completed_at IS NULL",
                completedAt, questRowId));
    }

    /** Housekeeping for quests older than the retention window. */
    public CompletableFuture<Integer> deleteAssignedBefore(long cutoff) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM player_quests WHERE assigned_at < ?", cutoff));
    }
}
