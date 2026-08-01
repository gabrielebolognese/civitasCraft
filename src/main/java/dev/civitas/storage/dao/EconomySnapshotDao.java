package dev.civitas.storage.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.EconomySnapshotRow;

/** {@code economy_snapshots}, migration V3. Backs the SPEC 4.8 inflation check. */
public final class EconomySnapshotDao extends Dao<EconomySnapshotRow> {

    private static final String COLUMNS = "id, timestamp, player_total, treasury_total";

    public EconomySnapshotDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "economy_snapshots";
    }

    @Override
    protected EconomySnapshotRow map(ResultSet rs) throws SQLException {
        return new EconomySnapshotRow(
                rs.getLong("id"),
                rs.getLong("timestamp"),
                money(rs, "player_total"),
                money(rs, "treasury_total"));
    }

    public CompletableFuture<Long> insert(EconomySnapshotRow row) {
        return db.call(connection -> insertSync(connection,
                "INSERT INTO economy_snapshots (timestamp, player_total, treasury_total) "
                        + "VALUES (?, ?, ?)",
                row.timestamp(), row.playerTotal(), row.treasuryTotal()));
    }

    /**
     * The most recent snapshot at or before {@code at}.
     *
     * <p>"At or before" rather than "nearest": comparing against a snapshot from the future
     * would report shrinkage as growth after a clock change or a restore from backup.
     */
    public CompletableFuture<Optional<EconomySnapshotRow>> findLatestBefore(long at) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM economy_snapshots WHERE timestamp <= ? "
                        + "ORDER BY timestamp DESC LIMIT 1",
                this::map, at));
    }

    public CompletableFuture<List<EconomySnapshotRow>> findSince(long since, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM economy_snapshots WHERE timestamp >= ? "
                + "ORDER BY timestamp DESC LIMIT ?", since, limit);
    }

    /** Housekeeping, so a long-lived server does not keep every hour it ever ran. */
    public CompletableFuture<Integer> deleteBefore(long cutoff) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM economy_snapshots WHERE timestamp < ?", cutoff));
    }
}
