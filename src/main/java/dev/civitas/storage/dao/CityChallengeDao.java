package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.CityChallengeRow;

/** {@code city_challenges}, added in V5 for SPEC 13.2. */
public final class CityChallengeDao extends Dao<CityChallengeRow> {

    private static final String COLUMNS =
            "id, city_id, challenge_id, progress, target, reward, week_start, completed_at";

    public CityChallengeDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "city_challenges";
    }

    @Override
    protected CityChallengeRow map(ResultSet rs) throws SQLException {
        return new CityChallengeRow(
                rs.getLong("id"),
                rs.getInt("city_id"),
                rs.getString("challenge_id"),
                rs.getLong("progress"),
                rs.getLong("target"),
                money(rs, "reward"),
                rs.getLong("week_start"),
                nullableLong(rs, "completed_at"));
    }

    public CompletableFuture<List<CityChallengeRow>> findForWeek(int cityId, long weekStart) {
        return queryList("SELECT " + COLUMNS + " FROM city_challenges "
                + "WHERE city_id = ? AND week_start = ? ORDER BY id", cityId, weekStart);
    }

    public List<CityChallengeRow> findForWeek(Connection connection, int cityId, long weekStart)
            throws SQLException {
        return queryListSync(connection, "SELECT " + COLUMNS + " FROM city_challenges "
                + "WHERE city_id = ? AND week_start = ? ORDER BY id", this::map, cityId, weekStart);
    }

    public CompletableFuture<Long> insert(CityChallengeRow row) {
        return db.call(connection -> insert(connection, row));
    }

    public long insert(Connection connection, CityChallengeRow row) throws SQLException {
        return insertSync(connection,
                "INSERT INTO city_challenges "
                        + "(city_id, challenge_id, progress, target, reward, week_start, completed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.cityId(), row.challengeId(), row.progress(), row.target(), row.reward(),
                row.weekStart(), row.completedAt());
    }

    /**
     * Adds to pooled progress in one statement.
     *
     * <p>SQL arithmetic rather than read-modify-write, because SPEC 13.2 pools progress across
     * every member and two of them harvesting in the same tick must both count.
     */
    public CompletableFuture<Integer> addProgress(long rowId, long delta) {
        return db.call(connection -> updateSync(connection,
                "UPDATE city_challenges SET progress = progress + ? "
                        + "WHERE id = ? AND completed_at IS NULL", delta, rowId));
    }

    public CompletableFuture<Integer> markCompleted(long rowId, long completedAt) {
        return db.call(connection -> updateSync(connection,
                "UPDATE city_challenges SET completed_at = ? WHERE id = ? AND completed_at IS NULL",
                completedAt, rowId));
    }

    public CompletableFuture<Integer> deleteBefore(long cutoff) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM city_challenges WHERE week_start < ?", cutoff));
    }
}
