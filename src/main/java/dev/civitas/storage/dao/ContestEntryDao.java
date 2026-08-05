package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.ContestEntryRow;
import dev.civitas.storage.row.RankedCityRow;

/**
 * {@code contest_entries}, SPEC 3.9, with the columns V9 added for SPEC 13.4.
 *
 * <p>The unique index on {@code (contest_id, city_id)} enforces SPEC 13.4's "a city may
 * submit one entry per contest" at the database, not by trusting the command layer.
 */
public final class ContestEntryDao extends Dao<ContestEntryRow> {

    private static final String COLUMNS = "id, contest_id, city_id, plot_region, submitted_at, "
            + "score, disqualified, disqualified_reason, placement";

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
                rs.getDouble("score"),
                rs.getBoolean("disqualified"),
                rs.getString("disqualified_reason"),
                nullableInt(rs, "placement"));
    }

    public CompletableFuture<Optional<ContestEntryRow>> findById(int id) {
        return db.call(connection -> findById(connection, id));
    }

    public Optional<ContestEntryRow> findById(Connection connection, int id) throws SQLException {
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM contest_entries WHERE id = ?", this::map, id);
    }

    public CompletableFuture<Optional<ContestEntryRow>> findByCity(int contestId, int cityId) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM contest_entries WHERE contest_id = ? AND city_id = ?",
                this::map, contestId, cityId));
    }

    /** Every entry of a contest, submitted or not, for the admin view. */
    public CompletableFuture<List<ContestEntryRow>> findAll(int contestId) {
        return queryList("SELECT " + COLUMNS + " FROM contest_entries WHERE contest_id = ? "
                + "ORDER BY id", contestId);
    }

    /**
     * The entries that are actually in the running: submitted, and not disqualified.
     *
     * <p>What voting lists and what scoring ranks. Ordered by score, then by who submitted
     * first: SPEC 13.4 does not say how to break a tie, and rewarding the city that finished
     * earlier is the one tiebreak that cannot be gamed after the fact.
     */
    public CompletableFuture<List<ContestEntryRow>> findSubmitted(int contestId) {
        return db.call(connection -> findSubmitted(connection, contestId));
    }

    public List<ContestEntryRow> findSubmitted(Connection connection, int contestId)
            throws SQLException {
        return queryListSync(connection,
                "SELECT " + COLUMNS + " FROM contest_entries "
                        + "WHERE contest_id = ? AND submitted_at IS NOT NULL AND disqualified = 0 "
                        + "ORDER BY score DESC, submitted_at ASC",
                this::map, contestId);
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
        return db.call(connection -> updateScore(connection, entryId, score));
    }

    public int updateScore(Connection connection, int entryId, double score) throws SQLException {
        return updateSync(connection,
                "UPDATE contest_entries SET score = ? WHERE id = ?", score, entryId);
    }

    /** Records where an entry finished, once, when the contest is scored. */
    public int updatePlacement(Connection connection, int entryId, Integer placement)
            throws SQLException {
        return updateSync(connection,
                "UPDATE contest_entries SET placement = ? WHERE id = ?", placement, entryId);
    }

    /**
     * SPEC 9.4.6 {@code /ca contest disqualify}.
     *
     * <p>Marks rather than deletes. The votes reference the entry, and an admin decision that
     * erased its own evidence would be the one thing here that cannot be audited (SPEC 1.5).
     */
    public CompletableFuture<Integer> disqualify(int entryId, String reason) {
        return db.call(connection -> updateSync(connection,
                "UPDATE contest_entries SET disqualified = 1, disqualified_reason = ? WHERE id = ?",
                reason, entryId));
    }

    /**
     * Cumulative contest points per city, for SPEC 13.3's Contest Champions board.
     *
     * <p>A city's points are the scores of its finished, qualifying entries added up. SPEC
     * 13.3 asks for "cumulative contest points" and defines them nowhere, so this is a choice:
     * it rewards entering often and doing well, and it cannot be gamed by a city that enters
     * once and never again. Recorded in OPEN_QUESTIONS.md.
     *
     * <p>The score is read with {@code getDouble}, never through the money reader: it is a
     * 1-to-10 rating, and on SQLite the money reader would divide it by a hundred.
     */
    public CompletableFuture<List<RankedCityRow>> topByCumulativeScore(int limit) {
        return db.call(connection -> queryListSync(connection,
                "SELECT e.city_id AS city_id, c.name AS name, SUM(e.score) AS total "
                        + "FROM contest_entries e "
                        + "JOIN contests t ON t.id = e.contest_id "
                        + "JOIN cities c ON c.id = e.city_id "
                        + "WHERE t.state = 'FINISHED' AND e.disqualified = 0 "
                        + "AND e.submitted_at IS NOT NULL AND e.score > 0 "
                        + "GROUP BY e.city_id, c.name "
                        + "ORDER BY total DESC, c.name ASC LIMIT ?",
                rs -> new RankedCityRow(
                        rs.getInt("city_id"),
                        rs.getString("name"),
                        java.math.BigDecimal.valueOf(rs.getDouble("total"))),
                limit));
    }

    public CompletableFuture<Integer> delete(int entryId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM contest_entries WHERE id = ?", entryId));
    }
}
