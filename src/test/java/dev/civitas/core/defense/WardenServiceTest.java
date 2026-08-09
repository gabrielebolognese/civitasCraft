package dev.civitas.core.defense;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.upgrade.UpgradeService;
import dev.civitas.core.upgrade.UpgradeType;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * SPEC 28's Warden against a real database, and SPEC 30.2 cases 97 to 100.
 *
 * <p>Nothing here spawns an entity. {@code org.bukkit.entity.Warden} is not implemented by
 * MockBukkit and neither is {@code setRemoveWhenFarAway}, and an unimplemented Bukkit method is
 * recorded by JUnit as a <em>skip</em> — so a test that reached for one would print green having
 * asserted nothing. What is asserted is the row, the money and the clock, which is where every
 * decision in SPEC 28.2 and 28.6 actually lives.
 */
class WardenServiceTest {

    private static final String WORLD = "world";

    @TempDir
    Path directory;

    private ServerMock server;
    private CityTestSupport support;
    private DefenseCatalogue catalogue;
    private DefenseRegistry units;
    private WardenRegistry registry;
    private WardenService wardens;
    private UpgradeService upgrades;
    private UUID mayor;
    private City city;

    /** Whether the city is treated as being in an ACTIVE war, which is the only lever SPEC 28.6 has. */
    private final AtomicBoolean atWar = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin("CivitasTest");
        support = CityTestSupport.open(directory);

        catalogue = new DefenseCatalogue(support.configs, CityTestSupport.quietLogger());
        catalogue.load();
        units = new DefenseRegistry(support.daos.defenseUnits());
        registry = new WardenRegistry(support.daos.cityWardens());
        upgrades = new UpgradeService(support.db, support.daos.cityUpgrades(), support.treasury,
                support.configs, Scheduler.direct());
        wardens = new WardenService(support.db, support.daos.cityWardens(),
                support.daos.defenseUnits(), registry, units, catalogue, support.registry,
                support.treasury, upgrades, Scheduler.direct());
        wardens.useWars(cityId -> atWar.get());

        mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);
        fundTreasury("50000000.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
        MockBukkit.unmock();
    }

    private void fundTreasury(String amount) {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal(amount)));
        city.setTreasury(new BigDecimal(amount));
    }

    /** Buys the Fortification levels SPEC 28.2 requires before a Warden may be bought. */
    private void maxFortification() {
        for (int level = 0; level < catalogue.wardenRequiredFortification(); level++) {
            Result<?> bought = await(upgrades.purchase(mayor, city, UpgradeType.FORTIFICATION));
            assertTrue(bought.isSuccess(), reasonOf(bought));
        }
        assertEquals(5, upgrades.levelOf(city, UpgradeType.FORTIFICATION));
    }

    /** Buys a Warden standing in the core chunk, which is the only legal spot. */
    private CityWarden.Owned buy() {
        Result<CityWarden.Owned> bought = await(wardens.purchase(mayor, city, WORLD,
                city.coreChunkX() * 16 + 8, 64, city.coreChunkZ() * 16 + 8));
        assertTrue(bought.isSuccess(), reasonOf(bought));
        return bought.orElseThrow();
    }

    // ==================================================================================
    // SPEC 28.2
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 28.2, buying")
    class Buying {

        @Test
        @DisplayName("a maxed city buys one, and pays 750,000 C from the treasury")
        void buysAndPays() {
            maxFortification();
            BigDecimal before = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();

            CityWarden.Owned owned = buy();

            assertTrue(registry.owns(city.id()));
            assertEquals(before.subtract(new BigDecimal("750000.00")),
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury());
            // One defense_units row, so materialisation, the leash and the upkeep sweep need no
            // second path -- and one city_wardens row, which is what limits it to one.
            assertEquals(1, await(support.daos.defenseUnits().findByCity(city.id())).size());
            assertEquals(owned.unitId(),
                    await(support.daos.cityWardens().findByCity(city.id()))
                            .orElseThrow().unitId());
        }

        @Test
        @DisplayName("SPEC 28.2: a city below Fortification 5 is refused and pays nothing")
        void fortificationGate() {
            BigDecimal before = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();

            Result<CityWarden.Owned> refused = await(wardens.purchase(mayor, city, WORLD,
                    city.coreChunkX() * 16 + 8, 64, city.coreChunkZ() * 16 + 8));

            assertFalse(refused.isSuccess());
            assertEquals("NEEDS_FORTIFICATION", reasonOf(refused));
            assertEquals(before,
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury());
        }

        @Test
        @DisplayName("SPEC 28.2: one per city, so the second purchase is refused")
        void oneEach() {
            maxFortification();
            buy();
            BigDecimal after = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();

            Result<CityWarden.Owned> second = await(wardens.purchase(mayor, city, WORLD,
                    city.coreChunkX() * 16 + 8, 64, city.coreChunkZ() * 16 + 8));

            assertFalse(second.isSuccess());
            assertEquals("ALREADY_OWNED", reasonOf(second));
            assertEquals(after,
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury());
            assertEquals(1, await(support.daos.defenseUnits().findByCity(city.id())).size());
        }

        @Test
        @DisplayName("SPEC 28.2: it may only be placed in the core chunk")
        void coreChunkOnly() {
            maxFortification();

            Result<CityWarden.Owned> elsewhere = await(wardens.purchase(mayor, city, WORLD,
                    (city.coreChunkX() + 4) * 16 + 8, 64, city.coreChunkZ() * 16 + 8));

            assertFalse(elsewhere.isSuccess());
            assertEquals("NOT_CORE_CHUNK", reasonOf(elsewhere));
            assertFalse(registry.owns(city.id()));
            assertTrue(await(support.daos.defenseUnits().findByCity(city.id())).isEmpty(),
                    "a refused placement must leave no unit row behind");
        }

        @Test
        @DisplayName("SPEC 28.2: it costs no Defense Capacity, so a full garrison still fits it")
        void costsNoCapacity() {
            maxFortification();
            buy();

            // pointsOf is the one function the budget reads, and the Warden's row prices at zero
            // through it -- so nothing downstream needs a special case to exclude it.
            List<DefenseCapacity.Placed> standing =
                    units.standing(city.id(), catalogue::pointsOf);
            assertEquals(1, standing.size());
            assertEquals(0, DefenseCapacity.spent(standing));
        }

        @Test
        @DisplayName("SPEC 30.4: buying one is announced")
        void announced() {
            maxFortification();
            AtomicReference<String> told = new AtomicReference<>();
            wardens.onPurchased((who, owned) -> told.set(who.name()));

            buy();

            assertEquals("Roma", told.get());
        }
    }

    // ==================================================================================
    // SPEC 28.6
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 28.6, dying and coming back")
    class Recovery {

        @Test
        @DisplayName("SPEC 28.6: a peacetime defeat burrows it rather than killing it")
        void peacetimeIsRecoverable() {
            maxFortification();
            CityWarden.Owned owned = buy();
            long now = 1_000_000L;

            Optional<Long> until = await(wardens.defeated(owned, now));

            assertEquals(CityWarden.recoveryEndsAt(now, 6), until.orElseThrow());
            assertTrue(registry.owns(city.id()), "the city still owns it while it is underground");
            assertTrue(registry.of(city.id()).orElseThrow().isRecovering(now));
            // The rows survive. A 750,000 C asset is not deleted because somebody hit it.
            assertTrue(await(support.daos.cityWardens().findByCity(city.id())).isPresent());
            assertEquals(1, await(support.daos.defenseUnits().findByCity(city.id())).size());
        }

        @Test
        @DisplayName("SPEC 28.6: a recovering city may not buy a second one")
        void recoveringStillCounts() {
            maxFortification();
            CityWarden.Owned owned = buy();
            await(wardens.defeated(owned, 1_000_000L));

            assertEquals(Optional.of(CityWarden.Refusal.ALREADY_OWNED), wardens.check(city));
        }

        @Test
        @DisplayName("SPEC 28.6: six hours later it comes back, at full health")
        void comesBackFull() {
            maxFortification();
            CityWarden.Owned owned = buy();
            long now = 1_000_000L;
            await(wardens.defeated(owned, now));
            // Damaged on the way down, which is what makes "at full health" a claim worth testing.
            await(support.daos.defenseUnits().saveState(owned.unitId(), 12.0, now));
            AtomicInteger returned = new AtomicInteger();
            wardens.onRecovered(who -> returned.incrementAndGet());

            assertEquals(0, await(wardens.sweepRecovered(now + 5 * 3_600_000L)),
                    "five hours is not six");
            assertEquals(1, await(wardens.sweepRecovered(now + 6 * 3_600_000L)));

            assertFalse(registry.of(city.id()).orElseThrow().isRecovering(now + 6 * 3_600_000L));
            // Null health reads as full, which is the same rule a never-materialised unit follows.
            assertEquals(null, await(support.daos.defenseUnits().findById(owned.unitId()))
                    .orElseThrow().health());
            assertEquals(1, returned.get());
        }

        @Test
        @DisplayName("case 98: a war during recovery neither ends it early nor extends it")
        void warDoesNotChangeRecovery() {
            maxFortification();
            CityWarden.Owned owned = buy();
            long now = 1_000_000L;
            await(wardens.defeated(owned, now));

            // "Recovery continues. The city fights that war without it."
            atWar.set(true);
            assertEquals(0, await(wardens.sweepRecovered(now + 5 * 3_600_000L)));
            assertTrue(registry.of(city.id()).orElseThrow()
                    .isRecovering(now + 5 * 3_600_000L));
            assertEquals(1, await(wardens.sweepRecovered(now + 6 * 3_600_000L)));
        }

        @Test
        @DisplayName("SPEC 28.6 and case 97: a war kill is permanent, on any day of the war")
        void warIsPermanent() {
            maxFortification();
            CityWarden.Owned owned = buy();
            atWar.set(true);
            AtomicReference<String> announced = new AtomicReference<>();
            wardens.onDestroyedInWar(who -> announced.set(who.name()));

            assertEquals(Optional.empty(), await(wardens.defeated(owned, 1_000_000L)));

            assertFalse(registry.owns(city.id()));
            assertTrue(await(support.daos.cityWardens().findByCity(city.id())).isEmpty());
            assertTrue(await(support.daos.defenseUnits().findByCity(city.id())).isEmpty());
            assertEquals("Roma", announced.get());
        }

        @Test
        @DisplayName("case 97: after a war death it may be bought again, at full price")
        void repurchasableAfterAWar() {
            maxFortification();
            CityWarden.Owned first = buy();
            atWar.set(true);
            await(wardens.defeated(first, 1_000_000L));
            atWar.set(false);

            BigDecimal before = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();
            CityWarden.Owned second = buy();

            assertNotEquals(first.unitId(), second.unitId());
            assertEquals(before.subtract(new BigDecimal("750000.00")),
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury(),
                    "SPEC 28.6: repurchased at full price");
        }
    }

    // ==================================================================================
    // SPEC 30.2 cases 99 and 100
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 30.2, the admin cases")
    class AdminCases {

        @Test
        @DisplayName("case 99: an admin moving the core chunk removes it and refunds 100%")
        void adminRefundsInFull() {
            maxFortification();
            buy();
            BigDecimal after = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();
            AtomicReference<BigDecimal> told = new AtomicReference<>();
            wardens.onAdminRemoved((who, refund) -> told.set(refund));

            BigDecimal refunded = await(wardens.removeForAdmin(city));

            // The only path in the plugin that refunds a defense unit, because case 99 calls it
            // "an admin action, not a player outcome".
            assertEquals(new BigDecimal("750000"), refunded);
            assertEquals(after.add(new BigDecimal("750000.00")),
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury());
            assertFalse(registry.owns(city.id()));
            assertEquals(new BigDecimal("750000"), told.get());
        }

        @Test
        @DisplayName("a city with no Warden refunds nothing and does not fail")
        void adminOnACityWithout() {
            assertEquals(0, await(wardens.removeForAdmin(city)).signum());
        }

        @Test
        @DisplayName("case 100: a Fortification downgrade does not take it away")
        void downgradeKeepsIt() {
            maxFortification();
            CityWarden.Owned owned = buy();

            // "Downgrades do not retroactively remove purchased units." Nothing re-checks the
            // gate after purchase, which is what makes that true -- a defensive re-check at
            // materialisation time would read as good hygiene and would violate the case.
            await(support.daos.cityUpgrades().setLevel(city.id(),
                    UpgradeType.FORTIFICATION.key(), 0));
            upgrades.loadAll().join();

            assertEquals(0, upgrades.levelOf(city, UpgradeType.FORTIFICATION));
            assertTrue(registry.owns(city.id()));
            assertEquals(owned.unitId(), registry.of(city.id()).orElseThrow().unitId());
        }

        @Test
        @DisplayName("a disbanding city takes its Warden with it, and refunds nothing")
        void disbandRemovesIt() {
            maxFortification();
            buy();
            BigDecimal after = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();

            await(wardens.removeCity(city.id()));

            assertFalse(registry.owns(city.id()));
            assertTrue(await(support.daos.cityWardens().findByCity(city.id())).isEmpty());
            assertEquals(after,
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury());
        }
    }
}
