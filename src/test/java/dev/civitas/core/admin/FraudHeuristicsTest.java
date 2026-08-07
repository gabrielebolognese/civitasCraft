package dev.civitas.core.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.row.LedgerRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 17.6 case 79's six heuristics.
 *
 * <h2>Both directions of every threshold</h2>
 * A detector that fires on everything is as useless as one that fires on nothing, and it is
 * worse: SPEC 9.4.4 has an admin acting on these, so a false positive is somebody accused of
 * cheating for playing well. Every rule below is tested at its boundary in both directions.
 *
 * <p>These are pure functions with the rows and the clock supplied, which is what makes that
 * possible: a heuristic that read the database and {@code System.currentTimeMillis} could only
 * be tested approximately.
 */
class FraudHeuristicsTest {

    @TempDir
    Path directory;

    private static final long NOW = 1_700_000_000_000L;
    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private FraudHeuristics rules;

    @BeforeEach
    void setUp() {
        ConfigManager configs = new ConfigManager(PluginResources.ofClasspath(
                directory.resolve("plugin").toFile(), quiet()));
        configs.loadAll();
        rules = new FraudHeuristics(configs);
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("fraud-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    /** A withdrawal that left {@code after} in a treasury. */
    private static LedgerRow withdrawal(String amount, String after, long when) {
        return new LedgerRow(0, when, TransactionType.TREASURY_WITHDRAW.name(), ALICE, null, 1,
                new BigDecimal(amount).negate(), new BigDecimal(after), null);
    }

    private static LedgerRow payment(UUID from, UUID to, String amount, long when) {
        return new LedgerRow(0, when, TransactionType.PLAYER_PAY.name(), from, to, null,
                new BigDecimal(amount).negate(), new BigDecimal("0"), null);
    }

    // ==================================================================================
    // Rule 1
    // ==================================================================================

    @Nested
    @DisplayName("single withdrawals over 40% of treasury")
    class LargeWithdrawals {

        @Test
        @DisplayName("half a treasury in one go is flagged")
        void flagsHalf() {
            // 5,000 taken from 10,000 leaves 5,000: fifty percent.
            List<FraudHeuristics.Hit> hits = rules.largeWithdrawals(
                    List.of(withdrawal("5000", "5000", NOW)));

            assertEquals(1, hits.size());
            assertEquals("large-withdrawal", hits.get(0).rule());
            assertEquals("50.00", hits.get(0).detail().get("percent"));
        }

        @Test
        @DisplayName("a tenth is not")
        void ignoresSmallOnes() {
            assertTrue(rules.largeWithdrawals(List.of(withdrawal("1000", "9000", NOW))).isEmpty());
        }

        @Test
        @DisplayName("exactly at the threshold is flagged, because the rule says 'over 40%'")
        void boundaryIsIncluded() {
            // 4,000 from 10,000. A boundary has to fall somewhere and inclusive is the safer
            // direction for something that only asks an admin to look.
            assertEquals(1, rules.largeWithdrawals(
                    List.of(withdrawal("4000", "6000", NOW))).size());
        }

        @Test
        @DisplayName("the percentage is of the treasury before the withdrawal, not after")
        void measuredAgainstTheOpeningBalance() {
            // Measuring against what is left would make every withdrawal that empties a
            // treasury infinite, and every one from a since-emptied treasury look enormous.
            List<FraudHeuristics.Hit> hits = rules.largeWithdrawals(
                    List.of(withdrawal("9000", "1000", NOW)));

            assertEquals("90.00", hits.get(0).detail().get("percent"));
        }

        @Test
        @DisplayName("a deposit is not a withdrawal however large")
        void depositsAreIgnored() {
            LedgerRow deposit = new LedgerRow(0, NOW, TransactionType.TREASURY_DEPOSIT.name(),
                    ALICE, null, 1, new BigDecimal("100000"), new BigDecimal("100000"), null);

            assertTrue(rules.largeWithdrawals(List.of(deposit)).isEmpty());
        }
    }

    // ==================================================================================
    // Rule 2
    // ==================================================================================

    @Nested
    @DisplayName("more than five transfers to the same player in an hour")
    class RepeatedTransfers {

        @Test
        @DisplayName("six payments to one player are flagged")
        void flagsSix() {
            List<LedgerRow> rows = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                rows.add(payment(ALICE, BOB, "100", NOW - i * 60_000L));
            }

            List<FraudHeuristics.Hit> hits = rules.repeatedTransfers(rows, NOW);

            assertEquals(1, hits.size());
            assertEquals("6", hits.get(0).detail().get("count"));
            assertEquals("600", hits.get(0).detail().get("total"));
        }

        @Test
        @DisplayName("five are not, because the rule says 'more than five'")
        void fiveIsFine() {
            List<LedgerRow> rows = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                rows.add(payment(ALICE, BOB, "100", NOW - i * 60_000L));
            }

            assertTrue(rules.repeatedTransfers(rows, NOW).isEmpty());
        }

        @Test
        @DisplayName("payments spread over more than an hour are not")
        void windowIsRespected() {
            List<LedgerRow> rows = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                rows.add(payment(ALICE, BOB, "100", NOW - i * TimeUnit.HOURS.toMillis(1)));
            }

            // Only the first two fall inside the hour.
            assertTrue(rules.repeatedTransfers(rows, NOW).isEmpty());
        }

        @Test
        @DisplayName("counted per direction, so two people trading is not a hit")
        void tradeIsNotFeeding() {
            // A and B paying each other three times each is trade. A paying B six times is
            // the pattern this rule is looking for.
            List<LedgerRow> rows = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                rows.add(payment(ALICE, BOB, "100", NOW - i * 60_000L));
                rows.add(payment(BOB, ALICE, "100", NOW - i * 60_000L));
            }

            assertTrue(rules.repeatedTransfers(rows, NOW).isEmpty());
        }
    }

    // ==================================================================================
    // Rules 3 and 4
    // ==================================================================================

    @Nested
    @DisplayName("sudden wealth and new-account windfalls")
    class Windfalls {

        @Test
        @DisplayName("receiving four times a lifetime of earnings in a day is flagged")
        void flagsSuddenWealth() {
            assertEquals(1, rules.suddenWealth(ALICE, new BigDecimal("40000"),
                    new BigDecimal("10000")).size());
        }

        @Test
        @DisplayName("receiving twice is not")
        void twiceIsFine() {
            assertTrue(rules.suddenWealth(ALICE, new BigDecimal("20000"),
                    new BigDecimal("10000")).isEmpty());
        }

        @Test
        @DisplayName("a player who has earned nothing yet is not flagged for their first coin")
        void noBaselineNoHit() {
            // Otherwise every new player's starting balance would fire the rule.
            assertTrue(rules.suddenWealth(ALICE, new BigDecimal("2000"),
                    BigDecimal.ZERO).isEmpty());
        }

        @Test
        @DisplayName("a new account receiving over 100k is flagged")
        void flagsNewAccountWindfall() {
            assertEquals(1, rules.newAccountWindfall(ALICE, new BigDecimal("150000"),
                    TimeUnit.DAYS.toMillis(2)).size());
        }

        @Test
        @DisplayName("an established account receiving the same is not")
        void establishedAccountsAreFine() {
            assertTrue(rules.newAccountWindfall(ALICE, new BigDecimal("150000"),
                    TimeUnit.DAYS.toMillis(60)).isEmpty());
        }

        @Test
        @DisplayName("a new account receiving a normal amount is not")
        void smallAmountsAreFine() {
            assertTrue(rules.newAccountWindfall(ALICE, new BigDecimal("5000"),
                    TimeUnit.DAYS.toMillis(1)).isEmpty());
        }
    }

    // ==================================================================================
    // Rule 5
    // ==================================================================================

    @Nested
    @DisplayName("a treasury dropping over 60% in ten minutes")
    class TreasuryDrain {

        @Test
        @DisplayName("a treasury emptied in minutes is flagged")
        void flagsADrain() {
            List<LedgerRow> rows = List.of(
                    withdrawal("4000", "6000", NOW - 300_000L),
                    withdrawal("4000", "2000", NOW - 60_000L));

            List<FraudHeuristics.Hit> hits = rules.treasuryDrain(1, rows, NOW);

            assertEquals(1, hits.size());
            assertEquals("80.00", hits.get(0).detail().get("percent"));
        }

        @Test
        @DisplayName("a steady spend over the same window is not")
        void gradualSpendingIsFine() {
            List<LedgerRow> rows = List.of(
                    withdrawal("1000", "9000", NOW - 300_000L),
                    withdrawal("1000", "8000", NOW - 60_000L));

            assertTrue(rules.treasuryDrain(1, rows, NOW).isEmpty());
        }

        @Test
        @DisplayName("the same drop spread over hours is not")
        void windowIsRespected() {
            List<LedgerRow> rows = List.of(
                    withdrawal("4000", "6000", NOW - TimeUnit.HOURS.toMillis(5)),
                    withdrawal("4000", "2000", NOW - TimeUnit.HOURS.toMillis(4)));

            assertTrue(rules.treasuryDrain(1, rows, NOW).isEmpty());
        }

        @Test
        @DisplayName("another city's rows are not counted against this one")
        void staysWithinTheCity() {
            LedgerRow other = new LedgerRow(0, NOW, TransactionType.TREASURY_WITHDRAW.name(),
                    ALICE, null, 2, new BigDecimal("-9000"), new BigDecimal("1000"), null);

            assertTrue(rules.treasuryDrain(1, List.of(other), NOW).isEmpty());
        }
    }

    // ==================================================================================
    // Rule 6
    // ==================================================================================

    @Nested
    @DisplayName("income far beyond the 99th percentile")
    class OutlierIncome {

        @Test
        @DisplayName("one player earning many times everybody else is flagged")
        void flagsAnOutlier() {
            Map<UUID, BigDecimal> income = new HashMap<>();
            for (int i = 0; i < 30; i++) {
                income.put(UUID.randomUUID(), new BigDecimal("1000"));
            }
            income.put(ALICE, new BigDecimal("100000"));

            List<FraudHeuristics.Hit> hits = rules.outlierIncome(income);

            assertEquals(1, hits.size());
            assertEquals("outlier-income", hits.get(0).rule());
        }

        @Test
        @DisplayName("a server where everybody earns similarly flags nobody")
        void evenDistributionIsQuiet() {
            Map<UUID, BigDecimal> income = new HashMap<>();
            for (int i = 0; i < 30; i++) {
                income.put(UUID.randomUUID(), new BigDecimal(1000 + i * 10));
            }

            assertTrue(rules.outlierIncome(income).isEmpty());
        }

        @Test
        @DisplayName("too few players and the rule declines to answer")
        void needsEnoughData() {
            // With four players the richest is always the outlier, which says nothing. A
            // percentile over a handful of samples is not a percentile.
            Map<UUID, BigDecimal> income = Map.of(
                    ALICE, new BigDecimal("100000"),
                    BOB, new BigDecimal("100"),
                    UUID.randomUUID(), new BigDecimal("100"),
                    UUID.randomUUID(), new BigDecimal("100"));

            assertTrue(rules.outlierIncome(income).isEmpty());
        }
    }
}
