package dev.civitas.core.market;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.storage.dao.MarketStockDao;

/**
 * The SPEC 4.4 stock drift: current stock moves toward target so prices recover.
 *
 * <p>Without it a single player dumping ten thousand carrots would leave carrots worthless
 * for the rest of the server's life, which is the failure mode SPEC 4.4 calls "no item stays
 * permanently dead". Runs off the main thread; the whole sweep is a handful of updates.
 */
public final class StockDecayTask implements Runnable {

    private final MarketRegistry registry;
    private final MarketPricing pricing;
    private final MarketStockDao stockDao;
    private final Logger logger;

    public StockDecayTask(MarketRegistry registry, MarketPricing pricing,
                          MarketStockDao stockDao, Logger logger) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        this.stockDao = Objects.requireNonNull(stockDao, "stockDao");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void run() {
        try {
            decayOnce();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Market stock decay failed", e);
        }
    }

    /**
     * One decay step across the whole catalogue.
     *
     * @return how many items moved
     */
    public int decayOnce() {
        int moved = 0;
        for (MarketItem item : registry.catalogue()) {
            int before = registry.stockOf(item.material());
            int after = pricing.decayed(item, before);
            if (after == before) {
                continue;
            }
            registry.putStock(item.material(), after);
            stockDao.setStock(item.material(), after).join();
            moved++;
        }
        return moved;
    }
}
