package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.ContestRow;

/** {@code contests}, SPEC 3.9. One biweekly cycle, SPEC 13.4. */
public final class ContestDao extends Dao<ContestRow> {

    private static final String COLUMNS = "id, theme, starts_at, ends_at, state";

    public ContestDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "contests";
    }

    @Override
    protected ContestRow map(ResultSet rs) throws SQLException {
        return new ContestRow(
                rs.getInt("id"),
                rs.getString("theme"),
                rs.getLong("starts_at"),
                rs.getLong("ends_at"),
                rs.getString("state"));
    }

    public CompletableFuture<Optional<ContestRow>> findById(int id) {
        return db.call(connection ->
                queryOneSync(connection, "SELECT " + COLUMNS + " FROM contests WHERE id = ?", this::map, id));
    }

    /** The most recently started contest that has not finished. */
    public CompletableFuture<Optional<ContestRow>> findCurrent(long now) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM contests WHERE starts_at <= ? AND ends_at > ? "
                        + "ORDER BY starts_at DESC LIMIT 1",
                this::map, now, now));
    }

    /**
     * The most recent contest that has not been scored, whether or not its window has closed.
     *
     * <p>What the cycle loads at startup, and deliberately not {@link #findCurrent}: a contest
     * whose day 14 passed while the server was down is exactly the one that still needs its
     * votes tallied and its prizes paid, and {@code findCurrent} filters it out for having
     * ended.
     */
    public CompletableFuture<Optional<ContestRow>> findUnfinished() {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM contests WHERE state <> ? "
                        + "ORDER BY starts_at DESC LIMIT 1",
                this::map, "FINISHED"));
    }

    public CompletableFuture<List<ContestRow>> findRecent(int limit) {
        return queryList("SELECT " + COLUMNS + " FROM contests ORDER BY starts_at DESC LIMIT ?", limit);
    }

    public CompletableFuture<Integer> insert(ContestRow row) {
        return db.call(connection -> insert(connection, row));
    }

    /** @return the generated contest id */
    public int insert(Connection connection, ContestRow row) throws SQLException {
        long id = insertSync(connection,
                "INSERT INTO contests (theme, starts_at, ends_at, state) VALUES (?, ?, ?, ?)",
                row.theme(), row.startsAt(), row.endsAt(), row.state());
        return Math.toIntExact(id);
    }

    public CompletableFuture<Integer> updateState(int contestId, String state) {
        return db.call(connection -> updateState(connection, contestId, state));
    }

    public int updateState(Connection connection, int contestId, String state) throws SQLException {
        return updateSync(connection,
                "UPDATE contests SET state = ? WHERE id = ?", state, contestId);
    }
}
