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
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.CityTestSupport;
import dev.civitas.storage.dao.BountyDao;
import dev.civitas.storage.row.BountyRow;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 4.7's bounties.
 *
 * <h2>The clause that does the work</h2>
 * A bounty is "claimed by whoever kills the target <b>during an active war</b>", and SPEC 4.7
 * explains itself in the same sentence: "so bounties cannot be used to fund random murder
 * outside of the sanctioned combat window". Every test about claiming is really a test of that
 * one condition, because without it a bounty is a standing contract to hunt somebody.
 *
 * <p>The other half is the escrow. SPEC 4.7 takes the money the moment a bounty is placed, so a
 * bounty can never be a promise the server cannot keep, and SPEC 1.5 requires the whole round
 * trip to be visible in the ledger — placed, then either claimed or refunded, never vanished.
 */
class BountyServiceTest {

    @TempDir
    Path directory;

    private static final long NOW = System.currentTimeMillis();

    private CityTestSupport support;
    private BountyService bounties;
    private UUID placer;
    private UUID target;
    private UUID hunter;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        bounties = new BountyService(support.db, support.daos.bounties(), support.economy,
                support.configs, Scheduler.direct(), quiet());

        placer = support.givenEligiblePlayer("Cicero");
        target = support.givenEligiblePlayer("Catilina");
        hunter = support.givenEligiblePlayer("Antonius");
        fund(placer, "100000.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("civitas-bounty-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private void fund(UUID player, String amount) {
        await(support.economy.give(player, new BigDecimal(amount),
                TransactionType.ADMIN_GIVE, null, null));
    }

    private BigDecimal balanceOf(UUID player) {
        return await(support.daos.players().findByUuid(player)).orElseThrow().balance();
    }

    private List<LedgerRow> ledgerOf(UUID player) {
        return await(support.daos.ledger().findByPlayer(player, 0L, 100));
    }

    // ==================================================================================
    // Placing
    // ==================================================================================

    @Nested
    @DisplayName("placing a bounty")
    class Placing {

        @Test
        @DisplayName("the money leaves at once, and the row records that it did")
        void escrowIsImmediate() {
            BigDecimal before = balanceOf(placer);

            Result<BountyRow> placed = await(bounties.place(placer, target,
                    new BigDecimal("5000"), NOW));

            assertTrue(placed.isSuccess(), reasonOf(placed));
            assertEquals(0, before.subtract(new BigDecimal("5000"))
                    .compareTo(balanceOf(placer)), "SPEC 4.7 escrows immediately");
            assertEquals(BountyDao.OPEN, placed.orElseThrow().state());
        }

        @Test
        @DisplayName("the escrow is written to the ledger, so the money is traceable")
        void ledgerRecordsTheEscrow() {
            await(bounties.place(placer, target, new BigDecimal("5000"), NOW));

            assertTrue(ledgerOf(placer).stream().anyMatch(row ->
                            TransactionType.BOUNTY_PLACE.name().equals(row.type())),
                    "SPEC 1.5: no coin moves without a ledger row");
        }

        @Test
        @DisplayName("a bounty below the SPEC 4.7 minimum is refused")
        void minimumIsEnforced() {
            assertEquals("BELOW_MINIMUM",
                    reasonOf(await(bounties.place(placer, target, new BigDecimal("999"), NOW))));
            assertEquals(0, new BigDecimal("1000").compareTo(bounties.minimumAmount()));
        }

        @Test
        @DisplayName("you cannot put a price on your own head")
        void noSelfBounty() {
            // Otherwise a pair of accounts could launder money through a staged kill.
            assertEquals("SELF_BOUNTY",
                    reasonOf(await(bounties.place(placer, placer, new BigDecimal("5000"), NOW))));
        }

        @Test
        @DisplayName("a placer who cannot afford it gets no bounty and keeps their money")
        void insufficientFundsPlacesNothing() {
            UUID pauper = support.givenEligiblePlayer("Pauper");
            BigDecimal beyondReach = balanceOf(pauper).add(new BigDecimal("1000000"));

            Result<BountyRow> refused = await(bounties.place(pauper, target, beyondReach, NOW));

            assertEquals("INSUFFICIENT_FUNDS", reasonOf(refused));
            assertTrue(await(support.daos.bounties().findOpenOn(target)).isEmpty(),
                    "the transaction rolled back, so no row survives either");
        }

        @Test
        @DisplayName("several people may hunt the same target")
        void bountiesStack() {
            UUID second = support.givenEligiblePlayer("Cato");
            fund(second, "100000.00");

            await(bounties.place(placer, target, new BigDecimal("5000"), NOW));
            await(bounties.place(second, target, new BigDecimal("3000"), NOW));

            assertEquals(0, new BigDecimal("8000").compareTo(await(bounties.totalOn(target))));
        }
    }

    // ==================================================================================
    // Claiming, SPEC 4.7's war restriction
    // ==================================================================================

    @Nested
    @DisplayName("claiming a bounty")
    class Claiming {

        @Test
        @DisplayName("a kill outside a war pays nothing and leaves the bounty open")
        void notInWarPaysNothing() {
            // The whole point of SPEC 4.7's restriction. A bounty that paid out on any kill
            // would be a contract to hunt somebody wherever they went.
            await(bounties.place(placer, target, new BigDecimal("5000"), NOW));
            BigDecimal before = balanceOf(hunter);

            Result<BigDecimal> refused = await(bounties.claim(hunter, target, false, NOW));

            assertEquals("NOT_IN_WAR", reasonOf(refused));
            assertEquals(0, before.compareTo(balanceOf(hunter)));
            assertEquals(1, await(support.daos.bounties().findOpenOn(target)).size(),
                    "and it is still there to be claimed properly");
        }

        @Test
        @DisplayName("a kill during a war pays the killer")
        void warKillPays() {
            await(bounties.place(placer, target, new BigDecimal("5000"), NOW));
            BigDecimal before = balanceOf(hunter);

            Result<BigDecimal> paid = await(bounties.claim(hunter, target, true, NOW));

            assertTrue(paid.isSuccess(), reasonOf(paid));
            assertEquals(0, new BigDecimal("5000").compareTo(paid.orElseThrow()));
            assertEquals(0, before.add(new BigDecimal("5000")).compareTo(balanceOf(hunter)));
        }

        @Test
        @DisplayName("one kill collects every bounty on that head")
        void allBountiesAtOnce() {
            UUID second = support.givenEligiblePlayer("Cato");
            fund(second, "100000.00");
            await(bounties.place(placer, target, new BigDecimal("5000"), NOW));
            await(bounties.place(second, target, new BigDecimal("3000"), NOW));

            Result<BigDecimal> paid = await(bounties.claim(hunter, target, true, NOW));

            assertEquals(0, new BigDecimal("8000").compareTo(paid.orElseThrow()));
            assertTrue(await(support.daos.bounties().findOpenOn(target)).isEmpty());
        }

        @Test
        @DisplayName("a bounty cannot be collected twice")
        void claimedOnce() {
            await(bounties.place(placer, target, new BigDecimal("5000"), NOW));
            await(bounties.claim(hunter, target, true, NOW));

            assertEquals("NO_BOUNTY", reasonOf(await(bounties.claim(hunter, target, true, NOW))));
        }

        @Test
        @DisplayName("killing somebody with no bounty is not an error worth money")
        void nothingOnTheirHead() {
            assertEquals("NO_BOUNTY", reasonOf(await(bounties.claim(hunter, target, true, NOW))));
        }

        @Test
        @DisplayName("the payout is written to the ledger")
        void ledgerRecordsThePayout() {
            await(bounties.place(placer, target, new BigDecimal("5000"), NOW));
            await(bounties.claim(hunter, target, true, NOW));

            assertTrue(ledgerOf(hunter).stream().anyMatch(row ->
                    TransactionType.BOUNTY_CLAIM.name().equals(row.type())));
        }

        @Test
        @DisplayName("a settled bounty keeps its row rather than being deleted")
        void rowsSurviveSettlement() {
            // SPEC 1.5 makes money auditable. A deleted row is money the ledger says moved to
            // somewhere that no longer exists.
            Result<BountyRow> placed = await(bounties.place(placer, target,
                    new BigDecimal("5000"), NOW));
            await(bounties.claim(hunter, target, true, NOW));

            BountyRow settled = await(support.daos.bounties().findAllOpen(50)).stream()
                    .filter(row -> row.id() == placed.orElseThrow().id())
                    .findFirst().orElse(null);
            assertFalse(settled != null, "it is no longer open");

            assertEquals(1, await(support.daos.bounties().count()).intValue(),
                    "but the row is still there");
        }
    }

    // ==================================================================================
    // Expiry, SPEC 4.7
    // ==================================================================================

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        @DisplayName("after thirty days it refunds the placer")
        void expiredRefunds() {
            await(bounties.place(placer, target, new BigDecimal("5000"), NOW));
            BigDecimal afterEscrow = balanceOf(placer);
            long later = NOW + TimeUnit.DAYS.toMillis(31);

            assertEquals(1, await(bounties.expireDue(later)).intValue());

            assertEquals(0, afterEscrow.add(new BigDecimal("5000")).compareTo(balanceOf(placer)));
            assertTrue(await(support.daos.bounties().findOpenOn(target)).isEmpty());
        }

        @Test
        @DisplayName("a bounty inside its window is left alone")
        void unexpiredSurvives() {
            await(bounties.place(placer, target, new BigDecimal("5000"), NOW));

            assertEquals(0, await(bounties.expireDue(NOW + TimeUnit.DAYS.toMillis(29))).intValue());
            assertEquals(1, await(support.daos.bounties().findOpenOn(target)).size());
        }

        @Test
        @DisplayName("the refund is a ledger entry of its own")
        void refundIsLedgered() {
            await(bounties.place(placer, target, new BigDecimal("5000"), NOW));
            await(bounties.expireDue(NOW + TimeUnit.DAYS.toMillis(31)));

            assertTrue(ledgerOf(placer).stream().anyMatch(row ->
                    TransactionType.BOUNTY_REFUND.name().equals(row.type())));
        }

        @Test
        @DisplayName("sweeping twice refunds once")
        void sweepIsIdempotent() {
            // The state is part of the WHERE clause, so a second sweep matches nothing. A
            // sweep that paid twice would mint money on every restart.
            await(bounties.place(placer, target, new BigDecimal("5000"), NOW));
            long later = NOW + TimeUnit.DAYS.toMillis(31);

            await(bounties.expireDue(later));
            BigDecimal afterFirst = balanceOf(placer);

            assertEquals(0, await(bounties.expireDue(later)).intValue());
            assertEquals(0, afterFirst.compareTo(balanceOf(placer)));
        }

        @Test
        @DisplayName("an expired bounty cannot then be claimed")
        void expiredIsGone() {
            await(bounties.place(placer, target, new BigDecimal("5000"), NOW));
            await(bounties.expireDue(NOW + TimeUnit.DAYS.toMillis(31)));

            assertEquals("NO_BOUNTY", reasonOf(await(bounties.claim(hunter, target, true, NOW))));
        }
    }

    @Test
    @DisplayName("the arithmetic closes: what was staked is what came back")
    void moneyIsConserved() {
        // A bounty must neither mint nor destroy coins. Placed, claimed, and the total across
        // the three wallets is what it was before.
        BigDecimal total = balanceOf(placer).add(balanceOf(target)).add(balanceOf(hunter));

        await(bounties.place(placer, target, new BigDecimal("7500"), NOW));
        await(bounties.claim(hunter, target, true, NOW));

        assertEquals(0, total.compareTo(
                balanceOf(placer).add(balanceOf(target)).add(balanceOf(hunter))));
    }
}
