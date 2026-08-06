package dev.civitas.core.market;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.Money;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.income.IncomeReporter;
import dev.civitas.core.income.QuestMetric;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.dao.LedgerDao;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.util.Result;

/**
 * Buying from and selling to the server market, SPEC 4.4.
 *
 * <h2>Order of operations</h2>
 * A sale moves items one way and money the other, and only the item half can be lost to a
 * race. So the caller removes the items first, on the server thread, and calls this
 * afterwards; if the sale then fails, the caller puts them back. The reverse order would
 * pay a player for items that a disconnect or a second command had already taken out from
 * under it, which is a duplication bug rather than an inconvenience.
 *
 * <h2>The tax</h2>
 * SPEC 4.3 deletes the market tax from circulation rather than paying it to anyone. It is
 * therefore never credited: the player is paid the net, and the tax is written to the ledger
 * as its own {@code MARKET_TAX} row so the deletion is auditable.
 */
public final class MarketService {

    private final DatabaseManager db;
    private final LedgerDao ledger;
    private final MarketRegistry registry;
    private final MarketPricing pricing;
    private final EconomyService economy;
    private final ConfigManager configs;
    private final IncomeReporter reporter;

    public MarketService(DatabaseManager db, LedgerDao ledger, MarketRegistry registry,
                         MarketPricing pricing, EconomyService economy, ConfigManager configs) {
        this(db, ledger, registry, pricing, economy, configs, IncomeReporter.noop());
    }

    public MarketService(DatabaseManager db, LedgerDao ledger, MarketRegistry registry,
                         MarketPricing pricing, EconomyService economy, ConfigManager configs,
                         IncomeReporter reporter) {
        this.db = Objects.requireNonNull(db, "db");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    public MarketRegistry registry() {
        return registry;
    }

    public MarketPricing pricing() {
        return pricing;
    }

    public boolean enabled() {
        return configs.get(ConfigFile.ECONOMY).getBoolean("market.enabled", true);
    }

    // ==================================================================================
    // Quoting, SPEC 9.1 /worth
    // ==================================================================================

    /** What one unit is worth right now, both ways. */
    public Optional<Quote> quote(String material) {
        return registry.item(material).map(item -> {
            int stock = registry.stockOf(item.material());
            return new Quote(item, stock,
                    pricing.unitSellPrice(item, stock),
                    pricing.unitBuyPrice(item, stock));
        });
    }

    /** What a batch would fetch, before tax, without moving anything. */
    public Optional<BigDecimal> previewSell(String material, int amount) {
        return registry.item(material).map(item ->
                pricing.grossSellValue(item, registry.stockOf(item.material()), amount));
    }

    /** What a batch would cost, without moving anything. */
    public Optional<BigDecimal> previewBuy(String material, int amount) {
        return registry.item(material).map(item ->
                pricing.buyCost(item, registry.stockOf(item.material()), amount));
    }

    // ==================================================================================
    // Selling
    // ==================================================================================

    /**
     * Sells items the caller has already removed from the player's inventory.
     *
     * @return the receipt, or a failure the caller must respond to by returning the items
     */
    public CompletableFuture<Result<Receipt>> sell(UUID seller, String material, int amount) {
        if (!enabled()) {
            return completed(Result.failure("MARKET_DISABLED", "market.disabled"));
        }
        if (amount <= 0) {
            return completed(Result.failure("AMOUNT_NOT_POSITIVE", "economy.amount-not-positive"));
        }
        Optional<MarketItem> found = registry.item(material);
        if (found.isEmpty()) {
            return completed(Result.failure("NOT_TRADED", "market.not-traded",
                    Map.of("item", String.valueOf(material))));
        }

        MarketItem item = found.get();
        int stock = registry.stockOf(item.material());
        BigDecimal gross = pricing.grossSellValue(item, stock, amount)
                .multiply(sellBonusMultiplier(seller));
        gross = Money.floor(gross);

        int accessLevel = marketAccessLevel(seller);
        BigDecimal tax = pricing.taxOn(gross, accessLevel);
        BigDecimal net = gross.subtract(tax);

        if (net.signum() <= 0) {
            // The curve has bottomed out and the tax eats what is left. Refusing is kinder
            // than taking the items for nothing.
            return completed(Result.failure("WORTHLESS", "market.worthless",
                    Map.of("item", item.material())));
        }

        BigDecimal finalGross = gross;
        return db.transaction(connection -> {
            Result<BigDecimal> paid = economy.deposit(connection, seller, net,
                    TransactionType.MARKET_SELL, null, metadata(item.material(), amount));
            if (paid instanceof Result.Failure<BigDecimal> failure) {
                return Result.<Receipt>propagate(failure);
            }

            if (tax.signum() > 0) {
                ledger.insert(connection, new LedgerRow(0, System.currentTimeMillis(),
                        TransactionType.MARKET_TAX.name(), seller, null, null,
                        tax.negate(), paid.orElseThrow(), metadata(item.material(), amount)));
            }
            return Result.success(new Receipt(item, amount, finalGross, tax, net,
                    paid.orElseThrow()));
        }).thenCompose(result -> {
            if (result instanceof Result.Failure<Receipt>) {
                return completed(result);
            }
            // SPEC 13.1's Trading quests count the value sold, not the number of sales.
            reporter.report(seller, QuestMetric.MARKET_SELL_VALUE,
                    result.orElseThrow().gross().longValue());

            // Stock moves only once the money has committed, so a failed sale cannot move
            // the price for everyone else.
            return registry.addStock(item.material(), amount).thenApply(ignored -> result);
        });
    }

    // ==================================================================================
    // Buying
    // ==================================================================================

    /**
     * Buys items for a player. The caller gives the items out once this succeeds.
     *
     * <p>SPEC 17.3 case 28: the market is an infinite seller. Stock going negative is
     * expected and the clamp holds the price at its ceiling.
     */
    public CompletableFuture<Result<Receipt>> buy(UUID buyer, String material, int amount) {
        if (!enabled()) {
            return completed(Result.failure("MARKET_DISABLED", "market.disabled"));
        }
        if (amount <= 0) {
            return completed(Result.failure("AMOUNT_NOT_POSITIVE", "economy.amount-not-positive"));
        }
        Optional<MarketItem> found = registry.item(material);
        if (found.isEmpty()) {
            return completed(Result.failure("NOT_TRADED", "market.not-traded",
                    Map.of("item", String.valueOf(material))));
        }

        MarketItem item = found.get();
        int stock = registry.stockOf(item.material());
        BigDecimal cost = pricing.buyCost(item, stock, amount);

        return db.transaction(connection -> {
            Result<BigDecimal> charged = economy.withdraw(connection, buyer, cost,
                    TransactionType.MARKET_BUY, null, metadata(item.material(), amount));
            if (charged instanceof Result.Failure<BigDecimal> failure) {
                return Result.<Receipt>propagate(failure);
            }
            return Result.success(new Receipt(item, amount, cost, SqlDialect.zero(), cost,
                    charged.orElseThrow()));
        }).thenCompose(result -> {
            if (result instanceof Result.Failure<Receipt>) {
                return completed(result);
            }
            return registry.addStock(item.material(), -amount).thenApply(ignored -> result);
        });
    }

    // ==================================================================================
    // What later milestones will supply
    // ==================================================================================

    /**
     * The seller's city's Market Access level, SPEC 5.7.
     *
     * <p>A player with no city pays the full SPEC 4.3 tax, which is the point of the track:
     * the discount belongs to a city, and a member carries it with them.
     */
    private int marketAccessLevel(UUID seller) {
        if (upgrades == null || cities == null) {
            return 0;
        }
        return cities.cityOf(seller)
                .map(city -> upgrades.levelOf(city,
                        dev.civitas.core.upgrade.UpgradeType.MARKET_ACCESS))
                .orElse(0);
    }

    /** Told about cities and upgrades once they exist. */
    public void useUpgrades(dev.civitas.core.city.CityRegistry registry,
                            dev.civitas.core.upgrade.UpgradeService service) {
        this.cities = registry;
        this.upgrades = service;
    }

    private dev.civitas.core.city.CityRegistry cities;
    private dev.civitas.core.upgrade.UpgradeService upgrades;

    /**
     * The SPEC 11.9 winner's bonus: +10% sell prices for seven days after winning a war.
     *
     * <p>One until M19 builds wars, because there is no war for anyone to have won.
     */
    private BigDecimal sellBonusMultiplier(UUID seller) {
        if (warRewards == null || cities == null) {
            return BigDecimal.ONE;
        }
        return cities.cityOf(seller)
                .filter(city -> warRewards.hasMarketBonus(city.id(), System.currentTimeMillis()))
                .map(city -> BigDecimal.ONE.add(BigDecimal.valueOf(warBonusPercent)
                        .divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP)))
                .orElse(BigDecimal.ONE);
    }

    private dev.civitas.core.war.WarRewards warRewards;
    private double warBonusPercent = 10.0;

    /**
     * SPEC 11.9's winner bonus, wired by M19.
     *
     * <p>Read from a map rather than from storage: this runs on every sale, and SPEC 2.1
     * forbids a query there.
     */
    public void useWarRewards(dev.civitas.core.war.WarRewards rewards, double bonusPercent) {
        this.warRewards = rewards;
        this.warBonusPercent = bonusPercent;
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private static String metadata(String material, int amount) {
        return "{\"item\":\"" + material + "\",\"amount\":" + amount + "}";
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /**
     * A price at a moment.
     *
     * @param sellPrice what the market pays for one
     * @param buyPrice  what the market charges for one
     */
    public record Quote(MarketItem item, int stock, BigDecimal sellPrice, BigDecimal buyPrice) {
    }

    /**
     * What a completed transaction did.
     *
     * @param gross   before tax, for a sale; the full cost for a purchase
     * @param tax     the SPEC 4.3 sale tax, deleted from circulation; zero on a purchase
     * @param net     what actually reached or left the wallet
     * @param balance the wallet afterwards
     */
    public record Receipt(MarketItem item, int amount, BigDecimal gross, BigDecimal tax,
                          BigDecimal net, BigDecimal balance) {
    }
}
