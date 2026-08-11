package dev.civitas.storage.dao;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.MoneySupplyRow;

/** {@code money_supply}, SPEC 21.4 Class G. */
public final class MoneySupplyDao extends Dao<MoneySupplyRow> {

    private static final String COLUMNS =
            "id, timestamp, player_total, treasury_total, escrow_total";

    public MoneySupplyDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "money_supply";
    }

    @Override
    protected MoneySupplyRow map(ResultSet rs) throws SQLException {
        return new MoneySupplyRow(
                rs.getLong("id"),
                rs.getLong("timestamp"),
                money(rs, "player_total"),
                money(rs, "treasury_total"),
                money(rs, "escrow_total"));
    }

    public CompletableFuture<Long> insert(MoneySupplyRow row) {
        return db.call(connection -> insertSync(connection,
                "INSERT INTO money_supply (timestamp, player_total, treasury_total, escrow_total) "
                        + "VALUES (?, ?, ?, ?)",
                row.timestamp(), row.playerTotal(), row.treasuryTotal(), row.escrowTotal()));
    }

    /** Oldest first, which is the order a graph is drawn in. */
    public CompletableFuture<List<MoneySupplyRow>> findSince(long since) {
        return queryList("SELECT " + COLUMNS + " FROM money_supply WHERE timestamp >= ? "
                + "ORDER BY timestamp ASC", since);
    }

    public CompletableFuture<Optional<MoneySupplyRow>> findLatest() {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM money_supply ORDER BY timestamp DESC LIMIT 1",
                this::map));
    }

    /**
     * The newest snapshot at or before a moment.
     *
     * <p>What a week-over-week comparison needs: the reading closest to seven days ago rather
     * than the oldest one kept, which would drift as retention pruned the table.
     */
    public CompletableFuture<Optional<MoneySupplyRow>> findLatestBefore(long at) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM money_supply WHERE timestamp <= ? "
                        + "ORDER BY timestamp DESC LIMIT 1", this::map, at));
    }

    public CompletableFuture<Integer> deleteBefore(long cutoff) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM money_supply WHERE timestamp < ?", cutoff));
    }

    /** For the tests and for {@code /ca eco supply} to say how much history it is reading. */
    public CompletableFuture<BigDecimal> latestCirculation() {
        return findLatest().thenApply(row ->
                row.map(MoneySupplyRow::circulation).orElse(BigDecimal.ZERO));
    }
}
