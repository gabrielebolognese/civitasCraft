package dev.civitas.storage.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.ServerStatsRow;

/** {@code server_stats}, SPEC 36.6. */
public final class ServerStatsDao extends Dao<ServerStatsRow> {

    private static final String COLUMNS = "day_start, registered, active_7d, active_30d, cities, "
            + "claims, average_city, wars_started, contest_entries";

    public ServerStatsDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "server_stats";
    }

    @Override
    protected ServerStatsRow map(ResultSet rs) throws SQLException {
        return new ServerStatsRow(rs.getLong("day_start"), rs.getInt("registered"),
                rs.getInt("active_7d"), rs.getInt("active_30d"), rs.getInt("cities"),
                rs.getInt("claims"), rs.getDouble("average_city"), rs.getInt("wars_started"),
                rs.getInt("contest_entries"));
    }

    /**
     * Records today, replacing today if it is already there.
     *
     * <p>Delete-then-insert rather than a dialect-specific upsert: {@code INSERT OR REPLACE} and
     * {@code ON DUPLICATE KEY} are spelled differently on the two backends, and this table is
     * written once a day.
     */
    public CompletableFuture<Integer> upsert(ServerStatsRow row) {
        return db.transaction(connection -> {
            updateSync(connection, "DELETE FROM server_stats WHERE day_start = ?", row.dayStart());
            return updateSync(connection,
                    "INSERT INTO server_stats (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    row.dayStart(), row.registered(), row.active7d(), row.active30d(),
                    row.cities(), row.claims(), row.averageCitySize(), row.warsStarted(),
                    row.contestEntries());
        });
    }

    /** Oldest first, which is the order a trend is read in. */
    public CompletableFuture<List<ServerStatsRow>> findSince(long since) {
        return queryList("SELECT " + COLUMNS + " FROM server_stats WHERE day_start >= ? "
                + "ORDER BY day_start ASC", since);
    }

    public CompletableFuture<Optional<ServerStatsRow>> findLatest() {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM server_stats ORDER BY day_start DESC LIMIT 1",
                this::map));
    }
}
