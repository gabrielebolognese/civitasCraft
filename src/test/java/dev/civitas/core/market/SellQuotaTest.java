package dev.civitas.core.market;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SPEC 21.5 daily sell quota.
 *
 * <p>SPEC calls this "the single most important mechanism in the revised economy, because it is
 * exploit-agnostic. It bounds money creation regardless of how clever the exploit is." Every
 * other defence in Part II names a vector; this one holds against vectors nobody has thought of
 * yet, and that is only true if the arithmetic is exact.
 *
 * <p>Each rule is asserted twice, in the shape M22's anti-toxicity audit established: that it
 * <b>happens</b>, and that changing its config key <b>changes the behaviour</b>. The second is
 * what catches a rule sitting behind a key nothing reads, which is how SPEC 17.1 hid for
 * twenty-one milestones.
 */
class SellQuotaTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private MarketService market;
    private SellQuota quota;
    private UUID farmer;

    /** A price that makes the arithmetic legible: diamonds open at 400 C each. */
    private static final String ITEM = "DIAMOND";

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        market = support.market;
        quota = support.sellQuota;
        farmer = support.givenPlayer("Cincinnatus", new BigDecimal("10000.00"), 0L);
        // No tax, so every figure below is the quota's doing and not the tax's.
        support.configs.get(ConfigFile.ECONOMY).set("market.sale-tax-percent", 0);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    // ==================================================================================
    // The cap itself
    // ==================================================================================

    @Nested
    @DisplayName("the cap, SPEC 21.5")
    class Cap {

        @Test
        @DisplayName("a sale inside the quota is paid in full")
        void insideQuotaIsUntouched() {
            setQuota(25_000);

            MarketService.Receipt receipt = sell(1);

            assertEquals(0, receipt.listed().compareTo(receipt.gross()),
                    "nothing was reduced, so the payable equals the listed value");
            assertFalse(receipt.quotaReduced());
            assertEquals(0, receipt.quota().used().compareTo(receipt.listed()),
                    "the counter moved by exactly what was sold");
        }

        @Test
        @DisplayName("past the quota, the sale is paid at the over-quota multiplier")
        void pastQuotaIsReduced() {
            setQuota(100);
            setMultiplier(0.2);

            sell(1);                                     // consumes the whole 100 and more
            MarketService.Receipt second = sell(1);      // entirely past the cap

            assertTrue(second.quota().over(), "the quota was already gone");
            assertEquals(0, second.gross().compareTo(
                            expected(second.listed(), "0.2")),
                    "listed " + second.listed() + " should have paid a fifth, paid "
                            + second.gross());
        }

        @Test
        @DisplayName("a sale straddling the boundary is split at it, not reduced wholesale")
        void straddlingSaleIsSplit() {
            // The design decision SPEC 21.5 does not make. Reducing a whole sale because its
            // last coin crossed the line would make the quota a puzzle about batch sizes.
            BigDecimal unit = market.quote(ITEM).orElseThrow().sellPrice();
            setQuota(unit.doubleValue() * 1.5);          // one and a half units of headroom
            setMultiplier(0.5);

            MarketService.Receipt receipt = sell(2);

            BigDecimal headroom = quota.dailyQuota();
            BigDecimal overflow = receipt.listed().subtract(headroom);
            BigDecimal expected = headroom.add(overflow.multiply(new BigDecimal("0.5")));

            assertTrue(receipt.quotaReduced(), "part of this sale was past the cap");
            assertEquals(0, receipt.gross().compareTo(dev.civitas.core.economy.Money
                            .floor(expected)),
                    "expected " + expected + " (headroom " + headroom + " at full, the rest "
                            + "at half), paid " + receipt.gross());
        }

        @Test
        @DisplayName("selling in pieces pays the same as selling in one go")
        void splittingASaleGainsNothing() {
            // The property that makes splitting-at-the-boundary the right reading. If it did
            // not hold, the optimal play would be to work out where the line is and sell
            // exactly up to it, which is not a game anyone should have to play.
            setQuota(1_000);
            setMultiplier(0.2);

            BigDecimal inOneGo = totalPaid(sell(4));

            // A second player, same starting state, selling the same four one at a time.
            UUID other = support.givenPlayer("Cato", new BigDecimal("10000.00"), 0L);
            resetStock();
            BigDecimal inPieces = BigDecimal.ZERO;
            for (int i = 0; i < 4; i++) {
                inPieces = inPieces.add(totalPaid(sellAs(other, 1)));
            }

            // Within a coin: the price curve moves per unit either way, so the two paths walk
            // the same curve and the only question is how the quota split them.
            assertTrue(inOneGo.subtract(inPieces).abs().compareTo(new BigDecimal("1")) <= 0,
                    "one go paid " + inOneGo + ", four sales paid " + inPieces);
        }

        @Test
        @DisplayName("it is a soft cap: selling past it still works")
        void softCapNeverBlocks() {
            // SPEC 21.5: "A player who hits it can still sell, just at a fifth of the value.
            // Hard blocks feel like punishment and generate support tickets."
            setQuota(1);

            Result<MarketService.Receipt> result = await(market.sell(farmer, ITEM, 1));

            assertTrue(result.isSuccess(), reasonOf(result));
            assertTrue(result.orElseThrow().net().signum() > 0, "and it still paid something");
        }
    }

    // ==================================================================================
    // What the quota is measured in
    // ==================================================================================

    @Nested
    @DisplayName("measured in value, SPEC 21.5")
    class Measurement {

        @Test
        @DisplayName("switching items does not refresh the quota")
        void cannotBeGamedBySwitchingItems() {
            // SPEC 21.5: "Quota is measured in value, not item count, so it cannot be gamed by
            // switching items." A per-item counter would let a player sell 25,000 of diamonds
            // and then 25,000 of wheat.
            setQuota(1_000);
            setMultiplier(0.2);

            sell(3);                                     // well past 1,000 C of diamonds
            MarketService.Receipt wheat = sellItem("WHEAT", 64);

            assertTrue(wheat.quota().over(),
                    "the wheat sale found the quota already spent by the diamonds");
        }

        @Test
        @DisplayName("the counter tracks value sold at full rate, not coins paid")
        void counterTracksFullRateValue() {
            // Otherwise a player past the cap would burn five coins of quota for every one
            // they were paid, and the counter would race past the cap for no reason.
            setQuota(100);
            setMultiplier(0.2);

            sell(1);
            BigDecimal afterFirst = await(quota.status(farmer, now())).used();
            sell(1);
            BigDecimal afterSecond = await(quota.status(farmer, now())).used();

            assertEquals(0, afterFirst.compareTo(afterSecond),
                    "the counter pinned at the cap instead of running away past it");
            assertEquals(0, afterSecond.compareTo(quota.dailyQuota()));
        }
    }

    // ==================================================================================
    // The day
    // ==================================================================================

    @Nested
    @DisplayName("the daily reset, SPEC 21.5")
    class Reset {

        @Test
        @DisplayName("the period begins at 00:00 server time by default")
        void resetsAtMidnight() {
            long noon = Instant.parse("2026-08-08T12:00:00Z").toEpochMilli();
            SellQuota utc = utcQuota();

            assertEquals(Instant.parse("2026-08-08T00:00:00Z").toEpochMilli(),
                    utc.periodStart(noon));
            assertEquals(Instant.parse("2026-08-09T00:00:00Z").toEpochMilli(),
                    utc.nextReset(noon));
        }

        @Test
        @DisplayName("quota-reset-hour moves the boundary, and before it the day is yesterday's")
        void resetHourIsConfigurable() {
            support.configs.get(ConfigFile.ECONOMY).set("market.quota-reset-hour", 4);
            SellQuota utc = utcQuota();

            long twoAm = Instant.parse("2026-08-08T02:00:00Z").toEpochMilli();
            long sixAm = Instant.parse("2026-08-08T06:00:00Z").toEpochMilli();

            assertEquals(Instant.parse("2026-08-07T04:00:00Z").toEpochMilli(),
                    utc.periodStart(twoAm),
                    "before the reset hour, the current period began yesterday");
            assertEquals(Instant.parse("2026-08-08T04:00:00Z").toEpochMilli(),
                    utc.periodStart(sixAm));
        }

        @Test
        @DisplayName("a counter from a day that has turned reads as a fresh quota")
        void staleRowIsAFreshQuota() {
            setQuota(100);
            sell(1);
            assertTrue(await(quota.status(farmer, now())).used().signum() > 0);

            long tomorrow = now() + 86_400_000L;

            assertEquals(0, await(quota.status(farmer, tomorrow)).used().signum(),
                    "the day turned, so the counter reads zero without anything sweeping it");
            assertEquals(0, await(quota.status(farmer, tomorrow)).remaining()
                    .compareTo(quota.dailyQuota()));
        }

        @Test
        @DisplayName("the counter survives a restart")
        void persisted() {
            // A quota held only in memory would reset every time the server restarted, which
            // on a server that restarts nightly is no quota at all.
            setQuota(10_000);
            sell(2);
            BigDecimal used = await(quota.status(farmer, now())).used();
            assertTrue(used.signum() > 0);

            SellQuota reopened = new SellQuota(support.configs, support.daos.sellQuota(),
                    ZoneId.systemDefault());

            assertEquals(0, used.compareTo(await(reopened.status(farmer, now())).used()));
        }
    }

    // ==================================================================================
    // SPEC 21.10.3, exact under concurrency
    // ==================================================================================

    @Nested
    @DisplayName("exact under concurrency, SPEC 21.10.3")
    class Concurrency {

        @Test
        @DisplayName("eight racing sales never take the counter past the cap")
        void racingSalesDoNotOvershoot() throws Exception {
            // SPEC 21.10.3: "Must be exact under concurrency, so it goes through the same
            // synchronised service method as balance mutation."
            //
            // Read what this does and does not prove. It asserts the invariant end to end,
            // which is worth having. It does NOT prove the per-player lock is doing the work:
            // removing the lock entirely leaves this test green, because SQLite serialises
            // writers and hands the read-modify-write its atomicity for free. On MySQL, whose
            // default REPEATABLE READ lets two transactions read the same row before either
            // writes, the lock is load-bearing and nothing here exercises it. Recorded in
            // OPEN_QUESTIONS.md rather than left as a test that looks stronger than it is.
            setQuota(10_000);
            setMultiplier(0.0);        // over-quota pays nothing, so any leak is visible

            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger succeeded = new AtomicInteger();
            try {
                List<java.util.concurrent.Future<?>> running = new java.util.ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    running.add(pool.submit(() -> {
                        start.await();
                        Result<MarketService.Receipt> result =
                                await(market.sell(farmer, ITEM, 1));
                        if (result.isSuccess()) {
                            succeeded.incrementAndGet();
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (java.util.concurrent.Future<?> task : running) {
                    task.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            BigDecimal used = await(quota.status(farmer, now())).used();

            assertTrue(succeeded.get() > 0, "at least some of the eight sales went through");
            assertTrue(used.compareTo(quota.dailyQuota()) <= 0,
                    "the counter overshot the cap to " + used + ", which means two threads "
                            + "spent the same headroom");
        }
    }

    // ==================================================================================
    // What the quota does not touch
    // ==================================================================================

    @Nested
    @DisplayName("what is exempt")
    class Exemptions {

        @Test
        @DisplayName("peer trade is paid in full and does not touch the counter, SPEC 21.5")
        void peerTradeIsExempt() {
            // Deliberate, and the point rather than an oversight. SPEC 21.5: "Peer trade moves
            // money, it does not create it. This makes the peer economy strictly more
            // attractive than the server market for high-volume producers, which is exactly
            // the behaviour we want."
            setQuota(1);

            UUID shopper = support.givenPlayer("Brutus", new BigDecimal("100000.00"), 0L);
            BigDecimal before = support.playerRow(farmer).balance();
            assertTrue(await(support.economy.pay(shopper, farmer, new BigDecimal("50000.00")))
                    .isSuccess());

            assertEquals(0, support.playerRow(farmer).balance().subtract(before)
                            .compareTo(new BigDecimal("50000.00")),
                    "a peer payment landed in full with the quota set to one coin");
            assertEquals(0, await(quota.status(farmer, now())).used().signum(),
                    "and it did not touch the quota counter");
        }

        @Test
        @DisplayName("the chest-shop path cannot reach the quota even by accident")
        void playerShopsCannotBeQuotad() {
            // Stronger than exercising a sale, and the same shape M22 used for the shop tax:
            // assert that no code path exists rather than that one path behaves. A shop sale
            // needs real inventories, so exercising it would mean a running server here, and
            // a test that needs a server proves less than this one line.
            String source = readSource(
                    "src/main/java/dev/civitas/core/shop/PlayerShopService.java");

            assertFalse(source.contains("SellQuota"),
                    "PlayerShopService now references the sell quota. SPEC 21.5 exempts peer "
                            + "trade deliberately: it moves money rather than creating it, and "
                            + "the exemption is what makes selling to players more attractive "
                            + "than selling to the server.");
        }

        @Test
        @DisplayName("buying from the server is not a sale and does not spend quota")
        void buyingIsExempt() {
            setQuota(100);

            await(market.buy(farmer, ITEM, 1));

            assertEquals(0, await(quota.status(farmer, now())).used().signum());
        }
    }

    // ==================================================================================
    // Configurable, in the M22 shape
    // ==================================================================================

    @Nested
    @DisplayName("configurable")
    class Configurable {

        @Test
        @DisplayName("daily-sell-quota changes where the cap falls")
        void quotaKeyIsRead() {
            setQuota(1_000_000);
            assertFalse(sell(1).quotaReduced(), "a huge quota reduces nothing");

            resetStock();
            setQuota(1);
            assertTrue(sell(1).quotaReduced(), "a tiny one reduces almost everything");
        }

        @Test
        @DisplayName("over-quota-multiplier changes what a reduced sale pays")
        void multiplierKeyIsRead() {
            setQuota(1);
            setMultiplier(0.5);
            BigDecimal atHalf = sell(1).gross();

            resetStock();
            setMultiplier(0.1);
            BigDecimal atATenth = sell(1).gross();

            assertTrue(atATenth.compareTo(atHalf) < 0,
                    "0.5 paid " + atHalf + " and 0.1 paid " + atATenth);
        }

        @Test
        @DisplayName("quota-enabled off pays in full and records nothing")
        void canBeSwitchedOff() {
            setQuota(1);
            support.configs.get(ConfigFile.ECONOMY).set("market.quota-enabled", false);

            MarketService.Receipt receipt = sell(1);

            assertFalse(quota.enabled());
            assertEquals(0, receipt.listed().compareTo(receipt.gross()));
            assertEquals(0, await(quota.status(farmer, now())).used().signum());
        }

        @Test
        @DisplayName("a quota of zero is not a market that pays nothing")
        void zeroQuotaDisablesRatherThanStarves() {
            // A misconfiguration that would otherwise cut every sale on the server to a fifth,
            // silently, and read to an operator as the market being broken.
            setQuota(0);

            assertFalse(quota.enabled());
            assertFalse(sell(1).quotaReduced());
        }
    }

    // ==================================================================================
    // /quota, SPEC 22.3
    // ==================================================================================

    @Nested
    @DisplayName("what /quota reports, SPEC 22.3")
    class Status {

        @Test
        @DisplayName("used, remaining, reset time and the current multiplier")
        void reportsAllFour() {
            setQuota(10_000);
            setMultiplier(0.2);
            sell(1);

            SellQuota.Status status = await(quota.status(farmer, now()));

            assertTrue(status.used().signum() > 0);
            assertEquals(0, status.quota().compareTo(new BigDecimal("10000")));
            assertEquals(0, status.used().add(status.remaining())
                    .compareTo(status.quota()), "used plus remaining is the whole quota");
            assertTrue(status.resetsAt() > now(), "the reset is in the future");
            assertEquals(0, status.multiplier().compareTo(BigDecimal.ONE),
                    "still inside the quota, so the next coin pays full price");
            assertTrue(status.percent() >= 0 && status.percent() <= 100);
        }

        @Test
        @DisplayName("past the cap it reports the reduced rate, not one")
        void reportsTheReducedRate() {
            setQuota(1);
            setMultiplier(0.2);
            sell(1);

            SellQuota.Status status = await(quota.status(farmer, now()));

            assertEquals(0, status.remaining().signum());
            assertEquals(0, status.multiplier().compareTo(new BigDecimal("0.2")),
                    "a player asking why sales pay less must be told the rate");
        }

        @Test
        @DisplayName("a player who has never sold reports a full quota, not an error")
        void unknownPlayerIsFull() {
            setQuota(25_000);
            UUID stranger = UUID.randomUUID();

            SellQuota.Status status = await(quota.status(stranger, now()));

            assertEquals(0, status.used().signum());
            assertEquals(0, status.remaining().compareTo(new BigDecimal("25000")));
        }
    }

    // ==================================================================================
    // Housekeeping
    // ==================================================================================

    @Nested
    @DisplayName("housekeeping")
    class Housekeeping {

        @Test
        @DisplayName("a reset clears one player and leaves everyone else alone")
        void resetIsPerPlayer() {
            setQuota(10_000);
            UUID other = support.givenPlayer("Cato", new BigDecimal("10000.00"), 0L);
            sell(1);
            sellAs(other, 1);

            await(quota.reset(farmer));

            assertEquals(0, await(quota.status(farmer, now())).used().signum());
            assertTrue(await(quota.status(other, now())).used().signum() > 0,
                    "the other player's counter was not collateral");
        }

        @Test
        @DisplayName("pruning drops yesterday's counters and keeps today's")
        void pruneKeepsToday() {
            setQuota(10_000);
            sell(1);

            await(quota.pruneOldPeriods(now()));
            assertTrue(await(quota.status(farmer, now())).used().signum() > 0,
                    "today's counter is not old");

            await(quota.pruneOldPeriods(now() + 86_400_000L));
            assertEquals(0, await(quota.status(farmer, now())).used().signum());
        }

        @Test
        @DisplayName("the server-wide total is the sum of every counter for the day")
        void totalAcrossPlayers() {
            setQuota(10_000);
            UUID other = support.givenPlayer("Cato", new BigDecimal("10000.00"), 0L);
            BigDecimal first = sell(1).quota().used();
            BigDecimal second = sellAs(other, 1).quota().used();

            BigDecimal total = await(support.daos.sellQuota()
                    .totalUsed(quota.periodStart(now())));

            assertEquals(0, total.compareTo(first.add(second)),
                    "expected " + first.add(second) + ", summed " + total);
        }
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private long now() {
        return System.currentTimeMillis();
    }

    private void setQuota(double amount) {
        support.configs.get(ConfigFile.ECONOMY).set("market.daily-sell-quota", amount);
    }

    private void setMultiplier(double multiplier) {
        support.configs.get(ConfigFile.ECONOMY).set("market.over-quota-multiplier", multiplier);
    }

    /** A quota reading UTC, so the boundary assertions do not depend on where this runs. */
    private SellQuota utcQuota() {
        return new SellQuota(support.configs, support.daos.sellQuota(), ZoneId.of("UTC"));
    }

    private MarketService.Receipt sell(int amount) {
        return sellAs(farmer, amount);
    }

    private MarketService.Receipt sellAs(UUID seller, int amount) {
        Result<MarketService.Receipt> result = await(market.sell(seller, ITEM, amount));
        assertTrue(result.isSuccess(), reasonOf(result));
        return result.orElseThrow();
    }

    private MarketService.Receipt sellItem(String material, int amount) {
        Result<MarketService.Receipt> result = await(market.sell(farmer, material, amount));
        assertTrue(result.isSuccess(), reasonOf(result));
        return result.orElseThrow();
    }

    /** Puts the price back where it started, so two arms of a comparison face the same curve. */
    private void resetStock() {
        MarketItem item = market.registry().item(ITEM).orElseThrow();
        int drift = market.registry().stockOf(ITEM) - item.targetStock();
        if (drift != 0) {
            await(market.registry().addStock(ITEM, -drift));
        }
        assertNotEquals(0, market.registry().item(ITEM).orElseThrow().basePrice().signum());
    }

    private BigDecimal totalPaid(MarketService.Receipt receipt) {
        return receipt.net();
    }

    private BigDecimal expected(BigDecimal listed, String multiplier) {
        return dev.civitas.core.economy.Money.floor(listed.multiply(new BigDecimal(multiplier)));
    }

    private static String readSource(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
