package dev.civitas.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 18.1: "Market price formula at stock 0, at target, at 10x target, and clamp
 * boundaries."
 *
 * <p>Wheat is the worked example throughout: base 3, target 20,000, elasticity 0.40, exactly
 * as SPEC 4.4 lists it.
 */
class MarketPricingTest {

    private static final MarketItem WHEAT =
            new MarketItem("WHEAT", new BigDecimal("3"), 20_000, 0.40);
    private static final MarketItem DIAMOND =
            new MarketItem("DIAMOND", new BigDecimal("400"), 1_500, 0.60);

    @TempDir
    Path directory;

    private ConfigManager configs;
    private MarketPricing pricing;

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("market-pricing-test");
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        configs = new ConfigManager(PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        pricing = new MarketPricing(configs);
    }

    private static double multiplier(MarketPricing pricing, MarketItem item, int stock) {
        return pricing.multiplier(item, stock).doubleValue();
    }

    // ==================================================================================
    // The four points SPEC 18.1 names
    // ==================================================================================

    @Nested
    @DisplayName("The SPEC 18.1 reference points")
    class ReferencePoints {

        @Test
        @DisplayName("at stock 0 the market is bought out and the multiplier clamps at 3.0")
        void atZeroStock() {
            // (20000 / 1) ^ 0.40 is about 52, far past the ceiling.
            assertEquals(3.0, multiplier(pricing, WHEAT, 0), 1e-9);
            assertEquals(0, new BigDecimal("9.00").compareTo(pricing.unitSellPrice(WHEAT, 0)),
                    "three times the 3 C base");
        }

        @Test
        @DisplayName("at target stock the price is the base price")
        void atTarget() {
            // The formula divides by stock + 1, so the multiplier is exactly 1 one unit
            // below target, and 0.99998 at target itself. Both are base price to a player;
            // the exact point is asserted here so the off-by-one is deliberate and visible.
            assertEquals(1.0, multiplier(pricing, WHEAT, WHEAT.targetStock() - 1), 1e-12);
            assertEquals(0, new BigDecimal("3.00")
                    .compareTo(pricing.unitSellPrice(WHEAT, WHEAT.targetStock() - 1)));

            assertEquals(1.0, multiplier(pricing, WHEAT, WHEAT.targetStock()), 1e-4);
            assertEquals(0, new BigDecimal("2.99")
                    .compareTo(pricing.unitSellPrice(WHEAT, WHEAT.targetStock())),
                    "floored, never rounded up");
        }

        @Test
        @DisplayName("at ten times target the price falls, but not to the floor")
        void atTenTimesTarget() {
            // (1 / 10) ^ 0.40 = 0.398.
            double actual = multiplier(pricing, WHEAT, WHEAT.targetStock() * 10);
            assertEquals(0.398, actual, 0.001);
            assertTrue(actual > pricing.clampMin().doubleValue(),
                    "still above the floor, so the clamp is not what produced this");
            assertEquals(0, new BigDecimal("1.19")
                    .compareTo(pricing.unitSellPrice(WHEAT, WHEAT.targetStock() * 10)));
        }

        @Test
        @DisplayName("far past target the multiplier clamps at 0.25 and stays there")
        void atTheFloor() {
            // 0.25 is reached at (target / stock) ^ 0.4 = 0.25, so stock about 32x target.
            assertEquals(0.25, multiplier(pricing, WHEAT, WHEAT.targetStock() * 40), 1e-9);
            assertEquals(0.25, multiplier(pricing, WHEAT, WHEAT.targetStock() * 4000), 1e-9,
                    "the floor holds however much is dumped");
            assertEquals(0, new BigDecimal("0.75")
                    .compareTo(pricing.unitSellPrice(WHEAT, WHEAT.targetStock() * 40)));
        }
    }

    // ==================================================================================
    // SPEC 17.3 case 28
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 17.3 case 28: stock at and below zero")
    class SoldOut {

        @Test
        @DisplayName("negative stock is allowed, and the price clamp holds")
        void negativeStock() {
            // stock + 1 is zero here, which would be a division by zero.
            assertEquals(3.0, multiplier(pricing, WHEAT, -1), 1e-9);
            assertEquals(3.0, multiplier(pricing, WHEAT, -50_000), 1e-9);
            assertEquals(0, new BigDecimal("9.00").compareTo(pricing.unitSellPrice(WHEAT, -50_000)));
        }

        @Test
        @DisplayName("buying stays possible at any stock, because the market is infinite")
        void buyingIsAlwaysPriced() {
            assertTrue(pricing.unitBuyPrice(WHEAT, -1_000).signum() > 0);
            assertEquals(0, new BigDecimal("12.15").compareTo(pricing.unitBuyPrice(WHEAT, -1_000)),
                    "3 base x 3.0 clamp x 1.35 spread");
        }
    }

    // ==================================================================================
    // The spread, SPEC 17.6 case 75
    // ==================================================================================

    @Nested
    @DisplayName("The buy/sell spread")
    class Spread {

        @Test
        @DisplayName("the market sells at 1.35x what it pays")
        void spreadIsApplied() {
            BigDecimal pays = pricing.unitSellPrice(DIAMOND, DIAMOND.targetStock() - 1);
            BigDecimal charges = pricing.unitBuyPrice(DIAMOND, DIAMOND.targetStock() - 1);

            assertEquals(0, new BigDecimal("400.00").compareTo(pays));
            assertEquals(0, new BigDecimal("540.00").compareTo(charges));
        }

        @Test
        @DisplayName("SPEC 17.6 case 75: buying then selling back always loses money")
        void arbitrageAlwaysLoses() {
            int stock = DIAMOND.targetStock();

            // Buy ten, then sell the same ten straight back. Buying pushes the price up,
            // which helps the resale, and it is still a loss.
            BigDecimal spent = pricing.buyCost(DIAMOND, stock, 10);
            BigDecimal recovered = pricing.grossSellValue(DIAMOND, stock - 10, 10);

            assertTrue(recovered.compareTo(spent) < 0,
                    "recovered " + recovered + " for " + spent + " spent");
            double loss = 1 - recovered.doubleValue() / spent.doubleValue();
            assertTrue(loss > 0.20, "expected to lose most of the 26% spread, lost " + loss);
        }
    }

    // ==================================================================================
    // Batches walk the curve
    // ==================================================================================

    @Nested
    @DisplayName("Batches")
    class Batches {

        @Test
        @DisplayName("selling a batch prices each unit as the stock moves under it")
        void batchWalksTheCurve() {
            // Priced around target, where the curve is live rather than clamped: the first
            // unit is worth more than the hundredth.
            BigDecimal batch = pricing.grossSellValue(WHEAT, 19_000, 100);
            BigDecimal flat = pricing.unitSellPrice(WHEAT, 19_000).multiply(BigDecimal.valueOf(100));

            assertTrue(batch.compareTo(flat) < 0,
                    "a hundred at the first unit's price would be " + flat + ", got " + batch);
        }

        @Test
        @DisplayName("a batch equals the sum of the same sales made one at a time")
        void batchMatchesOneByOne() {
            BigDecimal batch = pricing.grossSellValue(WHEAT, 500, 25);

            BigDecimal oneByOne = BigDecimal.ZERO;
            for (int i = 0; i < 25; i++) {
                oneByOne = oneByOne.add(pricing.unitSellPrice(WHEAT, 500 + i));
            }

            assertEquals(0, oneByOne.compareTo(batch),
                    "splitting a sale must never beat making it in one go");
        }

        @Test
        @DisplayName("buying a batch gets more expensive as it goes")
        void buyingWalksUp() {
            BigDecimal batch = pricing.buyCost(DIAMOND, DIAMOND.targetStock(), 50);
            BigDecimal flat = pricing.unitBuyPrice(DIAMOND, DIAMOND.targetStock())
                    .multiply(BigDecimal.valueOf(50));

            assertTrue(batch.compareTo(flat) > 0, "buying out the stock should cost more");
        }
    }

    // ==================================================================================
    // Tax
    // ==================================================================================

    @Nested
    @DisplayName("The sale tax")
    class Tax {

        @Test
        @DisplayName("5% by default, from config")
        void defaultTax() {
            assertEquals(5.0, pricing.taxPercent(0), 1e-9);
            assertEquals(0, new BigDecimal("50.00")
                    .compareTo(pricing.taxOn(new BigDecimal("1000"), 0)));
        }

        @Test
        @DisplayName("SPEC 5.7 Market Access takes 0.8 points off per level")
        void marketAccessReducesTax() {
            assertEquals(4.2, pricing.taxPercent(1), 1e-9);
            assertEquals(1.0, pricing.taxPercent(5), 1e-9, "five levels is 4% off 5%");
        }

        @Test
        @DisplayName("the tax can never go negative and pay the seller a bonus")
        void taxFloorsAtZero() {
            assertEquals(0.0, pricing.taxPercent(99), 1e-9);
            assertEquals(0, BigDecimal.ZERO.compareTo(pricing.taxOn(new BigDecimal("1000"), 99)));
        }
    }

    // ==================================================================================
    // Decay
    // ==================================================================================

    @Nested
    @DisplayName("Stock decay")
    class Decay {

        @Test
        @DisplayName("an oversupplied market drains toward target at 2% an hour")
        void decaysDown() {
            int start = 30_000;
            int after = pricing.decayed(WHEAT, start);

            assertEquals(start - 200, after, "2% of the 10,000 gap");
            assertTrue(after > WHEAT.targetStock(), "and it has not overshot");
        }

        @Test
        @DisplayName("a drained market refills toward target, so prices come back down")
        void decaysUp() {
            assertEquals(400, pricing.decayed(WHEAT, 0), "2% of the 20,000 gap");
        }

        @Test
        @DisplayName("decay never overshoots the target it is aiming at")
        void neverOvershoots() {
            assertEquals(WHEAT.targetStock(), pricing.decayed(WHEAT, WHEAT.targetStock() - 1));
            assertEquals(WHEAT.targetStock(), pricing.decayed(WHEAT, WHEAT.targetStock() + 1));
            assertEquals(WHEAT.targetStock(), pricing.decayed(WHEAT, WHEAT.targetStock()));
        }

        @Test
        @DisplayName("the rate is a config key, and zero means never")
        void rateIsConfigurable() {
            configs.get(ConfigFile.ECONOMY).set("market.stock-decay-percent-per-hour", 0.0);
            assertEquals(30_000, pricing.decayed(WHEAT, 30_000));
        }
    }

    // ==================================================================================
    // Everything is config
    // ==================================================================================

    @Test
    @DisplayName("the clamps are config keys, not constants")
    void clampsAreConfigurable() {
        assertEquals(0, new BigDecimal("0.25").compareTo(pricing.clampMin()));
        assertEquals(0, new BigDecimal("3.0").compareTo(pricing.clampMax()));

        configs.get(ConfigFile.ECONOMY).set("market.clamp-max", 1.5);
        assertEquals(1.5, multiplier(pricing, WHEAT, 0), 1e-9);
    }

    @Test
    @DisplayName("the spread is a config key")
    void spreadIsConfigurable() {
        configs.get(ConfigFile.ECONOMY).set("market.buy-spread", 2.0);
        assertEquals(0, new BigDecimal("800.00")
                .compareTo(pricing.unitBuyPrice(DIAMOND, DIAMOND.targetStock() - 1)));
    }

    @Test
    @DisplayName("a misconfigured item is refused at construction, not priced as nonsense")
    void badDefinitionsThrow() {
        assertTrue(assertThrows(() -> new MarketItem("X", new BigDecimal("1"), 0, 0.4)));
        assertTrue(assertThrows(() -> new MarketItem("X", BigDecimal.ZERO, 100, 0.4)));
        assertTrue(assertThrows(() -> new MarketItem("X", new BigDecimal("1"), 100, 0)));
    }

    private static boolean assertThrows(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
