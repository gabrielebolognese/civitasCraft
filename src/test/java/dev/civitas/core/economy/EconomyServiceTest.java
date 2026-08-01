package dev.civitas.core.economy;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Personal balances and transfers: SPEC 17.3 cases 24, 25, 27 and 34.
 */
class EconomyServiceTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private EconomyService economy;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        economy = support.economy;

        alice = support.givenPlayer("Alice", new BigDecimal("10000.00"), 0L);
        bob = support.givenPlayer("Bob", new BigDecimal("500.00"), 0L);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private BigDecimal stored(UUID player) {
        return support.playerRow(player).balance();
    }

    // ==================================================================================
    // The cache, SPEC 2.3
    // ==================================================================================

    @Nested
    @DisplayName("Balance cache")
    class Cache {

        @Test
        @DisplayName("the cache and the database agree after every movement")
        void cacheFollowsTheDatabase() {
            await(economy.pay(alice, bob, new BigDecimal("250")));

            assertEquals(0, new BigDecimal("9750.00").compareTo(economy.balanceOrZero(alice)));
            assertEquals(0, stored(alice).compareTo(economy.balanceOrZero(alice)));
            assertEquals(0, stored(bob).compareTo(economy.balanceOrZero(bob)));
        }

        @Test
        @DisplayName("loadAll rebuilds the cache from the database")
        void loadAllRebuilds() {
            assertEquals(2, await(economy.loadAll()));
            assertEquals(0, new BigDecimal("10000.00").compareTo(economy.balanceOrZero(alice)));
        }

        @Test
        @DisplayName("an unknown player reads as zero, and is distinguishable from a real zero")
        void unknownPlayer() {
            UUID ghost = UUID.randomUUID();

            assertEquals(0, BigDecimal.ZERO.compareTo(economy.balanceOrZero(ghost)));
            assertTrue(economy.cachedBalance(ghost).isEmpty());
            assertTrue(economy.cachedBalance(alice).isPresent());
        }

        @Test
        @DisplayName("a player with no record cannot be paid into existence")
        void noRecordIsRefused() {
            assertEquals("NO_PLAYER_RECORD",
                    reasonOf(await(economy.pay(alice, UUID.randomUUID(), BigDecimal.ONE))));
        }
    }

    // ==================================================================================
    // SPEC 9.1 /pay
    // ==================================================================================

    @Nested
    @DisplayName("Transfers")
    class Transfers {

        @Test
        @DisplayName("money moves whole: out of one wallet and into the other")
        void payMoves() {
            Result<BigDecimal> result = await(economy.pay(alice, bob, new BigDecimal("1000")));

            assertTrue(result.isSuccess(), reasonOf(result));
            assertEquals(0, new BigDecimal("9000.00").compareTo(stored(alice)));
            assertEquals(0, new BigDecimal("1500.00").compareTo(stored(bob)));
        }

        @Test
        @DisplayName("SPEC 17.3 case 25: paying yourself is refused")
        void paySelf() {
            assertEquals("PAY_SELF", reasonOf(await(economy.pay(alice, alice, BigDecimal.TEN))));
            assertEquals(0, new BigDecimal("10000.00").compareTo(stored(alice)),
                    "and nothing moved");
        }

        @Test
        @DisplayName("a transfer beyond the sender's balance moves nothing at all")
        void payBeyondBalance() {
            Result<BigDecimal> result = await(economy.pay(bob, alice, new BigDecimal("501")));

            assertEquals("INSUFFICIENT_FUNDS", reasonOf(result));
            assertEquals(0, new BigDecimal("500.00").compareTo(stored(bob)));
            assertEquals(0, new BigDecimal("10000.00").compareTo(stored(alice)));
        }

        @Test
        @DisplayName("a refused credit rolls the debit back, so money is never destroyed")
        void failedCreditRollsBack() {
            // SPEC 17.3 case 27: the credit breaches the ceiling. Both halves are one
            // transaction, so the sender must not be left short.
            support.configs.get(ConfigFile.ECONOMY).set("max-balance", "600");

            Result<BigDecimal> result = await(economy.pay(alice, bob, new BigDecimal("200")));

            assertEquals("MAX_BALANCE", reasonOf(result));
            assertEquals(0, new BigDecimal("10000.00").compareTo(stored(alice)),
                    "the sender keeps their money");
            assertEquals(0, new BigDecimal("500.00").compareTo(stored(bob)));
        }

        @Test
        @DisplayName("both sides of a transfer are ledgered")
        void payIsLedgered() {
            await(economy.pay(alice, bob, new BigDecimal("300")));

            List<LedgerRow> rows = await(support.daos.ledger()
                    .findByType(TransactionType.PLAYER_PAY.name(), 0L, 10));

            assertEquals(2, rows.size());
            assertTrue(rows.stream().anyMatch(row -> row.amount().signum() < 0));
            assertTrue(rows.stream().anyMatch(row -> row.amount().signum() > 0));
            assertTrue(rows.stream().allMatch(row -> row.metadata() != null),
                    "each side records who the other party was");
        }

        @Test
        @DisplayName("SPEC 17.3 case 34: a transfer does not depend on either player being online")
        void payIsOffline() {
            // Nothing in the path touches Bukkit; the service only ever sees UUIDs. If this
            // test compiles and passes with no server at all, the property holds.
            assertTrue(await(economy.pay(alice, bob, new BigDecimal("100"))).isSuccess());
        }

        @Test
        @DisplayName("amounts are floored before they move, SPEC 17.3 case 26")
        void payFloors() {
            await(economy.pay(alice, bob, new BigDecimal("100.999")));

            assertEquals(0, new BigDecimal("9899.01").compareTo(stored(alice)));
            assertEquals(0, new BigDecimal("600.99").compareTo(stored(bob)));
        }

        @Test
        @DisplayName("zero and negative transfers are refused")
        void payMustBePositive() {
            assertEquals("AMOUNT_NOT_POSITIVE",
                    reasonOf(await(economy.pay(alice, bob, BigDecimal.ZERO))));
            assertEquals("AMOUNT_NOT_POSITIVE",
                    reasonOf(await(economy.pay(alice, bob, new BigDecimal("-100")))));
        }
    }

    // ==================================================================================
    // SPEC 17.3 case 24: negative balances
    // ==================================================================================

    @Nested
    @DisplayName("Never negative")
    class NeverNegative {

        @Test
        @DisplayName("SPEC 17.3 case 24: concurrent debits cannot overdraw a wallet")
        void concurrentDebits() throws Exception {
            // Twenty threads each try to take 100 from a wallet holding 500. Without the
            // per-player lock, several would read the same balance and all pass the check.
            int attempts = 20;
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger succeeded = new AtomicInteger();

            var pool = Executors.newFixedThreadPool(8);
            try {
                List<CompletableFuture<Void>> runs = java.util.stream.IntStream.range(0, attempts)
                        .mapToObj(i -> CompletableFuture.runAsync(() -> {
                            try {
                                start.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            if (await(economy.take(bob, new BigDecimal("100"),
                                    TransactionType.ADMIN_TAKE, null, null)).isSuccess()) {
                                succeeded.incrementAndGet();
                            }
                        }, pool))
                        .toList();

                start.countDown();
                CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new))
                        .get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertEquals(5, succeeded.get(), "500 divided by 100 is five, however many threads try");
            assertEquals(0, BigDecimal.ZERO.compareTo(stored(bob)));
            assertFalse(stored(bob).signum() < 0, "a wallet must never go negative");
        }

        @Test
        @DisplayName("a balance already corrupt cannot be made worse")
        void corruptBalanceCannotBeDebited() {
            // If a balance somehow went negative outside the service, the next debit must
            // refuse rather than dig deeper. The insufficient-funds check catches it first,
            // which is why requireNonNegative below it is a backstop rather than the guard.
            Result<BigDecimal> result = await(support.db.transaction(connection -> {
                support.daos.players().updateBalance(connection, bob, new BigDecimal("-1.00"));
                return economy.withdraw(connection, bob, BigDecimal.ONE,
                        TransactionType.ADMIN_TAKE, null, null);
            }));

            assertEquals("INSUFFICIENT_FUNDS", reasonOf(result));
            assertEquals(0, new BigDecimal("500.00").compareTo(stored(bob)),
                    "and the failure rolled the corrupting write back with it");
        }
    }

    // ==================================================================================
    // SPEC 17.3 case 27: the ceiling
    // ==================================================================================

    @Nested
    @DisplayName("The balance ceiling")
    class Ceiling {

        @Test
        @DisplayName("SPEC 17.3 case 27: a credit past the ceiling is refused")
        void creditPastCeiling() {
            support.configs.get(ConfigFile.ECONOMY).set("max-balance", "10500");

            assertTrue(await(economy.give(alice, new BigDecimal("500"),
                    TransactionType.ADMIN_GIVE, null, null)).isSuccess(), "exactly the limit");

            assertEquals("MAX_BALANCE", reasonOf(await(economy.give(alice, BigDecimal.ONE,
                    TransactionType.ADMIN_GIVE, null, null))));
            assertEquals(0, new BigDecimal("10500.00").compareTo(stored(alice)));
        }
    }

    // ==================================================================================
    // Freezing, SPEC 9.4.4
    // ==================================================================================

    @Test
    @DisplayName("a frozen player can neither send nor receive")
    void frozenPlayer() {
        PlayerRow row = support.playerRow(alice);
        await(support.daos.players().update(new PlayerRow(row.uuid(), row.lastKnownName(),
                row.balance(), row.cityId(), row.rankId(), row.firstJoin(), row.lastSeen(),
                row.totalPlaytimeMs(), row.activePlaytimeMs(), row.dailyStreak(),
                row.lastDailyClaim(), row.newcomerUntil(), true,
                row.lastCityLeave(), row.lastCityDisband())));

        assertEquals("FROZEN", reasonOf(await(economy.pay(alice, bob, BigDecimal.TEN))));
        assertEquals("FROZEN", reasonOf(await(economy.pay(bob, alice, BigDecimal.TEN))));
        assertEquals(0, new BigDecimal("500.00").compareTo(stored(bob)),
                "the sender's side rolled back with the frozen recipient's");
    }

    @Test
    @DisplayName("circulation is the sum of every wallet, SPEC 4.8")
    void circulation() {
        assertEquals(0, new BigDecimal("10500.00").compareTo(await(economy.totalInWallets())));
    }
}
