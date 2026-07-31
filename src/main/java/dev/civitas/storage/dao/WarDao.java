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
import dev.civitas.storage.row.WarRow;

/** {@code wars}, SPEC 3.7. */
public final class WarDao extends Dao<WarRow> {

    private static final String COLUMNS =
            "id, attacker_city_id, defender_city_id, declared_at, prep_ends_at, war_ends_at, "
                    + "state, attacker_score, defender_score, winner_city_id, wager, "
                    + "rollback_completed_at, rollback_checkpoint_sequence";

    private static final String INSERT_COLUMNS =
            "attacker_city_id, defender_city_id, declared_at, prep_ends_at, war_ends_at, "
                    + "state, attacker_score, defender_score, winner_city_id, wager, "
                    + "rollback_completed_at, rollback_checkpoint_sequence";

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
                nullableLong(rs, "rollback_checkpoint_sequence"));
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
                "INSERT INTO wars (" + INSERT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.attackerCityId(), row.defenderCityId(), row.declaredAt(), row.prepEndsAt(),
                row.warEndsAt(), row.state(), row.attackerScore(), row.defenderScore(),
                row.winnerCityId(), row.wager(), row.rollbackCompletedAt(),
                row.rollbackCheckpointSequence());
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
                        + "rollback_completed_at = ?, rollback_checkpoint_sequence = ? WHERE id = ?",
                row.attackerCityId(), row.defenderCityId(), row.declaredAt(), row.prepEndsAt(),
                row.warEndsAt(), row.state(), row.attackerScore(), row.defenderScore(),
                row.winnerCityId(), row.wager(), row.rollbackCompletedAt(),
                row.rollbackCheckpointSequence(), row.id());
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
}
