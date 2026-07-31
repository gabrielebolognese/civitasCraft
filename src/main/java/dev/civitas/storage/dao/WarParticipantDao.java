package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.WarParticipantRow;

/** {@code war_participants}, SPEC 3.9. Covers allies who joined under SPEC 11.10. */
public final class WarParticipantDao extends Dao<WarParticipantRow> {

    private static final String COLUMNS = "war_id, city_id, side, is_ally";

    public WarParticipantDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "war_participants";
    }

    @Override
    protected WarParticipantRow map(ResultSet rs) throws SQLException {
        return new WarParticipantRow(
                rs.getInt("war_id"),
                rs.getInt("city_id"),
                rs.getString("side"),
                rs.getBoolean("is_ally"));
    }

    public CompletableFuture<List<WarParticipantRow>> findByWar(int warId) {
        return db.call(connection -> findByWar(connection, warId));
    }

    public List<WarParticipantRow> findByWar(Connection connection, int warId) throws SQLException {
        return queryListSync(connection,
                "SELECT " + COLUMNS + " FROM war_participants WHERE war_id = ?", this::map, warId);
    }

    /** Every war a city is currently listed in, used to enforce SPEC 11.3 precondition 6. */
    public CompletableFuture<List<WarParticipantRow>> findByCity(int cityId) {
        return queryList("SELECT " + COLUMNS + " FROM war_participants WHERE city_id = ?", cityId);
    }

    public CompletableFuture<Integer> insert(WarParticipantRow row) {
        return db.call(connection -> insert(connection, row));
    }

    public int insert(Connection connection, WarParticipantRow row) throws SQLException {
        return updateSync(connection,
                "INSERT INTO war_participants (" + COLUMNS + ") VALUES (?, ?, ?, ?)",
                row.warId(), row.cityId(), row.side(), row.isAlly());
    }

    public CompletableFuture<Integer> deleteByWar(int warId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM war_participants WHERE war_id = ?", warId));
    }
}
