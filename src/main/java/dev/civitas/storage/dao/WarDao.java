package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.WarRecordRow;
import dev.civitas.storage.row.WarRow;

/** {@code wars}, SPEC 3.7. */
public final class WarDao extends Dao<WarRow> {

    private static final String COLUMNS =
            "id, attacker_city_id, defender_city_id, declared_at, prep_ends_at, war_ends_at, "
                    + "state, attacker_score, defender_score, winner_city_id, wager, "
                    + "rollback_completed_at, rollback_checkpoint_sequence, siege_capacity";

    private static final String INSERT_COLUMNS =
            "attacker_city_id, defender_city_id, declared_at, prep_ends_at, war_ends_at, "
                    + "state, attacker_score, defender_score, winner_city_id, wager, "
                    + "rollback_completed_at, rollback_checkpoint_sequence, siege_capacity";

    public WarDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "wars";
    }

    @Override
    protected WarRow map(ResultSet rs) throws SQLException {
        return new WarRow(
                rs.getInt("id"),
                rs.getInt("attacker_city_id"),
                rs.getInt("defender_city_id"),
                rs.getLong("declared_at"),
                rs.getLong("prep_ends_at"),
                rs.getLong("war_ends_at"),
                rs.getString("state"),
                rs.getInt("attacker_score"),
                rs.getInt("defender_score"),
                nullableInt(rs, "winner_city_id"),
                money(rs, "wager"),
                nullableLong(rs, "rollback_completed_at"),
                nullableLong(rs, "rollback_checkpoint_sequence"),
                rs.getInt("siege_capacity"));
    }

    public CompletableFuture<Optional<WarRow>> findById(int id) {
        return db.call(connection -> findById(connection, id));
    }

    public Optional<WarRow> findById(Connection connection, int id) throws SQLException {
        return queryOneSync(connection, "SELECT " + COLUMNS + " FROM wars WHERE id = ?", this::map, id);
    }

    /**
     * Wars in any of the given states.
     *
     * <p>Called at startup for the SPEC 11.8.5 crash recovery sweep: an {@code ACTIVE} war
     * past its end time must roll back, and a {@code ROLLING_BACK} war must resume from its
     * checkpoint rather than be forgotten.
     */
    /**
     * Money held by SPEC 11.3's war escrow right now.
     *
     * <p>Both wagers, for every war that has been declared and not yet resolved. It is neither in
     * a wallet nor in a treasury, so a circulation figure without it appears to shrink the moment
     * a war is declared — which is the shape of a leak, arriving from a system working correctly.
     */
    public CompletableFuture<java.math.BigDecimal> totalEscrowed() {
        return db.call(connection -> queryOneSync(connection,
                "SELECT COALESCE(SUM(wager), 0) * 2 AS total FROM wars "
                        + "WHERE state IN ('PREP', 'ACTIVE')",
                rs -> money(rs, "total")).orElse(java.math.BigDecimal.ZERO));
    }

    public CompletableFuture<List<WarRow>> findByStates(Collection<String> states) {
        if (states.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String placeholders = states.stream().map(state -> "?").collect(Collectors.joining(", "));
        return queryList("SELECT " + COLUMNS + " FROM wars WHERE state IN (" + placeholders + ") "
                + "ORDER BY war_ends_at", states.toArray());
    }

    /** Every war a city took part in as attacker or defender, newest first. */
    public CompletableFuture<List<WarRow>> findByCity(int cityId, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM wars "
                + "WHERE attacker_city_id = ? OR defender_city_id = ? "
                + "ORDER BY declared_at DESC LIMIT ?", cityId, cityId, limit);
    }

    public CompletableFuture<Integer> insert(WarRow row) {
        return db.call(connection -> insert(connection, row));
    }

    /** @return the generated war id */
    public int insert(Connection connection, WarRow row) throws SQLException {
        long id = insertSync(connection,
                "INSERT INTO wars (" + INSERT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.attackerCityId(), row.defenderCityId(), row.declaredAt(), row.prepEndsAt(),
                row.warEndsAt(), row.state(), row.attackerScore(), row.defenderScore(),
                row.winnerCityId(), row.wager(), row.rollbackCompletedAt(),
                row.rollbackCheckpointSequence(), row.siegeCapacity());
        return Math.toIntExact(id);
    }

    public CompletableFuture<Integer> update(WarRow row) {
        return db.call(connection -> update(connection, row));
    }

    public int update(Connection connection, WarRow row) throws SQLException {
        return updateSync(connection,
                "UPDATE wars SET attacker_city_id = ?, defender_city_id = ?, declared_at = ?, "
                        + "prep_ends_at = ?, war_ends_at = ?, state = ?, attacker_score = ?, "
                        + "defender_score = ?, winner_city_id = ?, wager = ?, "
                        + "rollback_completed_at = ?, rollback_checkpoint_sequence = ?, siege_capacity = ? WHERE id = ?",
                row.attackerCityId(), row.defenderCityId(), row.declaredAt(), row.prepEndsAt(),
                row.warEndsAt(), row.state(), row.attackerScore(), row.defenderScore(),
                row.winnerCityId(), row.wager(), row.rollbackCompletedAt(),
                row.rollbackCheckpointSequence(), row.siegeCapacity(), row.id());
    }

    public CompletableFuture<Integer> updateState(int warId, String state) {
        return db.call(connection -> updateState(connection, warId, state));
    }

    public int updateState(Connection connection, int warId, String state) throws SQLException {
        return updateSync(connection, "UPDATE wars SET state = ? WHERE id = ?", state, warId);
    }

    /**
     * Records rollback progress, SPEC 11.8.5.
     *
     * <p>Written every {@code war.rollback.checkpoint-every-blocks} restored blocks, and it
     * is the only thing standing between a crash mid-rollback and a griefed city staying
     * griefed, so it is a single narrow statement that cannot fail for an unrelated reason.
     */
    public CompletableFuture<Integer> updateRollbackCheckpoint(int warId, long sequence) {
        return db.call(connection -> updateRollbackCheckpoint(connection, warId, sequence));
    }

    public int updateRollbackCheckpoint(Connection connection, int warId, long sequence)
            throws SQLException {
        return updateSync(connection,
                "UPDATE wars SET rollback_checkpoint_sequence = ? WHERE id = ?", sequence, warId);
    }

    /**
     * Every city's war record, for SPEC 13.3's War Record board.
     *
     * <p>Derived from the resolved wars rather than from a counter, so it cannot drift from
     * the wars it describes. SPEC 13.3 ranks by wins with losses as the tiebreaker, which is
     * the ordering here: more wins first, then fewer losses.
     *
     * <p>Only {@code RESOLVED} wars count. A cancelled or declined war was never fought, and a
     * war whose rollback failed has not finished being dealt with.
     */
    public CompletableFuture<List<WarRecordRow>> findRecords(int limit) {
        return findRecords(limit, 0);
    }

    /**
     * Ranked war records, SPEC 21.4 F4.
     *
     * <p>"A war only counts toward the leaderboard if the losing side scored at least 25% of
     * the winner's score. Collusive wars with a walkover score are recorded but not ranked."
     * Two friendly cities alternating staged wins is otherwise a way to farm the board, and
     * the 21-day cooldown only slows it rather than stopping it.
     *
     * <p>The filter is on the <b>board</b>, not on the war: an excluded war is still resolved,
     * still paid out, and still in {@code /war history}. It simply does not rank, which is what
     * "recorded but not ranked" asks for.
     *
     * <p>{@code CASE} rather than {@code MIN}/{@code LEAST}, because the scalar two-argument
     * form is spelled differently on SQLite and MySQL and this file has to run on both.
     *
     * @param minLoserPercent the loser's share of the winner's score, 0 to rank every war
     */
    public CompletableFuture<List<WarRecordRow>> findRecords(int limit, int minLoserPercent) {
        String loser = "CASE WHEN w.attacker_score < w.defender_score "
                + "THEN w.attacker_score ELSE w.defender_score END";
        String winner = "CASE WHEN w.attacker_score > w.defender_score "
                + "THEN w.attacker_score ELSE w.defender_score END";
        return db.call(connection -> queryListSync(connection,
                "SELECT c.id AS city_id, c.name AS name, "
                        + "SUM(CASE WHEN w.winner_city_id = c.id THEN 1 ELSE 0 END) AS wins, "
                        + "SUM(CASE WHEN w.winner_city_id IS NOT NULL "
                        + "AND w.winner_city_id <> c.id THEN 1 ELSE 0 END) AS losses "
                        + "FROM cities c "
                        + "JOIN wars w ON (w.attacker_city_id = c.id OR w.defender_city_id = c.id) "
                        + "WHERE w.state = ? "
                        + "AND (" + loser + ") * 100 >= (" + winner + ") * ? "
                        + "GROUP BY c.id, c.name "
                        + "HAVING wins > 0 OR losses > 0 "
                        + "ORDER BY wins DESC, losses ASC, c.name ASC LIMIT ?",
                rs -> new WarRecordRow(
                        rs.getInt("city_id"),
                        rs.getString("name"),
                        rs.getInt("wins"),
                        rs.getInt("losses")),
                "RESOLVED", minLoserPercent, limit));
    }

    /**
     * Wars a city won recently, for the SPEC 11.9 market bonus.
     *
     * <p>Read once at startup so the bonus survives a restart; the sell path itself reads a
     * cached answer, because SPEC 2.1 forbids a query there.
     */
    public CompletableFuture<List<WarRow>> findWonSince(long since) {
        // Keyed on when the war ended, not on when its rollback finished. SPEC 11.9 gives the
        // winner seven days from winning, and a restore that takes an hour must not eat an
        // hour of them. Any state with a winner qualifies, so a war still being rolled back
        // has already granted its bonus.
        return queryList("SELECT " + COLUMNS + " FROM wars "
                + "WHERE winner_city_id IS NOT NULL AND war_ends_at >= ?", since);
    }

    /**
     * Whether these two cities have fought inside the cooldown window.
     *
     * <p>SPEC 11.3 precondition 12, the anti-harassment rule SPEC 15.2 calls out by name.
     * Asked of storage rather than of the registry, because the registry drops a war once it
     * is finished and the cooldown outlives it by three weeks.
     */
    public CompletableFuture<Boolean> hasFoughtSince(int cityA, int cityB, long since) {
        return db.call(connection -> !queryListSync(connection,
                "SELECT id FROM wars WHERE declared_at >= ? "
                        + "AND ((attacker_city_id = ? AND defender_city_id = ?) "
                        + "OR (attacker_city_id = ? AND defender_city_id = ?)) LIMIT 1",
                rs -> rs.getInt("id"), since, cityA, cityB, cityB, cityA).isEmpty());
    }
}
