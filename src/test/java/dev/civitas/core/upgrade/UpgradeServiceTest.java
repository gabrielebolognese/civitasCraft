package dev.civitas.core.upgrade;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRank;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * City upgrades, SPEC 5.7.
 *
 * <p>The half of this that matters most is the effects. Storing a level is easy and reading
 * it back is easy; what is easy to get wrong is <em>forgetting to read it</em> in the system
 * the level is supposed to change, and that failure is silent. So every track that has a
 * consumer is tested by asking the consumer, not by asking the upgrade service.
 */
class UpgradeServiceTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private UpgradeService upgrades;
    private UUID mayor;
    private City city;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        upgrades = new UpgradeService(support.db, support.daos.cityUpgrades(), support.treasury,
                support.configs, Scheduler.direct());

        mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);
        fundTreasury("100000000.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private void fundTreasury(String amount) {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal(amount)));
        city.setTreasury(new BigDecimal(amount));
    }

    private void buy(UpgradeType type, int times) {
        for (int i = 0; i < times; i++) {
            Result<UpgradeService.Purchase> result = await(upgrades.purchase(mayor, city, type));
            assertTrue(result.isSuccess(), reasonOf(result));
        }
    }

    // ==================================================================================
    // SPEC 5.7's table
    // ==================================================================================

    @Nested
    @DisplayName("The SPEC 5.7 table")
    class Table {

        @ParameterizedTest(name = "{0} has five priced levels")
        @EnumSource(UpgradeType.class)
        @DisplayName("every track has exactly five levels, all priced")
        void fiveLevelsEach(UpgradeType type) {
            for (int level = 1; level <= UpgradeType.MAX_LEVEL; level++) {
                assertTrue(upgrades.costOf(type, level).isPresent(),
                        type + " level " + level + " has no price");
            }
            assertTrue(upgrades.costOf(type, 6).isEmpty(), "and no sixth");
            assertTrue(upgrades.costOf(type, 0).isEmpty());
        }

        @Test
        @DisplayName("the prices are the ones SPEC 5.7 lists")
        void prices() {
            assertEquals(0, new BigDecimal("20000")
                    .compareTo(upgrades.costOf(UpgradeType.POPULATION, 1).orElseThrow()));
            assertEquals(0, new BigDecimal("600000")
                    .compareTo(upgrades.costOf(UpgradeType.POPULATION, 5).orElseThrow()));
            assertEquals(0, new BigDecimal("30000")
                    .compareTo(upgrades.costOf(UpgradeType.VAULT, 1).orElseThrow()));
            assertEquals(0, new BigDecimal("1400000")
                    .compareTo(upgrades.costOf(UpgradeType.OUTPOST_RANGE, 5).orElseThrow()));
            assertEquals(0, new BigDecimal("1000000")
                    .compareTo(upgrades.costOf(UpgradeType.MARKET_ACCESS, 5).orElseThrow()));
        }

        @ParameterizedTest(name = "{0} is priced in ascending order")
        @EnumSource(UpgradeType.class)
        @DisplayName("each level costs more than the one before it")
        void ascending(UpgradeType type) {
            for (int level = 2; level <= UpgradeType.MAX_LEVEL; level++) {
                assertTrue(upgrades.costOf(type, level).orElseThrow()
                                .compareTo(upgrades.costOf(type, level - 1).orElseThrow()) > 0,
                        type + " level " + level + " is not dearer than " + (level - 1));
            }
        }
    }

    // ==================================================================================
    // Buying
    // ==================================================================================

    @Nested
    @DisplayName("Buying")
    class Buying {

        @Test
        @DisplayName("a purchase charges the treasury and raises the level")
        void purchase() {
            BigDecimal cost = upgrades.costOf(UpgradeType.POPULATION, 1).orElseThrow();
            BigDecimal before = city.treasury();

            UpgradeService.Purchase bought =
                    await(upgrades.purchase(mayor, city, UpgradeType.POPULATION)).orElseThrow();

            assertEquals(1, bought.level());
            assertEquals(1, upgrades.levelOf(city, UpgradeType.POPULATION));
            assertEquals(0, before.subtract(cost).compareTo(
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury()));
        }

        @Test
        @DisplayName("it is ledgered as an upgrade purchase")
        void ledgered() {
            buy(UpgradeType.VAULT, 1);

            assertEquals(1, await(support.daos.ledger()
                    .findByType(TransactionType.UPGRADE_PURCHASE.name(), 0L, 10)).size());
        }

        @Test
        @DisplayName("levels come one at a time, in order")
        void oneAtATime() {
            buy(UpgradeType.VAULT, 3);

            assertEquals(3, upgrades.levelOf(city, UpgradeType.VAULT));
            assertEquals(0, upgrades.costOf(UpgradeType.VAULT, 4).orElseThrow()
                    .compareTo(upgrades.nextCost(city, UpgradeType.VAULT).orElseThrow()));
        }

        @Test
        @DisplayName("a maxed track cannot be bought again")
        void maxed() {
            buy(UpgradeType.VAULT, UpgradeType.MAX_LEVEL);

            assertEquals("ALREADY_MAX",
                    reasonOf(await(upgrades.purchase(mayor, city, UpgradeType.VAULT))));
            assertTrue(upgrades.nextCost(city, UpgradeType.VAULT).isEmpty());
        }

        @Test
        @DisplayName("a treasury that cannot pay is refused, and the level does not move")
        void cannotAfford() {
            fundTreasury("100.00");

            assertEquals("TREASURY_SHORT",
                    reasonOf(await(upgrades.purchase(mayor, city, UpgradeType.POPULATION))));
            assertEquals(0, upgrades.levelOf(city, UpgradeType.POPULATION));
        }

        @Test
        @DisplayName("MANAGE_UPGRADES is what gates it")
        void permission() {
            UUID member = support.givenMember(city, "Titus");
            CityRank citizen = city.rankByName("Citizen").orElseThrow();
            await(support.ranks.assign(mayor, city, member, citizen));

            assertEquals("NO_CITY_PERMISSION",
                    reasonOf(await(upgrades.purchase(member, city, UpgradeType.VAULT))));

            await(support.ranks.setPermission(mayor, city, citizen,
                    CityPermission.MANAGE_UPGRADES, true));
            assertTrue(await(upgrades.purchase(member, city, UpgradeType.VAULT)).isSuccess());
        }

        @Test
        @DisplayName("a frozen or delinquent city cannot buy")
        void blockedStates() {
            city.setFrozen(true);
            assertEquals("CITY_FROZEN",
                    reasonOf(await(upgrades.purchase(mayor, city, UpgradeType.VAULT))));

            city.setFrozen(false);
            city.setDelinquentSince(System.currentTimeMillis());
            assertEquals("CITY_DELINQUENT",
                    reasonOf(await(upgrades.purchase(mayor, city, UpgradeType.VAULT))));
        }

        @Test
        @DisplayName("levels survive a restart")
        void reload() {
            buy(UpgradeType.POPULATION, 2);
            buy(UpgradeType.VAULT, 1);

            assertEquals(2, await(upgrades.loadAll()), "two rows, one per track touched");
            assertEquals(2, upgrades.levelOf(city, UpgradeType.POPULATION));
            assertEquals(1, upgrades.levelOf(city, UpgradeType.VAULT));
            assertEquals(3, upgrades.totalLevels(city.id()));
        }
    }

    // ==================================================================================
    // The four seams other systems read
    // ==================================================================================

    @Nested
    @DisplayName("Effects, asked of the systems they change")
    class Effects {

        @Test
        @DisplayName("SPEC 5.7 Population raises the member cap the join check uses")
        void population() {
            support.cities.useUpgrades(upgrades);
            int base = support.cities.memberCap(city);
            assertEquals(10, base, "the SPEC 16.2 base");

            buy(UpgradeType.POPULATION, 2);

            assertEquals(base + 10, support.cities.memberCap(city), "+5 a level");
        }

        @Test
        @DisplayName("SPEC 5.7 Treasury Interest lowers the bill the upkeep sweep produces")
        void treasuryInterest() {
            BigDecimal landValue = new BigDecimal("1000000.00");
            BigDecimal full = support.upkeep.dailyUpkeep(landValue, 0,
                    dev.civitas.storage.SqlDialect.zero(), 0);
            BigDecimal discounted = support.upkeep.dailyUpkeep(landValue, 0,
                    dev.civitas.storage.SqlDialect.zero(), 3);

            assertEquals(0, new BigDecimal("4000.00").compareTo(full));
            assertEquals(0, new BigDecimal("3520.00").compareTo(discounted),
                    "three levels is 12% off");
        }

        @Test
        @DisplayName("SPEC 5.7 Outpost Range raises the cap, to the SPEC 7.2 ceiling of six")
        void outpostRange() {
            dev.civitas.core.outpost.OutpostRegistry registry =
                    new dev.civitas.core.outpost.OutpostRegistry(support.daos.outposts());
            dev.civitas.core.outpost.OutpostService outposts =
                    new dev.civitas.core.outpost.OutpostService(support.db, support.daos,
                            support.registry, support.claimRegistry, support.claims, registry,
                            support.treasury, support.configs, Scheduler.direct());
            outposts.useUpgrades(upgrades);

            assertEquals(2, outposts.maxOutposts(city), "the SPEC 7.2 base");

            buy(UpgradeType.OUTPOST_RANGE, 3);
            assertEquals(5, outposts.maxOutposts(city));

            buy(UpgradeType.OUTPOST_RANGE, 2);
            assertEquals(6, outposts.maxOutposts(city),
                    "SPEC 7.2 promises at most six, and five levels of +1 would reach seven");
        }

        @Test
        @DisplayName("SPEC 5.7 Market Access lowers the tax a sale actually pays")
        void marketAccess() {
            support.market.useUpgrades(support.registry, upgrades);
            UUID seller = support.givenMember(city, "Trader");

            BigDecimal grossBefore = sellAndReadTax(seller);
            buy(UpgradeType.MARKET_ACCESS, 5);
            BigDecimal grossAfter = sellAndReadTax(seller);

            assertTrue(grossAfter.compareTo(grossBefore) < 0,
                    "five levels is 4 percentage points off the 5% tax");
        }

        /** Sells a fixed amount and returns the tax that was charged. */
        private BigDecimal sellAndReadTax(UUID seller) {
            var receipt = await(support.market.sell(seller, "WHEAT", 64)).orElseThrow();
            return receipt.tax();
        }

        @Test
        @DisplayName("Fortification is stored and read by nobody until M12 builds defense")
        void fortificationIsStoredOnly() {
            buy(UpgradeType.FORTIFICATION, 2);

            assertEquals(2, upgrades.levelOf(city, UpgradeType.FORTIFICATION));
            // Nothing consumes it yet. This test exists so that when M12 does, the level it
            // finds is already correct rather than needing to be plumbed in first.
            assertEquals(5.0, upgrades.effectPerLevel(UpgradeType.FORTIFICATION, 0), 0.001);
        }
    }

    // ==================================================================================
    // Keys
    // ==================================================================================

    @Test
    @DisplayName("every track's key round-trips, however it is typed")
    void keysParse() {
        for (UpgradeType type : UpgradeType.values()) {
            assertEquals(type, UpgradeType.parse(type.key()).orElseThrow());
            assertEquals(type, UpgradeType.parse(type.key().replace('-', '_')).orElseThrow(),
                    "a stored key with underscores still resolves");
            assertEquals(type, UpgradeType.parse(type.key().toUpperCase()).orElseThrow());
        }
        assertTrue(UpgradeType.parse("nonsense").isEmpty());
        assertTrue(UpgradeType.parse(null).isEmpty());
    }

    @Test
    @DisplayName("a city with nothing bought reads zero on every track")
    void defaultsAreZero() {
        for (UpgradeType type : UpgradeType.values()) {
            assertEquals(0, upgrades.levelOf(city, type));
        }
        assertEquals(0, upgrades.totalLevels(city.id()));
        assertFalse(upgrades.levelsOf(city.id()).isEmpty(), "but every track is listed");
    }
}
