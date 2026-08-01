package dev.civitas.core.market;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.dao.MarketStockDao;
import dev.civitas.storage.row.MarketStockRow;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The market's catalogue and its live stock, in memory.
 *
 * <p>SPEC 2.3 makes the database a persistence target rather than a read path, and a price
 * is quoted every time a player opens the shop or holds an item, so both halves are cached:
 * the item definitions come from {@code economy.yml} and the stock from
 * {@code market_stock}.
 *
 * <p>Stock is written through rather than written back. Every change goes to the database as
 * {@code current_stock = current_stock + delta} in SQL, so two sales in the same tick cannot
 * lose each other the way a read-modify-write would.
 */
public final class MarketRegistry {

    private final MarketStockDao stockDao;
    private final ConfigManager configs;
    private final Logger logger;

    /** Material name, uppercase, to its config definition. */
    private final Map<String, MarketItem> items = new ConcurrentHashMap<>();

    /** Material name, uppercase, to its live stock. */
    private final Map<String, Integer> stock = new ConcurrentHashMap<>();

    public MarketRegistry(MarketStockDao stockDao, ConfigManager configs, Logger logger) {
        this.stockDao = Objects.requireNonNull(stockDao, "stockDao");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // Loading
    // ==================================================================================

    /**
     * Reads the catalogue from config and reconciles it with the stored stock.
     *
     * <p>A newly listed item is seeded at its target stock, so it opens at roughly base
     * price rather than at the sold-out ceiling. An item whose definition changed keeps its
     * stock: a config reload must never hand players a price reset.
     *
     * @return how many items the market trades
     */
    public CompletableFuture<Integer> loadAll() {
        Map<String, MarketItem> parsed = parseCatalogue();

        return stockDao.findAll().thenCompose(rows -> {
            Map<String, Integer> stored = new ConcurrentHashMap<>();
            for (MarketStockRow row : rows) {
                stored.put(row.material().toUpperCase(Locale.ROOT), row.currentStock());
            }

            List<CompletableFuture<?>> writes = new ArrayList<>();
            for (MarketItem item : parsed.values()) {
                writes.add(stockDao.upsertDefinition(item.material(), item.targetStock(),
                        item.basePrice()));
            }

            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> {
                        items.clear();
                        items.putAll(parsed);
                        stock.clear();
                        for (MarketItem item : parsed.values()) {
                            stock.put(item.material(), stored.getOrDefault(item.material(),
                                    item.targetStock()));
                        }
                        return items.size();
                    });
        });
    }

    private Map<String, MarketItem> parseCatalogue() {
        Map<String, MarketItem> parsed = new ConcurrentHashMap<>();
        ConfigurationSection section =
                configs.get(ConfigFile.ECONOMY).getConfigurationSection("market.items");
        if (section == null) {
            return parsed;
        }

        double defaultElasticity = configs.get(ConfigFile.ECONOMY)
                .getDouble("market.default-elasticity", 0.45);

        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                logger.warning(() -> "Market item " + key + " is not a section; ignoring it.");
                continue;
            }
            String material = key.toUpperCase(Locale.ROOT);
            try {
                parsed.put(material, new MarketItem(material,
                        BigDecimal.valueOf(entry.getDouble("base-price")),
                        entry.getInt("target-stock"),
                        entry.getDouble("elasticity", defaultElasticity)));
            } catch (IllegalArgumentException e) {
                // One bad line should cost that item, not the whole market.
                logger.warning(() -> "Market item " + key + " is misconfigured and will not "
                        + "be traded: " + e.getMessage());
            }
        }
        return parsed;
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    /** @return the definition, or empty if the server does not trade this material */
    public Optional<MarketItem> item(String material) {
        return material == null
                ? Optional.empty()
                : Optional.ofNullable(items.get(material.toUpperCase(Locale.ROOT)));
    }

    public boolean trades(String material) {
        return item(material).isPresent();
    }

    /** Current stock, or the target if the material is unknown. */
    public int stockOf(String material) {
        MarketItem item = items.get(material.toUpperCase(Locale.ROOT));
        if (item == null) {
            return 0;
        }
        return stock.getOrDefault(item.material(), item.targetStock());
    }

    /** Every traded material, in a stable order for menus and tab completion. */
    public List<MarketItem> catalogue() {
        List<MarketItem> all = new ArrayList<>(items.values());
        all.sort((left, right) -> left.material().compareTo(right.material()));
        return all;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    // ==================================================================================
    // Writing
    // ==================================================================================

    /**
     * Moves stock and persists the move.
     *
     * @param delta positive when players sell to the market, negative when they buy from it
     * @return the stock level after the move
     */
    public CompletableFuture<Integer> addStock(String material, int delta) {
        MarketItem item = items.get(material.toUpperCase(Locale.ROOT));
        if (item == null || delta == 0) {
            return CompletableFuture.completedFuture(stockOf(material));
        }
        int after = stock.merge(item.material(), delta, Integer::sum);
        return stockDao.addStock(item.material(), delta).thenApply(ignored -> after);
    }

    /** Admin override, SPEC 9.4.4 {@code /ca market setstock}. */
    public CompletableFuture<Integer> setStock(String material, int value) {
        MarketItem item = items.get(material.toUpperCase(Locale.ROOT));
        if (item == null) {
            return CompletableFuture.completedFuture(0);
        }
        stock.put(item.material(), value);
        return stockDao.setStock(item.material(), value).thenApply(ignored -> value);
    }

    /** Used by the decay task, which computes the new level itself. */
    void putStock(String material, int value) {
        stock.put(material.toUpperCase(Locale.ROOT), value);
    }
}
