package dev.civitas.core.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.city.CityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 18.1: "Payout math for win, loss, draw, decline, and ally splits."
 *
 * <p>One of only two tests SPEC 18.1 assigns to M19, and the more important of the pair,
 * because this is where a war moves real money. The property every test here really guards is
 * that the arithmetic closes: whatever the split, the coins that come out equal the coins that
 * went in, so a war can neither mint money nor lose it somewhere unaccounted.
 */
class WarPayoutsTest {

    @TempDir
    Path directory;

    private static final BigDecimal WAGER = new BigDecimal("50000.00");

    private ConfigManager configs;
    private WarPayouts payouts;

    @BeforeEach
    void setUp() {
        configs = new ConfigManager(PluginResources.ofClasspath(
                directory.resolve("plugin").toFile(), CityTestSupport.quietLogger()));
        configs.loadAll();
        payouts = new WarPayouts(configs);
    }

    /** The pot: both sides staked the wager, so twice it. */
    private static BigDecimal pot() {
        return WAGER.multiply(BigDecimal.valueOf(2));
    }

    // ==================================================================================
    // The balance property
    // ==================================================================================

    @Nested
    @DisplayName("the money always balances")
    class Balance {

        @Test
        @DisplayName("a decided war distributes exactly the two wagers")
        void decidedBalances() {
            WarPayouts.Split split = payouts.decided(WAGER);

            assertEquals(0, pot().compareTo(split.total()),
                    "a war must neither create nor lose coins: staked " + pot()
                            + " but distributed " + split.total());
        }

        @Test
        @DisplayName("a draw returns both wagers untouched")
        void drawBalances() {
            WarPayouts.Split split = payouts.drawn(WAGER);

            assertEquals(0, pot().compareTo(split.total()));
            assertEquals(0, WAGER.compareTo(split.winnerReturn()));
            assertEquals(0, WAGER.compareTo(split.loserReturn()));
            assertEquals(0, BigDecimal.ZERO.compareTo(split.burned()),
                    "SPEC 11.9 burns nothing on a draw");
        }

        @Test
        @DisplayName("an admin cancellation returns both wagers in full")
        void cancelledBalances() {
            WarPayouts.Split split = payouts.cancelled(WAGER);

            assertEquals(0, WAGER.compareTo(split.winnerReturn()));
            assertEquals(0, WAGER.compareTo(split.loserReturn()));
            assertEquals(0, BigDecimal.ZERO.compareTo(split.burned()));
        }

        @Test
        @DisplayName("a decline balances too")
        void declineBalances() {
            WarPayouts.Split split = payouts.declined(WAGER);

            assertEquals(0, pot().compareTo(split.total()));
        }
    }

    // ==================================================================================
    // SPEC 11.9's table
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 11.9 payouts")
    class Table {

        @Test
        @DisplayName("the winner gets their stake back plus 80% of the loser's")
        void winnerShare() {
            WarPayouts.Split split = payouts.decided(WAGER);

            // 50,000 back plus 80% of 50,000.
            assertEquals(0, new BigDecimal("90000.00").compareTo(split.winnerReturn()));
        }

        @Test
        @DisplayName("with the shipped numbers the remaining 20% is burned, not refunded")
        void burnTakesPrecedence() {
            // SPEC 11.9 says both that the loser "receives 20% of their own wager back" and
            // that "the remaining 20% of the loser's wager is deleted from circulation". Those
            // are the same 20% and it cannot be both. Resolved in favour of the later
            // statement, which is the one that makes SPEC's economic-sink sentence true.
            WarPayouts.Split split = payouts.decided(WAGER);

            assertEquals(0, new BigDecimal("10000.00").compareTo(split.burned()),
                    "20% of the loser's 50,000 stake should be destroyed");
            assertEquals(0, BigDecimal.ZERO.compareTo(split.loserReturn()),
                    "which leaves the loser nothing, per the same sentence");
        }

        @Test
        @DisplayName("an operator who turns the burn off gets the refund instead")
        void refundWhenBurnIsOff() {
            // Both config keys stay meaningful, so a server that prefers SPEC's table row can
            // have it without a code change.
            configs.get(ConfigFile.WAR).set("rewards.burn-percent", 0);

            WarPayouts.Split split = payouts.decided(WAGER);

            assertEquals(0, new BigDecimal("10000.00").compareTo(split.loserReturn()));
            assertEquals(0, BigDecimal.ZERO.compareTo(split.burned()));
            assertEquals(0, pot().compareTo(split.total()), "and it still balances");
        }

        @Test
        @DisplayName("a winner share of 100% leaves nothing to burn or refund")
        void extremeShareStillBalances() {
            configs.get(ConfigFile.WAR).set("rewards.winner-wager-share-percent", 100);

            WarPayouts.Split split = payouts.decided(WAGER);

            assertEquals(0, pot().compareTo(split.total()));
            assertEquals(0, BigDecimal.ZERO.compareTo(split.loserReturn()));
            assertEquals(0, BigDecimal.ZERO.compareTo(split.burned()));
        }

        @Test
        @DisplayName("a decline costs the defender 30% and pays it to the attacker")
        void declinePenalty() {
            // SPEC 11.3. The exit that means nobody is forced to fight.
            WarPayouts.Split split = payouts.declined(WAGER);

            assertEquals(0, new BigDecimal("65000.00").compareTo(split.winnerReturn()));
            assertEquals(0, new BigDecimal("35000.00").compareTo(split.loserReturn()));
            assertEquals(0, BigDecimal.ZERO.compareTo(split.burned()),
                    "a declined war destroys nothing; nobody fought");
        }
    }

    // ==================================================================================
    // Draws, SPEC 11.9 and SPEC 17.4 case 55
    // ==================================================================================

    @Nested
    @DisplayName("deciding a draw")
    class Draws {

        @Test
        @DisplayName("scores within 5% of each other are a draw")
        void withinThreshold() {
            assertTrue(payouts.isDraw(100, 96));
            assertTrue(payouts.isDraw(100, 100));
        }

        @Test
        @DisplayName("scores further apart are not")
        void outsideThreshold() {
            assertFalse(payouts.isDraw(100, 80));
            assertFalse(payouts.isDraw(10, 0));
        }

        @Test
        @DisplayName("both sides on zero is a draw, not a division by nothing")
        void bothZero() {
            // SPEC 17.4 case 55 states this outcome directly.
            assertTrue(payouts.isDraw(0, 0));
        }

        @Test
        @DisplayName("the threshold is measured against the higher score")
        void measuredAgainstTheHigher() {
            // 5% of 200 is 10, so 190 draws and 189 does not.
            assertTrue(payouts.isDraw(200, 190));
            assertFalse(payouts.isDraw(200, 189));
        }
    }

    // ==================================================================================
    // Allies, SPEC 11.10
    // ==================================================================================

    @Nested
    @DisplayName("ally splits")
    class Allies {

        @Test
        @DisplayName("allies share 30% of the pool in proportion to what they scored")
        void proportionalShare() {
            BigDecimal winnerReturn = new BigDecimal("90000.00");

            // An ally that scored a quarter of the side's total takes a quarter of the pool.
            BigDecimal share = payouts.allyShare(winnerReturn, 25, 100);

            assertEquals(0, new BigDecimal("6750.00").compareTo(share),
                    "30% of 90,000 is 27,000, and a quarter of that is 6,750");
        }

        @Test
        @DisplayName("an ally that scored nothing takes nothing")
        void noScoreNoShare() {
            assertEquals(0, BigDecimal.ZERO
                    .compareTo(payouts.allyShare(new BigDecimal("90000.00"), 0, 100)));
        }

        @Test
        @DisplayName("a side that scored nothing splits nothing, rather than dividing by zero")
        void zeroSideScore() {
            assertEquals(0, BigDecimal.ZERO
                    .compareTo(payouts.allyShare(new BigDecimal("90000.00"), 10, 0)));
        }

        @Test
        @DisplayName("the allies together never take more than the 30% pool")
        void poolIsBounded() {
            BigDecimal winnerReturn = new BigDecimal("90000.00");

            BigDecimal first = payouts.allyShare(winnerReturn, 60, 100);
            BigDecimal second = payouts.allyShare(winnerReturn, 40, 100);

            assertEquals(0, new BigDecimal("27000.00").compareTo(first.add(second)),
                    "two allies splitting the whole side's score take exactly the pool");
        }
    }

    // ==================================================================================
    // Config, SPEC 16.3
    // ==================================================================================

    @Test
    @DisplayName("the SPEC 11.9 reward settings are read from war.yml, not hardcoded")
    void rewardsAreConfigured() {
        assertEquals(7L, payouts.immunityDays());
        assertEquals(7L, payouts.marketBonusDays());
        assertEquals(10.0, payouts.marketBonusPercent(), 1e-9);
    }
}
