package dev.civitas.storage.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.MarketStockRow;

/** {@code market_stock}, SPEC 3.9. The state behind the SPEC 4.4 price formula. */
public final class MarketStockDao extends Dao<MarketStockRow> {

    private static final String COLUMNS = "material, current_stock, target_stock, base_price";

    public MarketStockDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "market_stock";
    }

    @Override
    protected MarketStockRow map(ResultSet rs) throws SQLException {
        return new MarketStockRow(
                rs.getString("material"),
                rs.getInt("current_stock"),
                rs.getInt("target_stock"),
                money(rs, "base_price"));
    }

    public CompletableFuture<List<MarketStockRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM market_stock ORDER BY material");
    }

    public CompletableFuture<Optional<MarketStockRow>> find(String material) {
        return db.call(connection -> find(connection, material));
    }

    public Optional<MarketStockRow> find(Connection connection, String material) throws SQLException {
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM market_stock WHERE material = ?", this::map, material);
    }

    /**
     * Creates the row if absent, otherwise refreshes only the configured fields.
     *
     * <p>{@code current_stock} is deliberately left alone: it is live market state that a
     * config reload must not reset, or every reload would hand players a price reset.
     */
    public CompletableFuture<Integer> upsertDefinition(String material, int targetStock, BigDecimal basePrice) {
        return db.transaction(connection -> upsertDefinition(connection, material, targetStock, basePrice));
    }

    /**
     * Writes every definition in one transaction.
     *
     * <p>SPEC 21.6's builder catalogue expands to a few hundred materials, and one
     * transaction each meant a few hundred round trips through the pool every time a market
     * was opened. One transaction is the same work for the database and a tenth of the
     * latency.
     */
    public CompletableFuture<Integer> upsertDefinitions(
            java.util.Collection<dev.civitas.core.market.MarketItem> items) {
        if (items.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return db.call(connection -> {
            int written = 0;
            for (dev.civitas.core.market.MarketItem item : items) {
                written += upsertDefinition(connection, item.material(), item.targetStock(),
                        item.basePrice());
            }
            return written;
        });
    }

    public int upsertDefinition(Connection connection, String material, int targetStock,
                                BigDecimal basePrice) throws SQLException {
        int updated = updateSync(connection,
                "UPDATE market_stock SET target_stock = ?, base_price = ? WHERE material = ?",
                targetStock, basePrice, material);
        if (updated > 0) {
            return updated;
        }
        return updateSync(connection,
                "INSERT INTO market_stock (" + COLUMNS + ") VALUES (?, ?, ?, ?)",
                material, targetStock, targetStock, basePrice);
    }

    /**
     * Moves stock by {@code delta} in one statement.
     *
     * <p>Read-modify-write would lose a concurrent sale; SQL arithmetic cannot.
     */
    public CompletableFuture<Integer> addStock(String material, int delta) {
        return db.call(connection -> addStock(connection, material, delta));
    }

    public int addStock(Connection connection, String material, int delta) throws SQLException {
        return updateSync(connection,
                "UPDATE market_stock SET current_stock = current_stock + ? WHERE material = ?",
                delta, material);
    }

    /** Admin override, SPEC 9.4.4 {@code /ca market setstock}. */
    public CompletableFuture<Integer> setStock(String material, int stock) {
        return db.call(connection -> updateSync(connection,
                "UPDATE market_stock SET current_stock = ? WHERE material = ?", stock, material));
    }

    /** Admin override, SPEC 9.4.4 {@code /ca market setprice}. */
    public CompletableFuture<Integer> setBasePrice(String material, BigDecimal basePrice) {
        return db.call(connection -> updateSync(connection,
                "UPDATE market_stock SET base_price = ? WHERE material = ?", basePrice, material));
    }

    public CompletableFuture<Integer> delete(String material) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM market_stock WHERE material = ?", material));
    }
}
