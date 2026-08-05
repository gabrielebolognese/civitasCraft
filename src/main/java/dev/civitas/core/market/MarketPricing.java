package dev.civitas.core.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.Money;
import dev.civitas.storage.SqlDialect;

/**
 * The SPEC 4.4 dynamic price curve.
 *
 * <pre>
 * price = base_price * clamp( (target_stock / (current_stock + 1)) ^ elasticity, 0.25, 3.0 )
 * </pre>
 *
 * <p>Pure and config-driven: every constant above is a key in {@code economy.yml}. The
 * clamp is what makes the market safe to leave running unattended. Without the lower bound
 * a popular crop would fall to nothing and stay there; without the upper bound an item
 * bought out entirely would cost unbounded money, which SPEC 17.3 case 28 rules out
 * explicitly by letting stock go negative and the clamp hold the price instead.
 *
 * <h2>Selling a stack</h2>
 * The formula prices one unit at one stock level, so a batch is priced unit by unit with
 * the stock moving underneath it, not once at the opening price. That is the difference
 * between SPEC 4.4's stated intent, "the first player to sell pumpkins gets rich and the
 * hundredth does not", and a market where one player sells ten thousand pumpkins at the
 * first pumpkin's price.
 */
public final class MarketPricing {

    /** How many decimal places the multiplier keeps before it reaches the money scale. */
    private static final int MULTIPLIER_SCALE = 8;

    private final ConfigManager configs;

    /**
     * The SPEC 13.5 market events, once M16 has wired them.
     *
     * <p>Neutral until then, and neutral whenever nothing is running, which is the property
     * that matters: an event multiplier is derived from what is running rather than latched,
     * so it cannot survive the event that set it.
     */
    private dev.civitas.core.events.EventEffects events;

    public MarketPricing(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /** SPEC 13.5 Market Boom, Market Crash and Tax Holiday. */
    public void useEvents(dev.civitas.core.events.EventEffects effects) {
        this.events = effects;
    }

    private BigDecimal eventSellMultiplier() {
        return events == null ? BigDecimal.ONE : events.sellPriceMultiplier();
    }

    private BigDecimal eventBuyMultiplier() {
        return events == null ? BigDecimal.ONE : events.buyPriceMultiplier();
    }

    // ==================================================================================
    // The curve
    // ==================================================================================

    /**
     * The clamped supply multiplier at a stock level.
     *
     * <p>Stock may be negative (SPEC 17.3 case 28), which would make {@code stock + 1} zero
     * or negative and the division undefined. Stock at or below {@code -1} means the market
     * is more than sold out, so the multiplier is its ceiling, which is exactly where the
     * curve was heading anyway.
     */
    public BigDecimal multiplier(MarketItem item, int currentStock) {
        double denominator = currentStock + 1.0;
        if (denominator <= 0) {
            return clampMax();
        }
        double raw = Math.pow(item.targetStock() / denominator, item.elasticity());
        if (!Double.isFinite(raw)) {
            return clampMax();
        }
        return clamp(BigDecimal.valueOf(raw).setScale(MULTIPLIER_SCALE, RoundingMode.HALF_UP));
    }

    /** What the market pays a player for one unit at this stock level. */
    public BigDecimal unitSellPrice(MarketItem item, int currentStock) {
        return Money.floor(item.basePrice().multiply(multiplier(item, currentStock))
                .multiply(eventSellMultiplier()));
    }

    /**
     * What the market charges a player for one unit at this stock level.
     *
     * <p>The spread is the arbitrage guard of SPEC 17.6 case 75: buying and instantly
     * selling back loses the spread, every time, whatever the stock does.
     */
    public BigDecimal unitBuyPrice(MarketItem item, int currentStock) {
        BigDecimal spread = BigDecimal.valueOf(
                configs.get(ConfigFile.ECONOMY).getDouble("market.buy-spread", 1.35));
        return Money.floor(item.basePrice().multiply(multiplier(item, currentStock))
                .multiply(spread).multiply(eventBuyMultiplier()));
    }

    // ==================================================================================
    // Batches
    // ==================================================================================

    /**
     * What a player is paid for {@code amount} units, walking the curve down as they sell.
     *
     * @return the gross, before the SPEC 4.3 sale tax
     */
    public BigDecimal grossSellValue(MarketItem item, int currentStock, int amount) {
        BigDecimal total = SqlDialect.zero();
        for (int sold = 0; sold < amount; sold++) {
            total = total.add(unitSellPrice(item, currentStock + sold));
        }
        return total;
    }

    /** What a player pays for {@code amount} units, walking the curve up as they buy. */
    public BigDecimal buyCost(MarketItem item, int currentStock, int amount) {
        BigDecimal total = SqlDialect.zero();
        for (int bought = 0; bought < amount; bought++) {
            total = total.add(unitBuyPrice(item, currentStock - bought));
        }
        return total;
    }

    // ==================================================================================
    // Tax, SPEC 4.3
    // ==================================================================================

    /**
     * The sale tax rate a player actually pays, after the SPEC 5.7 Market Access upgrade.
     *
     * @param marketAccessLevel 0 to 5; each level takes 0.8 percentage points off
     */
    public double taxPercent(int marketAccessLevel) {
        // SPEC 13.5's Tax Holiday states a rate rather than a multiplier, and overriding
        // rather than multiplying is what keeps it from silently discarding a Market Access
        // discount the city already paid for: during the holiday nobody pays tax at all, and
        // afterwards the bought discount is still there.
        if (events != null) {
            java.util.Optional<Double> holiday = events.taxPercentOverride();
            if (holiday.isPresent()) {
                return Math.max(0.0, holiday.get());
            }
        }
        double base = configs.get(ConfigFile.ECONOMY)
                .getDouble("sinks.market-sale-tax-percent", 5.0);
        double perLevel = configs.get(ConfigFile.CITIES)
                .getDouble("upgrades.market-access.effect-per-level", 0.8);
        return Math.max(0.0, base - perLevel * Math.max(0, marketAccessLevel));
    }

    /** The tax on a gross sale. Deleted from circulation, never paid to anyone (SPEC 4.3). */
    public BigDecimal taxOn(BigDecimal gross, int marketAccessLevel) {
        return Money.percentOf(gross, taxPercent(marketAccessLevel));
    }

    // ==================================================================================
    // Decay, SPEC 4.4
    // ==================================================================================

    /**
     * One decay step: stock drifts back toward target so prices recover overnight.
     *
     * @return the new stock level, never overshooting target
     */
    public int decayed(MarketItem item, int currentStock) {
        double percent = configs.get(ConfigFile.ECONOMY)
                .getDouble("market.stock-decay-percent-per-hour", 2.0);
        int gap = item.targetStock() - currentStock;
        if (gap == 0 || percent <= 0) {
            return currentStock;
        }
        // At least one unit, or a stock one away from target would never arrive.
        int step = (int) Math.max(1, Math.round(Math.abs(gap) * percent / 100.0));
        return gap > 0
                ? Math.min(item.targetStock(), currentStock + step)
                : Math.max(item.targetStock(), currentStock - step);
    }

    // ==================================================================================
    // Clamp bounds
    // ==================================================================================

    public BigDecimal clampMin() {
        return BigDecimal.valueOf(
                configs.get(ConfigFile.ECONOMY).getDouble("market.clamp-min", 0.25));
    }

    public BigDecimal clampMax() {
        return BigDecimal.valueOf(
                configs.get(ConfigFile.ECONOMY).getDouble("market.clamp-max", 3.0));
    }

    private BigDecimal clamp(BigDecimal value) {
        return value.max(clampMin()).min(clampMax());
    }
}
