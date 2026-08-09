package dev.civitas.core.defense;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.upgrade.UpgradeService;
import dev.civitas.core.upgrade.UpgradeType;
import dev.civitas.storage.row.DefenseUnitRow;
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
 * SPEC 25.5's budget and SPEC 30.2 case 101, end to end against real storage.
 *
 * <p>{@link DefenseCapacityTest} owns the arithmetic. What is left here is the part that only
 * shows up once rows and entities are involved: that a suspension writes {@code active = false}
 * and <b>keeps the row</b>, that it takes the entity down rather than leaving it standing, and
 * that clearing an upkeep debt does not quietly hand a city back an army it is over budget for.
 *
 * <p>Every spawn goes through {@link UnitMaterializer#useSpawn}. MockBukkit does not implement
 * {@code setRemoveWhenFarAway}, which {@link DefenseSpawner} calls on every spawn, and that
 * aborts a test as a <em>skip</em> rather than a failure — a suite whose key test never ran still
 * prints a green build.
 */
class DefenseCapacityIntegrationTest {

    private static final String WORLD = "world";

    @TempDir
    Path directory;

    private ServerMock server;
    private CityTestSupport support;
    private DefenseCatalogue catalogue;
    private DefenseRegistry registry;
    private DefenseService defense;
    private UnitMaterializer materializer;
    private CapacityReconciler reconciler;
    private UpgradeService upgrades;
    private UUID mayor;
    private City city;

    /** Work the reconciler handed to the server thread, drained by {@link #drain()}. */
    private final java.util.Queue<Runnable> mainThread =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private void drain() {
        Runnable next;
        while ((next = mainThread.poll()) != null) {
            next.run();
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld(WORLD);
        var plugin = MockBukkit.createMockPlugin("CivitasTest");
        support = CityTestSupport.open(directory);

        catalogue = new DefenseCatalogue(support.configs, CityTestSupport.quietLogger());
        catalogue.load();
        registry = new DefenseRegistry(support.daos.defenseUnits());
        upgrades = new UpgradeService(support.db, support.daos.cityUpgrades(), support.treasury,
                support.configs, Scheduler.direct());
        var lang = new dev.civitas.lang.LangManager(
                dev.civitas.config.PluginResources.ofClasspath(
                        directory.resolve("lang").toFile(), CityTestSupport.quietLogger()),
                support.configs);
        lang.load();

        defense = new DefenseService(plugin, support.db, support.daos.defenseUnits(), registry,
                catalogue, new DefenseSpawner(plugin, catalogue, lang), support.registry,
                support.claimRegistry, support.treasury, upgrades, lang, Scheduler.direct());
        materializer = new UnitMaterializer(support.configs, support.daos.defenseUnits(),
                registry, catalogue, new DefenseSpawner(plugin, catalogue, lang),
                support.registry, CityTestSupport.quietLogger());
        materializer.useUpgrades(target -> upgrades.levelOf(target, UpgradeType.FORTIFICATION));
        materializer.useSpawn((unit, type, owner, fortification) -> {
            var at = new org.bukkit.Location(server.getWorld(WORLD),
                    unit.x(), unit.y(), unit.z());
            var spawned = (org.bukkit.entity.LivingEntity) server.getWorld(WORLD)
                    .spawnEntity(at, type.mob());
            spawned.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)
                    .setBaseValue(type.health());
            spawned.setHealth(type.health());
            return java.util.Optional.of(spawned);
        });

        // Not Scheduler.direct(): the DAO future completes on the database thread, so a direct
        // scheduler would run the entity removal there and MockBukkit refuses an "Asynchronous
        // entity remove!". That refusal is the mock enforcing the same rule the real server
        // does, and the production path hops properly -- so the test queues the work and drains
        // it on the test thread, which is what the server thread is standing in for.
        reconciler = new CapacityReconciler(defense, registry, catalogue,
                support.daos.defenseUnits(), materializer, mainThread::add);
        defense.useCapacity(reconciler);

        mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal("50000000.00")));
        city.setTreasury(new BigDecimal("50000000.00"));
    }

    @AfterEach
    void tearDown() {
        support.close();
        MockBukkit.unmock();
    }

    /** A unit row written straight to storage, so nothing spawns from the database thread. */
    private DefenseUnit given(String type, double x, double z) {
        BigDecimal upkeep = catalogue.byKey(type).orElseThrow().upkeepPerDay();
        int id = await(support.daos.defenseUnits().insert(new DefenseUnitRow(
                0, city.id(), type, WORLD, x, 64.0, z, upkeep, true, null, null)));
        DefenseUnit unit = new DefenseUnit(id, city.id(), type, WORLD, x, 64.0, z,
                upkeep, true, null, null);
        registry.put(unit);
        return unit;
    }

    private void setCapacity(int base) {
        support.configs.get(ConfigFile.DEFENSE).set("capacity.base", base);
    }

    // ==================================================================================
    // The budget, on the purchase and placement paths
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 25.5 gates buying as well as placing")
    class Gates {

        @Test
        @DisplayName("a purchase past the budget is refused before any money moves")
        void purchaseIsRefused() {
            for (int i = 0; i < 5; i++) {
                given("city-guard", i * 16 + 8.0, 8.0);
            }
            assertEquals(100, defense.pointsSpent(city.id()));
            BigDecimal before = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();

            Result<org.bukkit.inventory.ItemStack> refused =
                    await(defense.purchase(mayor, city, catalogue.byKey("warhound").orElseThrow()));

            assertEquals("CAPACITY_FULL", reasonOf(refused));
            assertEquals(0, before.compareTo(await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury()), "a refusal must not charge for nothing");
        }

        @Test
        @DisplayName("a cheap unit still fits where an expensive one does not")
        void theBudgetIsAboutWhatNotHowMany() {
            // The whole point of SPEC 25.5 over a count: with 92 points spent, a Frost Sentry
            // fits and a Colossus does not, and a unit count could not tell them apart.
            for (int i = 0; i < 4; i++) {
                given("city-guard", i * 16 + 8.0, 8.0);
            }
            given("watchtower-keeper", 8.0, 40.0);
            assertEquals(90, defense.pointsSpent(city.id()));

            assertTrue(defense.fits(city, catalogue.byKey("frost-sentry").orElseThrow()));
            assertFalse(defense.fits(city, catalogue.byKey("colossus").orElseThrow()));
            assertFalse(defense.fits(city, catalogue.byKey("warhound").orElseThrow()),
                    "12 points against 10 left");
        }

        @Test
        @DisplayName("SPEC 27.8's three-per-chunk cap still applies beside the budget")
        void perChunkCapSurvives() {
            // Both rules, not one instead of the other: three Frost Sentries is 24 points out
            // of 100, so only the chunk cap can be what refuses the fourth.
            for (int i = 0; i < 3; i++) {
                given("frost-sentry", 8.0 + i, 8.0 + i);
            }
            assertEquals(24, defense.pointsSpent(city.id()));

            assertEquals("CHUNK_FULL", reasonOf(defense.checkPlacement(mayor, city,
                    catalogue.byKey("frost-sentry").orElseThrow(), WORLD, 11.0, 64.0, 11.0)));
        }
    }

    // ==================================================================================
    // SPEC 30.2 case 101
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 30.2 case 101")
    class Case101 {

        @Test
        @DisplayName("units over budget are marked inactive newest-first, and are not deleted")
        void suspendsNewestFirstAndKeepsTheRows() {
            List<DefenseUnit> placed = List.of(
                    given("city-guard", 8.0, 8.0),
                    given("city-guard", 24.0, 8.0),
                    given("city-guard", 40.0, 8.0),
                    given("city-guard", 56.0, 8.0),
                    given("city-guard", 72.0, 8.0));
            assertEquals(100, defense.pointsSpent(city.id()));

            // The only realistic trigger: nothing in the plugin can downgrade a Fortification
            // level, so an operator lowering the base and reloading is what case 101 sees.
            setCapacity(60);
            assertEquals(2, await(reconciler.reconcile(city)));

            assertEquals(60, defense.pointsSpent(city.id()));
            assertFalse(registry.byId(placed.get(4).id()).orElseThrow().active(),
                    "the newest goes first");
            assertFalse(registry.byId(placed.get(3).id()).orElseThrow().active());
            assertTrue(registry.byId(placed.get(0).id()).orElseThrow().active(),
                    "and the oldest stays");

            assertEquals(5, await(support.daos.defenseUnits().findByCity(city.id())).size(),
                    "\"Not deleted.\" A city that loses a level does not lose what it paid for");
            assertEquals(2, await(support.daos.defenseUnits().findByCity(city.id())).stream()
                    .filter(row -> !row.active()).count(), "and the flag is persisted");
        }

        @Test
        @DisplayName("upkeep stops for a suspended unit, which is what \"suspended\" means")
        void upkeepStops() {
            for (int i = 0; i < 5; i++) {
                given("city-guard", i * 16 + 8.0, 8.0);
            }
            assertEquals(0, new BigDecimal("4500").compareTo(registry.dailyUpkeep(city.id())));

            setCapacity(60);
            await(reconciler.reconcile(city));

            assertEquals(0, new BigDecimal("2700").compareTo(registry.dailyUpkeep(city.id())),
                    "three guards at 900, not five");
        }

        @Test
        @DisplayName("a suspended unit is taken down, and comes back at the health it went down at")
        void dematerialisesThroughTheMaterializer() {
            DefenseUnit oldest = given("city-guard", 8.0, 8.0);
            DefenseUnit newest = given("city-guard", 24.0, 8.0);
            long now = 1_700_000_000_000L;
            assertTrue(materializer.materialize(newest, now));
            registry.entityOf(newest.id()).orElseThrow().setHealth(36.0);   // 40% of 90

            setCapacity(20);
            await(reconciler.reconcile(city));
            drain();

            assertTrue(registry.byId(oldest.id()).orElseThrow().active());
            assertFalse(registry.isMaterialized(newest.id()),
                    "marking a unit inactive does not take it down; the reconciler has to");
            assertEquals(36.0, registry.byId(newest.id()).orElseThrow().health(), 0.01,
                    "and through the materializer, or a Fortification round trip is a free heal");
        }

        @Test
        @DisplayName("capacity coming back stands the same units up again, oldest first")
        void restoresWhenThereIsRoom() {
            for (int i = 0; i < 5; i++) {
                given("city-guard", i * 16 + 8.0, 8.0);
            }
            setCapacity(60);
            await(reconciler.reconcile(city));
            assertEquals(3, registry.activeCount(city.id()));

            setCapacity(100);
            assertEquals(2, await(reconciler.reconcile(city)));

            assertEquals(5, registry.activeCount(city.id()));
            assertEquals(100, defense.pointsSpent(city.id()));
        }

        @Test
        @DisplayName("a pass that changes nothing is the common case and writes nothing")
        void idempotent() {
            given("city-guard", 8.0, 8.0);

            assertEquals(0, await(reconciler.reconcile(city)));
            assertEquals(0, await(reconciler.reconcile(city)));
        }

        @Test
        @DisplayName("the city is told, because a garrison going dark must not be silent")
        void theCityIsTold() {
            java.util.List<Integer> told = new java.util.ArrayList<>();
            reconciler.useNotifier((owner, count) -> told.add(count));
            for (int i = 0; i < 5; i++) {
                given("city-guard", i * 16 + 8.0, 8.0);
            }

            setCapacity(60);
            await(reconciler.reconcile(city));

            assertEquals(List.of(2), told);
        }

        @Test
        @DisplayName("SPEC 30.2 case 100: a downgrade never removes a purchased unit")
        void nothingIsEverDeleted() {
            for (int i = 0; i < 5; i++) {
                given("city-guard", i * 16 + 8.0, 8.0);
            }
            setCapacity(0);
            await(reconciler.reconcile(city));

            assertEquals(0, registry.activeCount(city.id()));
            assertEquals(5, registry.of(city.id()).size());
            assertEquals(5, await(support.daos.defenseUnits().findByCity(city.id())).size());
        }
    }

    // ==================================================================================
    // The trap: reactivating a debt must not undo a suspension
    // ==================================================================================

    @Test
    @DisplayName("clearing an upkeep debt does not hand back units the budget cannot afford")
    void clearingADebtGoesThroughTheBudget() {
        // The single most likely bug in this milestone. SPEC 12.3's upkeep sweep reactivates a
        // whole city's units when a debt clears; a city whose newest units were suspended by
        // case 101 would get every one of them back and stand silently over capacity.
        for (int i = 0; i < 5; i++) {
            given("city-guard", i * 16 + 8.0, 8.0);
        }
        setCapacity(60);
        await(reconciler.reconcile(city));
        assertEquals(3, registry.activeCount(city.id()));

        // The city falls behind and catches up, exactly as UpkeepTask drives it.
        await(defense.setActive(city, false));
        assertEquals(0, registry.activeCount(city.id()));
        await(defense.setActive(city, true));

        assertEquals(3, registry.activeCount(city.id()),
                "three, not five: reactivation goes through the budget rather than around it");
        assertEquals(60, defense.pointsSpent(city.id()));
    }

    @Test
    @DisplayName("a delinquent city is left alone entirely, so two systems do not fight")
    void delinquentCitiesAreNotTouched() {
        given("city-guard", 8.0, 8.0);
        await(defense.setActive(city, false));
        city.setDelinquentSince(System.currentTimeMillis());

        assertEquals(0, await(reconciler.reconcile(city)),
                "standing a unit up here would hand a city an army it has failed to pay for");
        assertEquals(0, registry.activeCount(city.id()));
    }

    // ==================================================================================
    // What a stored type the catalogue no longer knows is worth
    // ==================================================================================

    @Test
    @DisplayName("a retired unit type costs no capacity, deliberately rather than accidentally")
    void unknownTypesCostNothing() {
        // Every defense_units row written before SPEC 25.1 retired the eight-unit roster carries
        // a key like this. Such rows cannot be spawned either, so they are ghosts rather than
        // free units -- but it is a decision, and DefenseCatalogue warns about it once.
        int id = await(support.daos.defenseUnits().insert(new DefenseUnitRow(
                0, city.id(), "sharpshooter", WORLD, 8.0, 64.0, 8.0,
                new BigDecimal("1400.00"), true, null, null)));
        registry.put(new DefenseUnit(id, city.id(), "sharpshooter", WORLD, 8.0, 64.0, 8.0,
                new BigDecimal("1400.00"), true, null, null));

        assertEquals(0, defense.pointsSpent(city.id()));
        assertEquals(0, catalogue.pointsOf("sharpshooter"));
        assertTrue(defense.fits(city, catalogue.byKey("colossus").orElseThrow()));
    }
}
