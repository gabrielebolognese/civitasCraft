package dev.civitas.core.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.DatabaseSettings;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.MoneySupplyRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 21.4 Class G.
 *
 * <p>"The plugin must be able to answer, at any moment, 'how much money exists and where did it
 * come from.' <b>Without this you cannot detect an exploit you did not predict.</b>"
 *
 * <p>{@link Escrow#escrowIsNotLost} is the assertion that matters most. Money held by a war wager
 * or an open bounty is in neither a wallet nor a treasury, so a supply figure without it appears to
 * shrink the moment a war is declared and to grow when it resolves — which is exactly the shape of
 * a leak, arriving from a system working perfectly.
 */
class MoneySupplyServiceTest {

    @TempDir
    Path directory;

    private static final long NOW = 1_700_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private dev.civitas.core.city.CityTestSupport cities;
    private DaoRegistry daos;
    private MoneySupplyService supply;

    @BeforeEach
    void setUp() {
        // The real stack rather than stubs, so record() can be exercised end to end: a supply
        // figure assembled from mocks would prove the arithmetic and nothing about whether the
        // three stocks it adds up are the three that exist.
        cities = dev.civitas.core.city.CityTestSupport.open(directory);
        daos = cities.daos;
        supply = new MoneySupplyService(daos, cities.economy, cities.treasury, cities.configs,
                quiet());
    }

    @AfterEach
    void tearDown() {
        cities.close();
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("money-supply-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void reading(long at, String wallets, String treasuries, String escrow) {
        await(daos.moneySupply().insert(new MoneySupplyRow(0, at, new BigDecimal(wallets),
                new BigDecimal(treasuries), new BigDecimal(escrow))));
    }

    private void ledger(long at, String type, String amount, UUID actor) {
        await(daos.ledger().insert(new LedgerRow(0, at, type, actor, null, null,
                new BigDecimal(amount), BigDecimal.ZERO, null)));
    }

    @Nested
    @DisplayName("the stocks")
    class Stocks {

        @Test
        @DisplayName("circulation is wallets plus treasuries plus escrow")
        void circulation() {
            MoneySupplyRow row = new MoneySupplyRow(1, NOW, new BigDecimal("1000.00"),
                    new BigDecimal("500.00"), new BigDecimal("250.00"));

            assertEquals(0, row.circulation().compareTo(new BigDecimal("1750.00")));
        }

        @Test
        @DisplayName("readings come back oldest first, which is the order a graph is drawn in")
        void ordered() {
            reading(NOW - 2 * DAY, "100", "0", "0");
            reading(NOW - DAY, "200", "0", "0");
            reading(NOW, "300", "0", "0");

            List<MoneySupplyRow> rows = await(daos.moneySupply().findSince(NOW - 3 * DAY));

            assertEquals(3, rows.size());
            assertEquals(0, rows.get(0).playerTotal().compareTo(new BigDecimal("100")));
            assertEquals(0, rows.get(2).playerTotal().compareTo(new BigDecimal("300")));
        }

        @Test
        @DisplayName("pruning keeps the table proportional to the window it serves")
        void prunes() {
            reading(NOW - 200 * DAY, "1", "0", "0");
            reading(NOW - 10 * DAY, "2", "0", "0");

            assertEquals(1, await(supply.prune(NOW)), "the 200-day-old reading");
            assertEquals(1, await(daos.moneySupply().findSince(0)).size());
        }
    }

    @Nested
    @DisplayName("SPEC 21.4's escrow, the term that is easy to leave out")
    class Escrow {

        @Test
        @DisplayName("money in escrow is counted, not lost")
        void escrowIsNotLost() {
            // Without this term a war declaration reads as 100,000 C vanishing from the economy
            // and its resolution as 100,000 C appearing. Both look exactly like a leak, and both
            // are a system working correctly.
            MoneySupplyRow before = new MoneySupplyRow(1, NOW, new BigDecimal("1000000"),
                    new BigDecimal("500000"), BigDecimal.ZERO);
            MoneySupplyRow during = new MoneySupplyRow(2, NOW + DAY, new BigDecimal("1000000"),
                    new BigDecimal("400000"), new BigDecimal("100000"));

            assertEquals(0, before.circulation().compareTo(during.circulation()),
                    "declaring a war must not change how much money exists");
        }
    }

    @Nested
    @DisplayName("the flows, read from the ledger rather than copied")
    class Flows {

        @Test
        @DisplayName("created and destroyed are summed separately, not netted")
        void notNetted() {
            // A type like TREASURY_DEPOSIT writes both sides and would net to zero. What the
            // report has to show is that money moved, not that nothing happened.
            UUID actor = UUID.randomUUID();
            ledger(NOW - DAY, "TREASURY_DEPOSIT", "1000", actor);
            ledger(NOW - DAY, "TREASURY_DEPOSIT", "-1000", actor);

            var flows = await(daos.ledger().flowsSince(NOW - 2 * DAY));

            assertEquals(1, flows.size());
            assertEquals(0, flows.get(0).in().compareTo(new BigDecimal("1000")));
            assertEquals(0, flows.get(0).out().compareTo(new BigDecimal("1000")));
            assertEquals(0, flows.get(0).net().signum(), "and the net is still zero, correctly");
        }

        @Test
        @DisplayName("a faucet and a sink are told apart")
        void faucetAndSink() {
            UUID actor = UUID.randomUUID();
            ledger(NOW - DAY, "MARKET_SELL", "5000", actor);
            ledger(NOW - DAY, "MARKET_TAX", "-250", actor);
            ledger(NOW - DAY, "UPKEEP_CHARGE", "-800", actor);

            var report = await(supply.supplyOver(7, NOW));

            assertEquals(0, report.created().compareTo(new BigDecimal("5000")));
            assertEquals(0, report.destroyed().compareTo(new BigDecimal("1050")));
            assertEquals("MARKET_SELL", report.topSources(3).get(0).type());
            assertEquals("UPKEEP_CHARGE", report.topSinks(3).get(0).type(),
                    "the largest sink first, which is where an operator looks");
        }

        @Test
        @DisplayName("the window is respected, so yesterday's exploit does not hide in last year")
        void windowed() {
            UUID actor = UUID.randomUUID();
            ledger(NOW - 400 * DAY, "MARKET_SELL", "9999999", actor);
            ledger(NOW - DAY, "MARKET_SELL", "100", actor);

            assertEquals(0, await(supply.supplyOver(7, NOW)).created()
                    .compareTo(new BigDecimal("100")));
        }

        @Test
        @DisplayName("one player's income by source, largest first")
        void sources() {
            // SPEC 22.7.1: "Answers 'where did this come from.'"
            UUID suspect = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            ledger(NOW - DAY, "MARKET_SELL", "50000", suspect);
            ledger(NOW - DAY, "DAILY_LOGIN", "250", suspect);
            ledger(NOW - DAY, "MARKET_SELL", "1000000", other);

            var flows = await(supply.sourcesFor(suspect, 7, NOW));

            assertEquals(2, flows.size(), "and nobody else's income leaks in");
            assertEquals("MARKET_SELL", flows.get(0).type());
            assertEquals(0, flows.get(0).in().compareTo(new BigDecimal("50000")));
        }
    }

    @Nested
    @DisplayName("SPEC 4.8's week-over-week question")
    class Growth {

        @Test
        @DisplayName("the change is measured against the reading nearest the window's start")
        void change() {
            // Nearest rather than oldest-kept: an oldest-kept baseline would drift every time
            // retention pruned the table, so the same command would report a different answer
            // for the same week.
            reading(NOW - 30 * DAY, "100000", "0", "0");
            reading(NOW - 7 * DAY, "1000000", "0", "0");
            reading(NOW, "1200000", "0", "0");

            var report = await(supply.supplyOver(7, NOW));

            assertEquals(0, report.change().orElseThrow().compareTo(new BigDecimal("200000")));
            assertEquals(0, report.percentChange().orElseThrow()
                    .compareTo(new BigDecimal("20.00")), "20% growth in a week");
        }

        @Test
        @DisplayName("with nothing to compare against it says so rather than guessing")
        void noBaseline() {
            var report = await(supply.supplyOver(7, NOW));

            assertTrue(report.now().isEmpty());
            assertTrue(report.change().isEmpty());
            assertTrue(report.percentChange().isEmpty());
            assertEquals(0, report.readings());
        }

        @Test
        @DisplayName("a single reading is not a trend")
        void oneReading() {
            reading(NOW, "1000", "0", "0");

            var report = await(supply.supplyOver(7, NOW));

            assertTrue(report.now().isPresent());
            assertEquals(0, report.change().orElseThrow().signum(),
                    "compared against itself, which is zero rather than a fabricated baseline");
            assertFalse(report.readings() == 0);
        }
    }
}
