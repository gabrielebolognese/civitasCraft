package dev.civitas.core.economy;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRank;
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
 * SPEC 18.2: "Treasury deposit, withdraw, and the 25% cap", plus SPEC 17.6 case 71, the
 * member who drains the treasury and leaves.
 */
class TreasuryServiceTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private TreasuryService treasury;
    private UUID mayor;
    private UUID member;
    private City city;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        treasury = support.treasury;

        // This class covers SPEC 8.5's 25% withdrawal cap. SPEC 21.4 F16 adds a 72-hour hold
        // on a new member's first withdrawal, and every member here joined moments ago, so
        // the hold would refuse each withdrawal before the cap was ever consulted. F16 has
        // its own tests in AntiAbuseTest, including one asserting the cap still applies once
        // the hold has passed, so it is turned off here rather than making these tests wait.
        support.configs.get(dev.civitas.config.ConfigFile.ECONOMY)
                .set("anti-abuse.treasury-withdraw-member-age-hours", 0);

        mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);
        member = support.givenMember(city, "Titus");

        // Citizen holds DEPOSIT and, by default, not WITHDRAW.
        CityRank citizen = city.rankByName("Citizen").orElseThrow();
        assertTrue(await(support.ranks.assign(mayor, city, member, citizen)).isSuccess());

        fundTreasury("100000.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private void fundTreasury(String amount) {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal(amount)));
        city.setTreasury(new BigDecimal(amount));
    }

    private BigDecimal wallet(UUID player) {
        return support.playerRow(player).balance();
    }

    private BigDecimal treasuryNow() {
        return await(support.daos.cities().findById(city.id())).orElseThrow().treasury();
    }

    private void grantWithdraw(UUID player) {
        CityRank rank = support.registry.cityOf(player).orElseThrow()
                .rankOf(player).orElseThrow();
        assertTrue(await(support.ranks.setPermission(mayor, city, rank,
                CityPermission.WITHDRAW, true)).isSuccess());
    }

    // ==================================================================================
    // Deposit
    // ==================================================================================

    @Nested
    @DisplayName("Deposit")
    class Deposit {

        @Test
        @DisplayName("money moves from the wallet to the treasury and is ledgered")
        void depositMoves() {
            BigDecimal before = wallet(member);

            Result<BigDecimal> result =
                    await(treasury.deposit(member, city, new BigDecimal("1000")));

            assertTrue(result.isSuccess(), reasonOf(result));
            assertEquals(0, before.subtract(new BigDecimal("1000")).compareTo(wallet(member)));
            assertEquals(0, new BigDecimal("101000.00").compareTo(treasuryNow()));

            List<LedgerRow> entries = await(support.daos.ledger()
                    .findByType(TransactionType.TREASURY_DEPOSIT.name(), 0L, 10));
            assertEquals(2, entries.size(),
                    "one row for the wallet leaving, one for the treasury arriving");
        }

        @Test
        @DisplayName("a deposit counts toward lifetime contribution, SPEC 13.3")
        void depositCountsAsContribution() {
            await(treasury.deposit(member, city, new BigDecimal("2500")));

            assertEquals(0, new BigDecimal("2500.00").compareTo(
                    await(support.daos.cityMembers().findByUuid(member))
                            .orElseThrow().contributedTotal()));
        }

        @Test
        @DisplayName("a member cannot deposit more than they have")
        void depositBeyondWallet() {
            Result<BigDecimal> result =
                    await(treasury.deposit(member, city, new BigDecimal("999999")));

            assertEquals("INSUFFICIENT_FUNDS", reasonOf(result));
            assertEquals(0, new BigDecimal("100000.00").compareTo(treasuryNow()));
        }

        @Test
        @DisplayName("a member without DEPOSIT cannot")
        void depositNeedsPermission() {
            CityRank citizen = city.rankByName("Citizen").orElseThrow();
            assertTrue(await(support.ranks.setPermission(mayor, city, citizen,
                    CityPermission.DEPOSIT, false)).isSuccess());

            assertEquals("NO_CITY_PERMISSION",
                    reasonOf(await(treasury.deposit(member, city, new BigDecimal("100")))));
        }

        @Test
        @DisplayName("there is no cap on depositing, because contribution should be easy")
        void depositHasNoCap() {
            // SPEC 1.3 wants contribution additive. Only taking is capped.
            assertTrue(await(treasury.deposit(mayor, city, new BigDecimal("40000"))).isSuccess());
            assertEquals(0, new BigDecimal("140000.00").compareTo(treasuryNow()));
        }
    }

    // ==================================================================================
    // Withdraw and the SPEC 8.5 cap
    // ==================================================================================

    @Nested
    @DisplayName("Withdraw")
    class Withdraw {

        @Test
        @DisplayName("the mayor may take the whole treasury, SPEC 8.5")
        void mayorIsUncapped() {
            Result<BigDecimal> result =
                    await(treasury.withdraw(mayor, city, new BigDecimal("100000")));

            assertTrue(result.isSuccess(), reasonOf(result));
            assertEquals(0, BigDecimal.ZERO.compareTo(treasuryNow()));
        }

        @Test
        @DisplayName("a member without WITHDRAW cannot take anything")
        void withdrawNeedsPermission() {
            assertEquals("NO_CITY_PERMISSION",
                    reasonOf(await(treasury.withdraw(member, city, new BigDecimal("100")))));
        }

        @Test
        @DisplayName("SPEC 8.5: a member may take a quarter of the treasury and no more")
        void capIsTwentyFivePercent() {
            grantWithdraw(member);

            assertTrue(await(treasury.withdraw(member, city, new BigDecimal("25000"))).isSuccess(),
                    "exactly 25% of 100,000 is allowed");

            Result<BigDecimal> overCap =
                    await(treasury.withdraw(member, city, new BigDecimal("1")));

            assertEquals("WITHDRAW_CAP", reasonOf(overCap));
        }

        @Test
        @DisplayName("the refusal says how much is left, not just that it was refused")
        void capExplainsItself() {
            grantWithdraw(member);
            await(treasury.withdraw(member, city, new BigDecimal("20000")));

            Result<BigDecimal> refused =
                    await(treasury.withdraw(member, city, new BigDecimal("20000")));

            Result.Failure<BigDecimal> failure = (Result.Failure<BigDecimal>) refused;
            assertEquals("25", failure.placeholders().get("percent"));
            assertEquals("20000.00", failure.placeholders().get("taken"));
            assertTrue(failure.placeholders().containsKey("remaining"));
        }

        @Test
        @DisplayName("SPEC 17.6 case 71: the cap survives being spread over many withdrawals")
        void case71DrainIsCapped() {
            grantWithdraw(member);

            // Ten small bites rather than one big one; the ledger sums them all the same.
            int succeeded = 0;
            for (int attempt = 0; attempt < 10; attempt++) {
                if (await(treasury.withdraw(member, city, new BigDecimal("5000"))).isSuccess()) {
                    succeeded++;
                }
            }

            // The allowance is a quarter of what the treasury holds *now*, so it shrinks as
            // the member takes from it: 25,000 then 23,750 then 22,500 then 21,250, and the
            // fifth bite would put the running total over that. Salami-slicing therefore
            // gets strictly less than one honest request for the cap, which is the right
            // way round.
            assertEquals(4, succeeded, "the allowance shrinks with the treasury it measures");
            assertEquals(0, new BigDecimal("80000.00").compareTo(treasuryNow()),
                    "four fifths of the treasury survives one member's best effort");
        }

        @Test
        @DisplayName("the cap tracks each member separately")
        void capIsPerMember() {
            grantWithdraw(member);
            UUID second = support.givenMember(city, "Marcus");
            CityRank citizen = city.rankByName("Citizen").orElseThrow();
            assertTrue(await(support.ranks.assign(mayor, city, second, citizen)).isSuccess());

            assertTrue(await(treasury.withdraw(member, city, new BigDecimal("25000"))).isSuccess());
            // The treasury is now 75,000, so this member's own quarter is 18,750.
            assertTrue(await(treasury.withdraw(second, city, new BigDecimal("18750"))).isSuccess(),
                    "a second member has their own allowance");
        }

        @Test
        @DisplayName("the cap is a share of the treasury now, not of what it held before")
        void capFollowsTheCurrentTreasury() {
            grantWithdraw(member);
            assertTrue(await(treasury.withdraw(member, city, new BigDecimal("25000"))).isSuccess());

            // A large deposit arrives. Measuring the cap against the opening balance would
            // let this member take another quarter of the bigger figure; SPEC 17.6 case 71
            // is exactly that attack.
            await(treasury.deposit(mayor, city, new BigDecimal("40000")));

            assertEquals("WITHDRAW_CAP",
                    reasonOf(await(treasury.withdraw(member, city, new BigDecimal("20000")))),
                    "already at the allowance, whatever the treasury has grown to");
        }

        @Test
        @DisplayName("remainingAllowance reports what is still available")
        void remainingAllowance() {
            grantWithdraw(member);

            assertEquals(0, new BigDecimal("25000.00")
                    .compareTo(await(treasury.remainingAllowance(member, city))));

            await(treasury.withdraw(member, city, new BigDecimal("10000")));

            // The treasury is now 90,000, so the allowance is 22,500 less the 10,000 taken.
            assertEquals(0, new BigDecimal("12500.00")
                    .compareTo(await(treasury.remainingAllowance(member, city))));

            assertEquals(0, treasuryNow().compareTo(await(treasury.remainingAllowance(mayor, city))),
                    "the mayor's allowance is the whole treasury");
        }

        @Test
        @DisplayName("a treasury that cannot cover the request is refused before the cap")
        void treasuryShortComesFirst() {
            fundTreasury("100.00");
            grantWithdraw(member);

            assertEquals("TREASURY_SHORT",
                    reasonOf(await(treasury.withdraw(member, city, new BigDecimal("500")))));
        }

        @Test
        @DisplayName("money withdrawn actually reaches the wallet")
        void withdrawalReachesTheWallet() {
            grantWithdraw(member);
            BigDecimal before = wallet(member);

            assertTrue(await(treasury.withdraw(member, city, new BigDecimal("5000"))).isSuccess());

            assertEquals(0, before.add(new BigDecimal("5000")).compareTo(wallet(member)));
            assertEquals(0, new BigDecimal("95000.00").compareTo(treasuryNow()));
        }
    }

    // ==================================================================================
    // Shared rules
    // ==================================================================================

    @Test
    @DisplayName("a frozen city moves no money in either direction")
    void frozenCityIsSealed() {
        grantWithdraw(member);
        city.setFrozen(true);

        assertEquals("CITY_FROZEN",
                reasonOf(await(treasury.deposit(member, city, new BigDecimal("100")))));
        assertEquals("CITY_FROZEN",
                reasonOf(await(treasury.withdraw(member, city, new BigDecimal("100")))));
    }

    @Test
    @DisplayName("zero and negative amounts are refused on both sides")
    void amountsMustBePositive() {
        grantWithdraw(member);

        assertEquals("AMOUNT_NOT_POSITIVE",
                reasonOf(await(treasury.deposit(member, city, BigDecimal.ZERO))));
        assertEquals("AMOUNT_NOT_POSITIVE",
                reasonOf(await(treasury.withdraw(member, city, new BigDecimal("-100")))));
    }

    @Test
    @DisplayName("a non-member cannot touch the treasury at all")
    void nonMemberIsRefused() {
        UUID stranger = support.givenEligiblePlayer("Outsider");

        assertEquals("NOT_A_MEMBER",
                reasonOf(await(treasury.deposit(stranger, city, new BigDecimal("100")))));
        assertEquals("NOT_A_MEMBER",
                reasonOf(await(treasury.withdraw(stranger, city, new BigDecimal("100")))));
    }
}
