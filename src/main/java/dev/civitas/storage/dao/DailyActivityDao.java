package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.DailyActivityRow;

/**
 * {@code player_daily_activity}, SPEC 21.4 F12's "30 minutes of active playtime that day".
 *
 * <p>Holds a baseline rather than an accrual: the lifetime active playtime as it stood when the
 * day turned. Today's figure is the difference against the live counter, which means the SPEC
 * 4.2.1 filter stays the only thing that ever writes active playtime and the two numbers cannot
 * disagree.
 */
public final class DailyActivityDao extends Dao<DailyActivityRow> {

    private static final String COLUMNS = "uuid, day_start, baseline_ms";

    public DailyActivityDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "player_daily_activity";
    }

    @Override
    protected DailyActivityRow map(ResultSet rs) throws SQLException {
        return new DailyActivityRow(uuid(rs, "uuid"), rs.getLong("day_start"),
                rs.getLong("baseline_ms"));
    }

    public Optional<DailyActivityRow> findSync(Connection connection, UUID player)
            throws SQLException {
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM player_daily_activity WHERE uuid = ?",
                this::map, player.toString());
    }

    public CompletableFuture<Optional<DailyActivityRow>> find(UUID player) {
        return db.call(connection -> findSync(connection, player));
    }

    /** Update first, insert if that changed nothing — the dialect-safe upsert. */
    public int upsertSync(Connection connection, DailyActivityRow row) throws SQLException {
        int updated = updateSync(connection,
                "UPDATE player_daily_activity SET day_start = ?, baseline_ms = ? WHERE uuid = ?",
                row.dayStart(), row.baselineMs(), row.uuid().toString());
        if (updated > 0) {
            return updated;
        }
        return updateSync(connection,
                "INSERT INTO player_daily_activity (" + COLUMNS + ") VALUES (?, ?, ?)",
                row.uuid().toString(), row.dayStart(), row.baselineMs());
    }

    public CompletableFuture<Integer> upsert(DailyActivityRow row) {
        return db.call(connection -> upsertSync(connection, row));
    }

    /** Housekeeping: baselines from days that have already turned are of no further use. */
    public CompletableFuture<Integer> pruneBefore(long dayStart) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM player_daily_activity WHERE day_start < ?", dayStart));
    }
}
