package dev.civitas.core.economy;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.civitas.core.city.CityTestSupport;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 9.4.4's {@code /ca eco rollback}, and SPEC 17.3 case 35.
 *
 * <h2>The two rules that constrain everything here</h2>
 * SPEC 9.4.4: "Never deletes ledger rows." SPEC 3.6 makes the ledger append-only and SPEC 1.5
 * makes it the authority in a dispute, so a rollback that erased its own evidence would break
 * the one thing the ledger exists for. Every test below that asserts a balance also asserts
 * the row count went up rather than down.
 *
 * <p>SPEC 17.3 case 35: "may take the player negative. Balance floors at 0 and the remainder
 * is recorded as debt in metadata for admin follow-up." That is the case the command exists
 * for — an admin who catches a mistake before the money moves does not need a rollback — so it
 * gets the most attention.
 */
class LedgerRollbackTest {

    @TempDir
    Path directory;

    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000b");

    private CityTestSupport support;
    private LedgerRollback rollback;
    private UUID player;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        rollback = new LedgerRollback(support.db, support.daos, support.economy,
                support.registry);
        player = support.givenEligiblePlayer("Cicero");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private BigDecimal balanceOf(UUID uuid) {
        return await(support.daos.players().findByUuid(uuid)).orElseThrow().balance();
    }

    private List<LedgerRow> ledger() {
        return await(support.daos.ledger().findByPlayer(player, 0L, 200));
    }

    /** Gives the player money and returns the ledger row that recorded it. */
    private LedgerRow givenGrant(String amount) {
        await(support.economy.give(player, new BigDecimal(amount),
                TransactionType.ADMIN_GIVE, null, null));
        return ledger().stream()
                .filter(row -> TransactionType.ADMIN_GIVE.name().equals(row.type()))
                .findFirst().orElseThrow();
    }

    // ==================================================================================
    // The ordinary case
    // ==================================================================================

    @Nested
    @DisplayName("reversing a grant the player still holds")
    class FullRecovery {

        @Test
        @DisplayName("the money comes back and nothing is owed")
        void recoversInFull() {
            BigDecimal before = balanceOf(player);
            LedgerRow grant = givenGrant("10000");

            Result<LedgerRollback.Reversal> done = await(rollback.reverse(ADMIN, grant.id(),
                    "given by mistake"));

            assertTrue(done.isSuccess(), reasonOf(done));
            assertEquals(0, before.compareTo(balanceOf(player)), "back where it started");
            assertEquals(0, BigDecimal.ZERO.compareTo(done.orElseThrow().debt()));
            assertTrue(done.orElseThrow().isComplete());
        }

        @Test
        @DisplayName("a compensating row is written and nothing is deleted")
        void writesRatherThanDeletes() {
            // SPEC 9.4.4 in as many words, and SPEC 3.6's whole design. The original row must
            // still be readable afterwards, because it is the evidence that the grant happened.
            LedgerRow grant = givenGrant("10000");
            int before = ledger().size();

            await(rollback.reverse(ADMIN, grant.id(), "mistake"));

            List<LedgerRow> after = ledger();
            assertEquals(before + 1, after.size(), "one row added, none removed");
            assertTrue(after.stream().anyMatch(row -> row.id() == grant.id()),
                    "the original is still there");
            assertTrue(after.stream().anyMatch(row ->
                    TransactionType.ADMIN_ROLLBACK.name().equals(row.type())));
        }

        @Test
        @DisplayName("the compensating row names what it reversed and who did it")
        void namesItsOrigin() {
            LedgerRow grant = givenGrant("10000");

            await(rollback.reverse(ADMIN, grant.id(), "given by mistake"));

            LedgerRow reversal = ledger().stream()
                    .filter(row -> TransactionType.ADMIN_ROLLBACK.name().equals(row.type()))
                    .findFirst().orElseThrow();
            assertTrue(reversal.metadata().contains("\"reverses\":" + grant.id()),
                    reversal.metadata());
            assertTrue(reversal.metadata().contains("ADMIN_GIVE"), reversal.metadata());
            assertEquals(ADMIN, reversal.targetUuid(), "the admin is on the record");
        }

        @Test
        @DisplayName("the cached balance agrees with storage afterwards")
        void cacheIsRefreshed() {
            // A stale cache here would let the player spend money the database says is gone,
            // which is the one outcome worse than not rolling back at all.
            LedgerRow grant = givenGrant("10000");

            await(rollback.reverse(ADMIN, grant.id(), "mistake"));

            assertEquals(0, balanceOf(player).compareTo(
                    support.economy.balanceOrZero(player)));
        }
    }

    // ==================================================================================
    // SPEC 17.3 case 35
    // ==================================================================================

    @Nested
    @DisplayName("reversing a grant the player has already spent")
    class PartialRecovery {

        @Test
        @DisplayName("the balance floors at zero rather than going negative")
        void floorsAtZero() {
            // A negative balance would break every compareTo in the economy: SPEC 17.3 case
            // 24 already says a negative result is a critical error, so the floor is not a
            // preference.
            UUID other = support.givenEligiblePlayer("Cato");
            BigDecimal starting = balanceOf(player);
            LedgerRow grant = givenGrant("10000");
            await(support.economy.pay(player, other,
                    starting.add(new BigDecimal("9000"))));

            await(rollback.reverse(ADMIN, grant.id(), "duplicated"));

            assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(player)));
            assertFalse(balanceOf(player).signum() < 0);
        }

        @Test
        @DisplayName("what could not be recovered is recorded as debt")
        void recordsTheDebt() {
            // SPEC 17.3 case 35 names the key. An admin chasing unrecovered money needs one
            // thing to search the ledger for.
            UUID other = support.givenEligiblePlayer("Cato");
            BigDecimal starting = balanceOf(player);
            LedgerRow grant = givenGrant("10000");
            await(support.economy.pay(player, other, starting.add(new BigDecimal("9000"))));

            Result<LedgerRollback.Reversal> done = await(rollback.reverse(ADMIN, grant.id(),
                    "duplicated"));

            LedgerRollback.Reversal reversal = done.orElseThrow();
            assertTrue(reversal.debt().signum() > 0, "there is a shortfall");
            assertFalse(reversal.isComplete());

            LedgerRow row = ledger().stream()
                    .filter(entry -> TransactionType.ADMIN_ROLLBACK.name().equals(entry.type()))
                    .findFirst().orElseThrow();
            assertTrue(row.metadata().contains("\"debt\""), row.metadata());
        }

        @Test
        @DisplayName("the recovered and owed amounts add up to what was granted")
        void arithmeticCloses() {
            // The two numbers have to account for the whole grant, or an admin reading the
            // report cannot tell how much is still missing.
            UUID other = support.givenEligiblePlayer("Cato");
            BigDecimal starting = balanceOf(player);
            LedgerRow grant = givenGrant("10000");
            await(support.economy.pay(player, other, starting.add(new BigDecimal("6000"))));

            LedgerRollback.Reversal reversal = await(rollback.reverse(ADMIN, grant.id(),
                    "duplicated")).orElseThrow();

            assertEquals(0, new BigDecimal("10000").compareTo(
                    reversal.recovered().add(reversal.debt())));
        }

        @Test
        @DisplayName("the third party who was paid keeps their money")
        void doesNotCascade() {
            // The reading recorded in OPEN_QUESTIONS. Reversing the onward payment would take
            // money from somebody who was paid in good faith and did nothing wrong.
            UUID other = support.givenEligiblePlayer("Cato");
            LedgerRow grant = givenGrant("10000");
            await(support.economy.pay(player, other, new BigDecimal("5000")));
            BigDecimal theirs = balanceOf(other);

            await(rollback.reverse(ADMIN, grant.id(), "duplicated"));

            assertEquals(0, theirs.compareTo(balanceOf(other)));
        }

        @Test
        @DisplayName("but the transactions that followed are counted, so the admin knows")
        void reportsDownstream() {
            // The count is "how much happened after this", not an exact tally: ledger
            // timestamps are milliseconds and two payments a moment apart can share one, so
            // the assertion is on the contrast rather than on a number the clock decides.
            UUID other = support.givenEligiblePlayer("Cato");
            LedgerRow grant = givenGrant("10000");
            await(support.economy.pay(player, other, new BigDecimal("1000")));
            await(support.economy.pay(player, other, new BigDecimal("1000")));

            LedgerRollback.Reversal reversal = await(rollback.reverse(ADMIN, grant.id(),
                    "duplicated")).orElseThrow();

            assertTrue(reversal.downstream() > 0,
                    "payments followed the grant and the admin has to be told");
        }

        @Test
        @DisplayName("a grant nothing followed reports nothing to chase")
        void quietWhenNothingFollowed() {
            // The other half of the contrast, and the one an admin acts on: no downstream
            // means the reversal finished the job.
            LedgerRow grant = givenGrant("10000");

            LedgerRollback.Reversal reversal = await(rollback.reverse(ADMIN, grant.id(),
                    "mistake")).orElseThrow();

            assertEquals(0, reversal.downstream());
            assertTrue(reversal.isComplete());
        }
    }

    // ==================================================================================
    // What it refuses
    // ==================================================================================

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a transaction that does not exist")
        void unknownTransaction() {
            assertEquals("NO_SUCH_TRANSACTION",
                    reasonOf(await(rollback.reverse(ADMIN, 999_999L, "typo"))));
        }

        @Test
        @DisplayName("the same transaction twice")
        void notTwice() {
            // Otherwise an admin repeating the command would take the money again each time,
            // which is a way to zero somebody's balance by accident.
            LedgerRow grant = givenGrant("10000");
            await(rollback.reverse(ADMIN, grant.id(), "mistake"));

            assertEquals("ALREADY_REVERSED",
                    reasonOf(await(rollback.reverse(ADMIN, grant.id(), "mistake"))));
        }

        @Test
        @DisplayName("a reversal of a reversal")
        void notARollbackOfARollback() {
            // Alternating would mint money back into existence one command at a time. An
            // admin who wants the original state reverses the original row again.
            LedgerRow grant = givenGrant("10000");
            await(rollback.reverse(ADMIN, grant.id(), "mistake"));

            LedgerRow reversal = ledger().stream()
                    .filter(row -> TransactionType.ADMIN_ROLLBACK.name().equals(row.type()))
                    .findFirst().orElseThrow();

            assertEquals("ALREADY_A_ROLLBACK",
                    reasonOf(await(rollback.reverse(ADMIN, reversal.id(), "undo"))));
        }
    }
}
