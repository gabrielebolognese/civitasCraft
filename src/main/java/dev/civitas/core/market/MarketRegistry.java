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

            return stockDao.upsertDefinitions(parsed.values())
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

    /**
     * Reads {@code market.buy} and {@code market.sell}, SPEC 21.6 and 21.9.
     *
     * <p>Two sections rather than one list, because the two sides of the market have nothing
     * in common but a price. What the server <b>buys</b> is the money faucet and is guarded by
     * four startup assertions it cannot be configured out of; what the server <b>sells</b> is
     * a sink and carries no exploit risk at all. Merging them into one catalogue is how Part I
     * ended up buying iron.
     *
     * <p>A buy entry always overwrites a sell entry of the same material, so an item on both
     * lists is traded both ways at the buy entry's price rather than silently taking whichever
     * section happened to load last.
     */
    private Map<String, MarketItem> parseCatalogue() {
        Map<String, MarketItem> parsed = new ConcurrentHashMap<>();
        double defaultElasticity = configs.get(ConfigFile.ECONOMY)
                .getDouble("market.default-elasticity", 0.45);

        parseSection("market.sell", false, defaultElasticity, parsed);
        parseSection("market.buy", true, defaultElasticity, parsed);
        return parsed;
    }

    private void parseSection(String path, boolean serverBuys, double defaultElasticity,
                              Map<String, MarketItem> into) {
        ConfigurationSection section =
                configs.get(ConfigFile.ECONOMY).getConfigurationSection(path);
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                logger.warning(() -> path + "." + key + " is not a section; ignoring it.");
                continue;
            }
            BigDecimal basePrice = BigDecimal.valueOf(entry.getDouble("base-price"));
            int targetStock = entry.getInt("target-stock");
            double elasticity = entry.getDouble("elasticity", defaultElasticity);

            // A sell key may name a whole group, SPEC 21.6. The buy list never does: its
            // entries are individually chosen and individually justified.
            java.util.Collection<String> materials = !serverBuys && SellGroups.isGroup(key)
                    ? SellGroups.expand(key)
                    : java.util.List.of(key.toUpperCase(Locale.ROOT));

            for (String material : materials) {
                try {
                    into.put(material, new MarketItem(material, basePrice, targetStock,
                            elasticity, serverBuys));
                } catch (IllegalArgumentException e) {
                    // One bad line should cost that item, not the whole market.
                    logger.warning(() -> path + "." + key + " is misconfigured and will not "
                            + "be traded: " + e.getMessage());
                }
            }
        }
    }

    /**
     * The materials the server buys, which is what SPEC 21.10.1's assertions run over.
     *
     * <p>Sorted, so a startup failure names its pairs in the same order every time and two
     * runs of the same broken config produce the same log.
     */
    public java.util.List<String> buyList() {
        return catalogue().stream()
                .filter(MarketItem::serverBuys)
                .map(MarketItem::material)
                .sorted()
                .toList();
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
    /**
     * SPEC 9.4.4's {@code /ca market setprice}.
     *
     * <p>In memory only, deliberately. The base price is a config value (SPEC 4.4's table
     * lives in {@code economy.yml}), so a change here lasts until the next reload and is meant
     * to: an operator tuning a price live wants to see its effect before writing it into the
     * file, and silently rewriting their config from a chat command would be worse than
     * forgetting the change.
     */
    public void setBasePrice(String material, java.math.BigDecimal basePrice) {
        item(material).ifPresent(existing -> items.put(material.toUpperCase(java.util.Locale.ROOT),
                existing.withBasePrice(basePrice)));
    }

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
