package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.WarKillRow;

/** {@code war_kills}, SPEC 3.9. Feeds war scoring and the SPEC 8.8 kill feed. */
public final class WarKillDao extends Dao<WarKillRow> {

    private static final String COLUMNS =
            "id, war_id, killer_uuid, victim_uuid, timestamp, location";

    public WarKillDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "war_kills";
    }

    @Override
    protected WarKillRow map(ResultSet rs) throws SQLException {
        return new WarKillRow(
                rs.getLong("id"),
                rs.getInt("war_id"),
                uuid(rs, "killer_uuid"),
                uuid(rs, "victim_uuid"),
                rs.getLong("timestamp"),
                rs.getString("location"));
    }

    public CompletableFuture<Long> insert(WarKillRow row) {
        return db.call(connection -> insert(connection, row));
    }

    public long insert(Connection connection, WarKillRow row) throws SQLException {
        return insertSync(connection,
                "INSERT INTO war_kills (war_id, killer_uuid, victim_uuid, timestamp, location) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.warId(), row.killerUuid(), row.victimUuid(), row.timestamp(), row.location());
    }

    /** Most recent kills first, for the kill feed. */
    public CompletableFuture<List<WarKillRow>> findRecent(int warId, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM war_kills WHERE war_id = ? "
                + "ORDER BY timestamp DESC LIMIT ?", warId, limit);
    }

    public CompletableFuture<List<WarKillRow>> findByWar(int warId) {
        return queryList("SELECT " + COLUMNS + " FROM war_kills WHERE war_id = ? ORDER BY timestamp",
                warId);
    }

    /** Kills by one player in one war, used when settling a SPEC 4.7 bounty. */
    public CompletableFuture<List<WarKillRow>> findByKiller(int warId, UUID killer) {
        return queryList("SELECT " + COLUMNS + " FROM war_kills WHERE war_id = ? AND killer_uuid = ? "
                + "ORDER BY timestamp", warId, killer);
    }

    public CompletableFuture<Integer> deleteByWar(int warId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM war_kills WHERE war_id = ?", warId));
    }
}
