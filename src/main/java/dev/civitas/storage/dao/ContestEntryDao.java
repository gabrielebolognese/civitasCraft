package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.ContestEntryRow;

/**
 * {@code contest_entries}, SPEC 3.9.
 *
 * <p>The unique index on {@code (contest_id, city_id)} enforces SPEC 13.4's "a city may
 * submit one entry per contest" at the database, not by trusting the command layer.
 */
public final class ContestEntryDao extends Dao<ContestEntryRow> {

    private static final String COLUMNS =
            "id, contest_id, city_id, plot_region, submitted_at, score";

    public ContestEntryDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "contest_entries";
    }

    @Override
    protected ContestEntryRow map(ResultSet rs) throws SQLException {
        return new ContestEntryRow(
                rs.getInt("id"),
                rs.getInt("contest_id"),
                rs.getInt("city_id"),
                rs.getString("plot_region"),
                nullableLong(rs, "submitted_at"),
                rs.getDouble("score"));
    }

    public CompletableFuture<Optional<ContestEntryRow>> findById(int id) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM contest_entries WHERE id = ?", this::map, id));
    }

    public CompletableFuture<Optional<ContestEntryRow>> findByCity(int contestId, int cityId) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM contest_entries WHERE contest_id = ? AND city_id = ?",
                this::map, contestId, cityId));
    }

    /** Submitted entries only, highest score first, for results and the visit list. */
    public CompletableFuture<List<ContestEntryRow>> findSubmitted(int contestId) {
        return queryList("SELECT " + COLUMNS + " FROM contest_entries "
                + "WHERE contest_id = ? AND submitted_at IS NOT NULL ORDER BY score DESC", contestId);
    }

    public CompletableFuture<Integer> insert(ContestEntryRow row) {
        return db.call(connection -> insert(connection, row));
    }

    /** @return the generated entry id */
    public int insert(Connection connection, ContestEntryRow row) throws SQLException {
        long id = insertSync(connection,
                "INSERT INTO contest_entries (contest_id, city_id, plot_region, submitted_at, score) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.contestId(), row.cityId(), row.plotRegion(), row.submittedAt(), row.score());
        return Math.toIntExact(id);
    }

    public CompletableFuture<Integer> updateRegion(int entryId, String plotRegion) {
        return db.call(connection -> updateSync(connection,
                "UPDATE contest_entries SET plot_region = ? WHERE id = ?", plotRegion, entryId));
    }

    public CompletableFuture<Integer> markSubmitted(int entryId, long submittedAt) {
        return db.call(connection -> updateSync(connection,
                "UPDATE contest_entries SET submitted_at = ? WHERE id = ?", submittedAt, entryId));
    }

    public CompletableFuture<Integer> updateScore(int entryId, double score) {
        return db.call(connection -> updateSync(connection,
                "UPDATE contest_entries SET score = ? WHERE id = ?", score, entryId));
    }

    /** Removes a disqualified entry, SPEC 9.4.6 {@code /ca contest disqualify}. */
    public CompletableFuture<Integer> delete(int entryId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM contest_entries WHERE id = ?", entryId));
    }
}
