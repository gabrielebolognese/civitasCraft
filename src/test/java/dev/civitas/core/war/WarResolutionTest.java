package dev.civitas.core.war;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.row.WarRow;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 11.9: deciding a war and paying for it.
 *
 * <p>{@code WarPayoutsTest} proves the arithmetic. This proves it reaches the treasuries, and
 * that the two seven-day consequences land: the loser's immunity, which SPEC 11.9 is careful
 * to call "protection, not punishment", and the winner's market bonus.
 */
class WarResolutionTest {

    @TempDir
    Path directory;

    /**
     * Anchored to the real clock, not to an arbitrary constant.
     *
     * <p>Cities are founded with {@code System.currentTimeMillis()}, so a test time in 1970
     * makes every city younger than zero and the SPEC 11.3 age precondition fires before the
     * one under test.
     */
    private static final long NOW = System.currentTimeMillis();
    private static final BigDecimal WAGER = new BigDecimal("50000.00");

    private CityTestSupport support;
    private WarRegistry registry;
    private WarRewards rewards;
    private WarResolution resolution;
    private City attacker;
    private City defender;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        registry = new WarRegistry(support.daos.wars());
        rewards = new WarRewards(support.daos.wars());
        resolution = new WarResolution(support.db, support.daos, support.registry,
                support.treasury, new WarPayouts(support.configs), rewards,
                CityTestSupport.quietLogger());

        attacker = support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
        defender = support.givenCity(support.givenEligiblePlayer("Dido"), "Carthago", 40, 40);
        fund(attacker, "0.00");
        fund(defender, "0.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private void fund(City city, String amount) {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal(amount)));
        city.setTreasury(new BigDecimal(amount));
    }

    private BigDecimal treasuryOf(City city) {
        return await(support.daos.cities().findById(city.id())).orElseThrow().treasury();
    }

    /** A war already fought, with the wagers escrowed and the scores in. */
    private War givenFoughtWar(int attackerScore, int defenderScore) {
        int id = await(support.daos.wars().insert(new WarRow(0, attacker.id(), defender.id(),
                NOW, NOW, NOW, WarState.ACTIVE.key(), 0, 0, null, WAGER, null, null)));
        War war = new War(id, attacker.id(), defender.id(), NOW, NOW, NOW, WarState.ACTIVE, WAGER);
        war.addScore(true, attackerScore);
        war.addScore(false, defenderScore);
        registry.remember(war);
        return war;
    }

    // ==================================================================================
    // A decided war
    // ==================================================================================

    @Nested
    @DisplayName("a war with a winner")
    class Decided {

        @Test
        @DisplayName("the winner's treasury receives their stake plus 80% of the loser's")
        void winnerIsPaid() {
            War war = givenFoughtWar(100, 10);

            Result<WarResolution.Outcome> result = await(resolution.resolve(war, NOW));

            assertTrue(result.isSuccess(), reasonOf(result));
            assertEquals(0, new BigDecimal("90000.00").compareTo(treasuryOf(attacker)));
            assertEquals(attacker.id(), result.orElseThrow().winnerCityId());
        }

        @Test
        @DisplayName("the loser receives nothing, because the remainder is destroyed")
        void loserIsNotPaid() {
            // The SPEC 11.9 contradiction, settled on the developer's instruction in favour of
            // the burn. See OPEN_QUESTIONS.md.
            War war = givenFoughtWar(100, 10);

            await(resolution.resolve(war, NOW));

            assertEquals(0, BigDecimal.ZERO.compareTo(treasuryOf(defender)));
        }

        @Test
        @DisplayName("the destroyed share is recorded, so the money supply can be audited")
        void burnIsLedgered() {
            // SPEC 1.5 makes every coin movement auditable, and coins leaving circulation are
            // the movement an admin is most likely to be asked about.
            War war = givenFoughtWar(100, 10);

            await(resolution.resolve(war, NOW));

            long burnRows = await(support.daos.ledger()
                    .findByCity(defender.id(), 0L, 100)).stream()
                    .filter(row -> TransactionType.WAR_WAGER_PAYOUT.name().equals(row.type()))
                    .filter(row -> row.amount().signum() < 0)
                    .count();
            assertTrue(burnRows > 0, "the burn must leave a trace");
        }

        @Test
        @DisplayName("the defender can win too")
        void defenderCanWin() {
            War war = givenFoughtWar(5, 200);

            Result<WarResolution.Outcome> result = await(resolution.resolve(war, NOW));

            assertEquals(defender.id(), result.orElseThrow().winnerCityId());
            assertEquals(0, new BigDecimal("90000.00").compareTo(treasuryOf(defender)));
        }
    }

    // ==================================================================================
    // SPEC 11.9's two seven-day consequences
    // ==================================================================================

    @Nested
    @DisplayName("after the war")
    class Consequences {

        @Test
        @DisplayName("the loser gets seven days of immunity")
        void loserIsProtected() {
            // SPEC 11.9 calls this "protection, not punishment", and SPEC 15.2 names it as a
            // defence against serial harassment of one city.
            War war = givenFoughtWar(100, 10);

            await(resolution.resolve(war, NOW));

            long until = await(support.daos.cities().findById(defender.id()))
                    .orElseThrow().warProtectionUntil();
            assertEquals(NOW + TimeUnit.DAYS.toMillis(7), until);
        }

        @Test
        @DisplayName("and that immunity blocks a fresh declaration")
        void immunityBlocksRedeclaration() {
            War war = givenFoughtWar(100, 10);
            await(resolution.resolve(war, NOW));

            // The member and claim floors are checked before immunity (SPEC 11.3's order), and
            // this test is about immunity, so those two are relaxed rather than satisfied.
            support.configs.get(dev.civitas.config.ConfigFile.WAR)
                    .set("declaration.min-members", 1);
            support.configs.get(dev.civitas.config.ConfigFile.WAR)
                    .set("declaration.min-claims", 1);
            support.configs.get(dev.civitas.config.ConfigFile.WAR)
                    .set("declaration.min-city-age-days", 0);

            WarService wars = new WarService(support.db, support.daos, support.registry,
                    support.claimRegistry, support.diplomacyRegistry, registry,
                    support.treasury, support.configs, dev.civitas.util.Scheduler.direct());

            Result<Void> checked = wars.checkDeclaration(attacker.mayorUuid(), attacker,
                    defender, WAGER, NOW + TimeUnit.DAYS.toMillis(1));

            assertEquals("DEFENDER_IMMUNE", reasonOf(checked));
        }

        @Test
        @DisplayName("the winner gets seven days of the market bonus")
        void winnerGetsTheBonus() {
            War war = givenFoughtWar(100, 10);

            await(resolution.resolve(war, NOW));

            assertTrue(rewards.hasMarketBonus(attacker.id(), NOW + TimeUnit.DAYS.toMillis(3)));
            assertFalse(rewards.hasMarketBonus(attacker.id(), NOW + TimeUnit.DAYS.toMillis(8)),
                    "and it expires");
            assertFalse(rewards.hasMarketBonus(defender.id(), NOW),
                    "the loser gets no bonus");
        }

        @Test
        @DisplayName("the bonus survives a restart")
        void bonusIsRebuiltFromTheWars() {
            // A city that won a war should not lose its week because the server rebooted.
            War war = givenFoughtWar(100, 10);
            await(resolution.resolve(war, NOW));

            WarRewards restarted = new WarRewards(support.daos.wars());
            await(restarted.load(NOW + TimeUnit.DAYS.toMillis(1), 7));

            assertTrue(restarted.hasMarketBonus(attacker.id(), NOW + TimeUnit.DAYS.toMillis(1)));
        }
    }

    // ==================================================================================
    // Draws, SPEC 17.4 case 55
    // ==================================================================================

    @Nested
    @DisplayName("a draw")
    class Draws {

        @Test
        @DisplayName("both wagers come back and nothing is destroyed")
        void bothRefunded() {
            War war = givenFoughtWar(100, 98);

            Result<WarResolution.Outcome> result = await(resolution.resolve(war, NOW));

            assertTrue(result.orElseThrow().isDraw());
            assertEquals(0, WAGER.compareTo(treasuryOf(attacker)));
            assertEquals(0, WAGER.compareTo(treasuryOf(defender)));
            assertEquals(0, BigDecimal.ZERO.compareTo(result.orElseThrow().burned()));
        }

        @Test
        @DisplayName("both sides on zero is a draw")
        void bothZero() {
            // SPEC 17.4 case 55, end to end rather than only in the arithmetic.
            War war = givenFoughtWar(0, 0);

            Result<WarResolution.Outcome> result = await(resolution.resolve(war, NOW));

            assertTrue(result.orElseThrow().isDraw());
            assertNull(result.orElseThrow().winnerCityId());
            assertEquals(0, WAGER.compareTo(treasuryOf(attacker)));
        }

        @Test
        @DisplayName("a draw grants no immunity and no bonus")
        void drawHasNoConsequences() {
            War war = givenFoughtWar(0, 0);

            await(resolution.resolve(war, NOW));

            assertEquals(0L, await(support.daos.cities().findById(defender.id()))
                    .orElseThrow().warProtectionUntil());
            assertFalse(rewards.hasMarketBonus(attacker.id(), NOW));
        }
    }

    // ==================================================================================
    // The record
    // ==================================================================================

    @Test
    @DisplayName("a resolved war appears in the war record, wins and losses both")
    void recordIsDerived() {
        // SPEC 13.3's War Record board reads this rather than a counter, so it cannot drift.
        War war = givenFoughtWar(100, 10);
        await(resolution.resolve(war, NOW));
        await(support.daos.wars().updateState(war.id(), WarState.RESOLVED.key()));

        var records = await(support.daos.wars().findRecords(10));

        assertEquals(2, records.size());
        assertEquals("Roma", records.get(0).name());
        assertEquals(1, records.get(0).wins());
        assertEquals(0, records.get(0).losses());
        assertEquals("Carthago", records.get(1).name());
        assertEquals(0, records.get(1).wins());
        assertEquals(1, records.get(1).losses());
    }

    @Test
    @DisplayName("an unresolved war counts for nobody")
    void unresolvedWarsAreNotRanked() {
        givenFoughtWar(100, 10);

        assertTrue(await(support.daos.wars().findRecords(10)).isEmpty(),
                "a war still being fought has no result to rank");
    }
}
