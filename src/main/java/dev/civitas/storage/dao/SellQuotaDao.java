package dev.civitas.storage.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.SellQuotaRow;

/**
 * {@code player_sell_quota}, the SPEC 21.5 daily sell quota.
 *
 * <p>Every mutation has a {@link Connection} form, because SPEC 21.10.3 requires the counter to
 * be exact under concurrency and the only way to get that is for the quota to move in the same
 * transaction as the money it is counting. A quota charged in its own transaction can be lost
 * to a crash between the two, and what is left is a player who was paid and whose counter says
 * they sold nothing.
 */
public final class SellQuotaDao extends Dao<SellQuotaRow> {

    private static final String COLUMNS = "uuid, period_start, used";

    public SellQuotaDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "player_sell_quota";
    }

    @Override
    protected SellQuotaRow map(ResultSet rs) throws SQLException {
        return new SellQuotaRow(uuid(rs, "uuid"), rs.getLong("period_start"),
                money(rs, "used"));
    }

    /** One player's counter, or empty if they have never sold anything. */
    public Optional<SellQuotaRow> findSync(Connection connection, UUID player)
            throws SQLException {
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM player_sell_quota WHERE uuid = ?",
                this::map, player.toString());
    }

    public CompletableFuture<Optional<SellQuotaRow>> find(UUID player) {
        return db.call(connection -> findSync(connection, player));
    }

    /**
     * Writes a player's counter, replacing whatever was there.
     *
     * <p>Update first, insert only if that changed nothing — the same shape
     * {@code MarketStockDao} uses, because {@code ON CONFLICT} is SQLite's spelling and
     * {@code ON DUPLICATE KEY} is MySQL's, and neither runs on the other.
     *
     * <p>One row per player rather than one per player per day: yesterday's figure is of no
     * interest once the day has turned, and the alternative grows a table nobody reads.
     */
    public int upsertSync(Connection connection, SellQuotaRow row) throws SQLException {
        int updated = updateSync(connection,
                "UPDATE player_sell_quota SET period_start = ?, used = ? WHERE uuid = ?",
                row.periodStart(), row.used(), row.uuid().toString());
        if (updated > 0) {
            return updated;
        }
        return updateSync(connection,
                "INSERT INTO player_sell_quota (" + COLUMNS + ") VALUES (?, ?, ?)",
                row.uuid().toString(), row.periodStart(), row.used());
    }

    public CompletableFuture<Integer> upsert(SellQuotaRow row) {
        return db.call(connection -> upsertSync(connection, row));
    }

    /** Forgets one player's counter, for {@code /ca quota reset}. */
    public CompletableFuture<Integer> delete(UUID player) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM player_sell_quota WHERE uuid = ?", player.toString()));
    }

    /**
     * Drops counters from days that have already turned.
     *
     * <p>A stale row is harmless — the service reads an old {@code period_start} as a fresh
     * quota — so this is housekeeping rather than correctness.
     */
    public CompletableFuture<Integer> pruneBefore(long periodStart) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM player_sell_quota WHERE period_start < ?", periodStart));
    }

    /** What the whole server has sold today, for the admin views and for the tests. */
    public CompletableFuture<BigDecimal> totalUsed(long periodStart) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT COALESCE(SUM(used), 0) AS total FROM player_sell_quota "
                        + "WHERE period_start = ?",
                rs -> money(rs, "total"), periodStart)
                .orElse(BigDecimal.ZERO));
    }
}
