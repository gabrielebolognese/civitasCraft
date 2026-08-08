package dev.civitas.core.market;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The market end to end over a real database: money, stock and the ledger together.
 *
 * <p>{@link MarketPricingTest} proves the curve. This proves that a sale moves exactly what
 * it should and writes down that it did.
 */
class MarketServiceTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private MarketService market;
    private UUID farmer;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        market = support.market;
        farmer = support.givenPlayer("Cincinnatus", new BigDecimal("10000.00"), 0L);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private BigDecimal wallet() {
        return support.playerRow(farmer).balance();
    }

    private List<LedgerRow> ledger(TransactionType type) {
        return await(support.daos.ledger().findByType(type.name(), 0L, 100));
    }

    private int stock(String material) {
        return market.registry().stockOf(material);
    }

    // ==================================================================================
    // The catalogue
    // ==================================================================================

    @Nested
    @DisplayName("The catalogue")
    class Catalogue {

        @Test
        @DisplayName("every SPEC 4.4 item is loaded, seeded at its target stock")
        void loadedFromConfig() {
            // SPEC 21.6 and 21.9 replaced SPEC 4.4's single nineteen-row table with two
            // lists that do different jobs: a narrow buy list, which is the only money
            // faucet, and a large builder's catalogue the server only sells.
            assertTrue(market.registry().buyList().size() <= 15,
                    "the buy list is the money faucet and SPEC 21.9 keeps it small");

            // How far the sell catalogue expands is asserted in MarketHardeningTest instead.
            // SPEC 21.6's groups resolve through Bukkit's tags, so counting them needs a
            // running server, and this class deliberately has none: everything it covers is
            // pricing and ledger arithmetic that a server would only slow down.
            assertTrue(market.registry().item("GLASS").isPresent(),
                    "a sell entry naming a material outright needs no group to resolve");
            assertEquals(20_000, stock("WHEAT"), "a new item opens at equilibrium");
            assertEquals(0, new BigDecimal("3")
                    .compareTo(market.registry().item("WHEAT").orElseThrow().basePrice()));
        }

        @Test
        @DisplayName("SPEC 4.4: an item absent from the table is not traded at all")
        void unlistedIsNotTraded() {
            // Two refusals that read alike and are not. NOT_TRADED is an item the market has
            // never heard of. NOT_BOUGHT is an item the server sells to builders and will
            // never buy back, which is the shape SPEC 21.6 gives most of the catalogue: "every
            // item the server buys is a potential money faucet. Every item the server sells is
            // a money sink and carries no exploit risk at all."
            assertTrue(market.registry().item("GLASS").isPresent(),
                    "the server sells glass to builders");
            assertFalse(market.registry().item("GLASS").orElseThrow().serverBuys());
            assertEquals("NOT_TRADED",
                    reasonOf(await(market.sell(farmer, "NOT_A_REAL_MATERIAL", 64))));
            assertEquals("NOT_BOUGHT",
                    reasonOf(await(market.sell(farmer, "GLASS", 64))));
            assertEquals("NOT_TRADED", reasonOf(await(market.buy(farmer, "GUNPOWDER", 1))));
        }

        @Test
        @DisplayName("material names are matched however they are typed")
        void caseInsensitive() {
            assertTrue(market.quote("wheat").isPresent());
            assertTrue(market.quote("WHEAT").isPresent());
            assertTrue(market.registry().trades("Wheat"));
        }
    }

    // ==================================================================================
    // Selling
    // ==================================================================================

    @Nested
    @DisplayName("Selling")
    class Selling {

        @Test
        @DisplayName("a sale pays the net, adds the stock, and writes both ledger rows")
        void sellPays() {
            BigDecimal before = wallet();

            Result<MarketService.Receipt> result = await(market.sell(farmer, "WHEAT", 64));
            assertTrue(result.isSuccess(), reasonOf(result));

            MarketService.Receipt receipt = result.orElseThrow();
            assertEquals(64, receipt.amount());
            assertEquals(0, receipt.gross().subtract(receipt.tax()).compareTo(receipt.net()));
            assertEquals(0, before.add(receipt.net()).compareTo(wallet()));
            assertEquals(20_064, stock("WHEAT"), "the market now holds what was sold to it");

            assertEquals(1, ledger(TransactionType.MARKET_SELL).size());
            assertEquals(1, ledger(TransactionType.MARKET_TAX).size());
        }

        @Test
        @DisplayName("SPEC 4.3: the tax is 5% and is deleted rather than paid to anyone")
        void taxIsDeleted() {
            MarketService.Receipt receipt = await(market.sell(farmer, "DIAMOND", 10)).orElseThrow();

            assertEquals(0, support.pricing.taxOn(receipt.gross(), 0).compareTo(receipt.tax()));
            assertTrue(receipt.tax().signum() > 0);

            LedgerRow tax = ledger(TransactionType.MARKET_TAX).get(0);
            assertTrue(tax.amount().signum() < 0, "recorded as money leaving the economy");
            assertEquals(0, receipt.tax().negate().compareTo(tax.amount()));

            // Circulation grew by the net only. The tax exists nowhere but the ledger.
            assertEquals(0, new BigDecimal("10000.00").add(receipt.net())
                    .compareTo(await(support.economy.totalInWallets())));
        }

        @Test
        @DisplayName("selling drives the price down, which is the whole point of SPEC 4.4")
        void sellingMovesThePrice() {
            BigDecimal before = market.quote("DIAMOND").orElseThrow().sellPrice();
            await(market.sell(farmer, "DIAMOND", 500));
            BigDecimal after = market.quote("DIAMOND").orElseThrow().sellPrice();

            assertTrue(after.compareTo(before) < 0,
                    "was " + before + ", still " + after + " after flooding the market");
        }

        @Test
        @DisplayName("the hundredth seller earns less than the first, SPEC 4.4")
        void laterSellersEarnLess() {
            BigDecimal first = await(market.sell(farmer, "WHEAT", 64)).orElseThrow().net();
            for (int i = 0; i < 100; i++) {
                await(market.sell(farmer, "WHEAT", 64));
            }
            BigDecimal hundredth = await(market.sell(farmer, "WHEAT", 64)).orElseThrow().net();

            assertTrue(hundredth.compareTo(first) < 0,
                    "first got " + first + ", hundredth got " + hundredth);
        }

        @Test
        @DisplayName("a sale of nothing is refused")
        void amountMustBePositive() {
            assertEquals("AMOUNT_NOT_POSITIVE", reasonOf(await(market.sell(farmer, "WHEAT", 0))));
            assertEquals("AMOUNT_NOT_POSITIVE", reasonOf(await(market.sell(farmer, "WHEAT", -5))));
        }

        @Test
        @DisplayName("a failed sale moves no stock, so it cannot move the price")
        void failedSaleLeavesStockAlone() {
            int before = stock("WHEAT");
            await(market.sell(UUID.randomUUID(), "WHEAT", 64));

            assertEquals(before, stock("WHEAT"));
        }
    }

    // ==================================================================================
    // Buying, and SPEC 17.3 case 28
    // ==================================================================================

    @Nested
    @DisplayName("Buying")
    class Buying {

        @Test
        @DisplayName("a purchase charges the buyer and takes the stock down")
        void buyCharges() {
            BigDecimal before = wallet();

            MarketService.Receipt receipt = await(market.buy(farmer, "WHEAT", 100)).orElseThrow();

            assertEquals(0, before.subtract(receipt.net()).compareTo(wallet()));
            assertEquals(19_900, stock("WHEAT"));
            assertEquals(1, ledger(TransactionType.MARKET_BUY).size());
            assertEquals(0, BigDecimal.ZERO.compareTo(receipt.tax()), "buying is untaxed");
        }

        @Test
        @DisplayName("a purchase beyond the wallet is refused and moves no stock")
        void cannotAfford() {
            int before = stock("ANCIENT_DEBRIS");

            assertEquals("INSUFFICIENT_FUNDS",
                    reasonOf(await(market.buy(farmer, "ANCIENT_DEBRIS", 100))));
            assertEquals(before, stock("ANCIENT_DEBRIS"));
            assertEquals(0, new BigDecimal("10000.00").compareTo(wallet()));
        }

        @Test
        @DisplayName("SPEC 17.3 case 28: the market keeps selling once stock hits zero")
        void infiniteSeller() {
            await(support.economy.give(farmer, new BigDecimal("100000000"),
                    TransactionType.ADMIN_GIVE, null, null));

            await(market.registry().setStock("QUARTZ", 0));
            assertTrue(await(market.buy(farmer, "QUARTZ", 500)).isSuccess(),
                    "buying past zero is allowed");

            assertTrue(stock("QUARTZ") < 0, "stock goes negative internally");
            assertEquals(0, support.pricing.clampMax()
                            .multiply(market.registry().item("QUARTZ").orElseThrow().basePrice())
                            .compareTo(market.quote("QUARTZ").orElseThrow().sellPrice()),
                    "and the price holds at the clamp rather than running away");
        }
    }

    // ==================================================================================
    // SPEC 17.6 case 75
    // ==================================================================================

    @Test
    @DisplayName("SPEC 17.6 case 75: buying and selling straight back is a loss, with money")
    void arbitrageLosesRealMoney() {
        BigDecimal before = wallet();

        await(market.buy(farmer, "DIAMOND", 5));
        await(market.sell(farmer, "DIAMOND", 5));

        assertTrue(wallet().compareTo(before) < 0,
                "started with " + before + ", ended with " + wallet());
    }

    // ==================================================================================
    // Switches
    // ==================================================================================

    @Test
    @DisplayName("the market can be closed entirely from config")
    void marketCanBeDisabled() {
        support.configs.get(ConfigFile.ECONOMY).set("market.enabled", false);

        assertEquals("MARKET_DISABLED", reasonOf(await(market.sell(farmer, "WHEAT", 1))));
        assertEquals("MARKET_DISABLED", reasonOf(await(market.buy(farmer, "WHEAT", 1))));
    }

    @Test
    @DisplayName("stock survives a reload, so a config change is not a price reset")
    void reloadKeepsStock() {
        await(market.sell(farmer, "WHEAT", 500));
        assertEquals(20_500, stock("WHEAT"));

        await(market.registry().loadAll());

        assertEquals(20_500, stock("WHEAT"));
    }
}
