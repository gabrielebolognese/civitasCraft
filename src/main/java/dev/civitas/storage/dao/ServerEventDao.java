package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.ServerEventRow;

/** {@code server_events}, added by V10. One row per run of a SPEC 13.5 event. */
public final class ServerEventDao extends Dao<ServerEventRow> {

    private static final String COLUMNS = "id, event_key, starts_at, ends_at, ended_at, announced";

    public ServerEventDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "server_events";
    }

    @Override
    protected ServerEventRow map(ResultSet rs) throws SQLException {
        return new ServerEventRow(
                rs.getInt("id"),
                rs.getString("event_key"),
                rs.getLong("starts_at"),
                rs.getLong("ends_at"),
                nullableLong(rs, "ended_at"),
                rs.getBoolean("announced"));
    }

    /**
     * The most recent event that was never closed.
     *
     * <p>Asked once at startup. A row here whose window has already passed is an event the
     * server was down for, and the caller closes it rather than resuming it.
     */
    public CompletableFuture<Optional<ServerEventRow>> findOpen() {
        return queryOne("SELECT " + COLUMNS + " FROM server_events "
                + "WHERE ended_at IS NULL ORDER BY starts_at DESC LIMIT 1");
    }

    /** When this event last ran, for the SPEC 13.5 repeat cooldown. */
    public CompletableFuture<Optional<ServerEventRow>> findLatestOf(String eventKey) {
        return queryOne("SELECT " + COLUMNS + " FROM server_events "
                + "WHERE event_key = ? ORDER BY starts_at DESC LIMIT 1", eventKey);
    }

    public CompletableFuture<List<ServerEventRow>> findRecent(int limit) {
        return queryList("SELECT " + COLUMNS + " FROM server_events "
                + "ORDER BY starts_at DESC LIMIT ?", limit);
    }

    public CompletableFuture<Integer> insert(ServerEventRow row) {
        return db.call(connection -> insert(connection, row));
    }

    /** @return the generated event id */
    public int insert(Connection connection, ServerEventRow row) throws SQLException {
        long id = insertSync(connection,
                "INSERT INTO server_events (event_key, starts_at, ends_at, ended_at, announced) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.eventKey(), row.startsAt(), row.endsAt(), row.endedAt(), row.announced());
        return Math.toIntExact(id);
    }

    public CompletableFuture<Integer> markAnnounced(int id) {
        return db.call(connection -> updateSync(connection,
                "UPDATE server_events SET announced = 1 WHERE id = ?", id));
    }

    /**
     * Closes an event.
     *
     * <p>Guarded on {@code ended_at IS NULL} so a second close writes nothing. Closing is what
     * takes the effects off, and a run that could be closed twice is a run whose end could be
     * announced twice.
     */
    public CompletableFuture<Integer> markEnded(int id, long endedAt) {
        return db.call(connection -> updateSync(connection,
                "UPDATE server_events SET ended_at = ? WHERE id = ? AND ended_at IS NULL",
                endedAt, id));
    }
}
