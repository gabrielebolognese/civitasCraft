package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.SeasonResultRow;
import dev.civitas.storage.row.SeasonRow;

/** {@code seasons}, {@code season_results} and {@code season_baselines}, SPEC 35. */
public final class SeasonDao extends Dao<SeasonRow> {

    private static final String COLUMNS = "id, name, theme, starts_at, ends_at, state, ended_at";

    public SeasonDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "seasons";
    }

    @Override
    protected SeasonRow map(ResultSet rs) throws SQLException {
        return new SeasonRow(rs.getInt("id"), rs.getString("name"), rs.getString("theme"),
                rs.getLong("starts_at"), rs.getLong("ends_at"), rs.getString("state"),
                nullableLong(rs, "ended_at"));
    }

    public CompletableFuture<Optional<SeasonRow>> findRunning() {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM seasons WHERE state = 'RUNNING' "
                        + "ORDER BY starts_at DESC LIMIT 1", this::map));
    }

    /** Newest first, which is the order a history is read in. */
    public CompletableFuture<List<SeasonRow>> findFinished(int limit) {
        return queryList("SELECT " + COLUMNS + " FROM seasons WHERE state = 'FINISHED' "
                + "ORDER BY ends_at DESC LIMIT ?", limit);
    }

    public CompletableFuture<Integer> insert(SeasonRow row) {
        return db.call(connection -> Math.toIntExact(insertSync(connection,
                "INSERT INTO seasons (name, theme, starts_at, ends_at, state, ended_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                row.name(), row.theme(), row.startsAt(), row.endsAt(), row.state(),
                row.endedAt())));
    }

    public CompletableFuture<Integer> finish(int seasonId, long endedAt) {
        return db.call(connection -> updateSync(connection,
                "UPDATE seasons SET state = 'FINISHED', ended_at = ? "
                        + "WHERE id = ? AND state = 'RUNNING'", endedAt, seasonId));
    }

    public CompletableFuture<Integer> extend(int seasonId, long newEnd) {
        return db.call(connection -> updateSync(connection,
                "UPDATE seasons SET ends_at = ? WHERE id = ? AND state = 'RUNNING'",
                newEnd, seasonId));
    }

    // ==================================================================================
    // The Hall of Fame, SPEC 35.2
    // ==================================================================================

    /** Opens a season inside a caller's transaction, so its baselines land with it. */
    public int insertSync(Connection connection, String name, String theme, long startsAt,
                          long endsAt, String state) throws SQLException {
        return Math.toIntExact(insertSync(connection,
                "INSERT INTO seasons (name, theme, starts_at, ends_at, state, ended_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                name, theme, startsAt, endsAt, state, null));
    }

    /** Closes one inside a caller's transaction, so the Hall of Fame lands with it. */
    public int finishSync(Connection connection, int seasonId, long endedAt) throws SQLException {
        return updateSync(connection,
                "UPDATE seasons SET state = 'FINISHED', ended_at = ? "
                        + "WHERE id = ? AND state = 'RUNNING'", endedAt, seasonId);
    }

    public int insertResult(Connection connection, SeasonResultRow row) throws SQLException {
        return updateSync(connection,
                "INSERT INTO season_results (season_id, board, position, holder_uuid, "
                        + "holder_name, value) VALUES (?, ?, ?, ?, ?, ?)",
                row.seasonId(), row.board(), row.position(), row.holderUuid(), row.holderName(),
                row.value());
    }

    public CompletableFuture<List<SeasonResultRow>> findResults(int seasonId) {
        return db.call(connection -> queryListSync(connection,
                "SELECT id, season_id, board, position, holder_uuid, holder_name, value "
                        + "FROM season_results WHERE season_id = ? ORDER BY board ASC, "
                        + "position ASC",
                rs -> new SeasonResultRow(rs.getLong("id"), rs.getInt("season_id"),
                        rs.getString("board"), rs.getInt("position"),
                        uuid(rs, "holder_uuid"), rs.getString("holder_name"),
                        rs.getString("value")),
                seasonId));
    }

    // ==================================================================================
    // Baselines, which are what makes a reset possible without destroying anything
    // ==================================================================================

    /**
     * Stores what every counter read when the season opened.
     *
     * <p>A season value is the lifetime figure minus this. Resetting the counter itself would
     * destroy the lifetime figure — which SPEC 35.2 lists among the things a season never touches.
     */
    public int insertBaseline(Connection connection, int seasonId, String board, String subject,
                              long value) throws SQLException {
        return updateSync(connection,
                "INSERT INTO season_baselines (season_id, board, subject, value) "
                        + "VALUES (?, ?, ?, ?)", seasonId, board, subject, value);
    }

    public CompletableFuture<Map<String, Long>> findBaselines(int seasonId, String board) {
        return db.call(connection -> {
            Map<String, Long> baselines = new java.util.HashMap<>();
            queryListSync(connection,
                    "SELECT subject, value FROM season_baselines "
                            + "WHERE season_id = ? AND board = ?",
                    rs -> Map.entry(rs.getString("subject"), rs.getLong("value")),
                    seasonId, board).forEach(entry -> baselines.put(entry.getKey(),
                    entry.getValue()));
            return baselines;
        });
    }

    /** Exposed so a season can be opened, baselined and its results written in one transaction. */
    public <R> CompletableFuture<R> transaction(
            dev.civitas.storage.SqlFunction<Connection, R> work) {
        return db.transaction(work);
    }
}
