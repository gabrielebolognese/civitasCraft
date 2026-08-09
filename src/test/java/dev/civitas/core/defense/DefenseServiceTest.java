package dev.civitas.core.defense;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRank;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.upgrade.UpgradeService;
import dev.civitas.core.upgrade.UpgradeType;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Defense units, SPEC 12.
 *
 * <p>One test per row of the SPEC 12.2 stat table and the SPEC 12.4 placement rules, plus
 * SPEC 17.4 case 56. The catalogue is loaded from the shipped {@code defense.yml}, so these
 * assert the numbers a server actually gets rather than numbers invented here.
 */
class DefenseServiceTest {

    private static final String WORLD = "world";

    @TempDir
    Path directory;

    private ServerMock server;
    private UnitMaterializer materializer;
    private CityTestSupport support;
    private DefenseCatalogue catalogue;
    private DefenseRegistry registry;
    private DefenseService defense;
    private UpgradeService upgrades;
    private UUID mayor;
    private City city;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin("CivitasTest");
        support = CityTestSupport.open(directory);

        catalogue = new DefenseCatalogue(support.configs, CityTestSupport.quietLogger());
        catalogue.load();
        registry = new DefenseRegistry(support.daos.defenseUnits());
        upgrades = new UpgradeService(support.db, support.daos.cityUpgrades(), support.treasury,
                support.configs, Scheduler.direct());
        defense = new DefenseService(plugin, support.db, support.daos.defenseUnits(), registry,
                catalogue, new DefenseSpawner(plugin, catalogue, langOf(plugin)),
                support.registry, support.claimRegistry, support.treasury, upgrades,
                langOf(plugin), Scheduler.direct());

        materializer = new UnitMaterializer(support.configs, support.daos.defenseUnits(),
                registry, catalogue, new DefenseSpawner(plugin, catalogue, langOf(plugin)),
                support.registry, CityTestSupport.quietLogger());
        materializer.useUpgrades(target -> upgrades.levelOf(target, UpgradeType.FORTIFICATION));

        mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);
        fundTreasury("50000000.00");
    }

    private dev.civitas.lang.LangManager langOf(org.bukkit.plugin.Plugin plugin) {
        dev.civitas.lang.LangManager lang = new dev.civitas.lang.LangManager(
                dev.civitas.config.PluginResources.ofClasspath(
                        directory.resolve("lang").toFile(), CityTestSupport.quietLogger()),
                support.configs);
        lang.load();
        return lang;
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

    private DefenseUnitType type(String key) {
        return catalogue.byKey(key).orElseThrow();
    }

    private DefenseUnit place(String key, double x, double z) {
        Result<DefenseUnit> placed = await(defense.place(mayor, city, type(key), WORLD,
                x, 64.0, z));
        assertTrue(placed.isSuccess(), reasonOf(placed));
        return placed.orElseThrow();
    }

    // ==================================================================================
    // SPEC 12.2, the catalogue
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 12.2 catalogue")
    class Catalogue {

        @Test
        @DisplayName("all eight units load from the shipped config")
        void eightUnits() {
            assertEquals(8, catalogue.size());
            for (String key : List.of("watchman", "city-guard", "elite-guard", "archer",
                    "sharpshooter", "warhound", "siege-golem", "sentry")) {
                assertTrue(catalogue.byKey(key).isPresent(), key + " is missing");
            }
        }

        @Test
        @DisplayName("the stats are the ones SPEC 12.2 lists")
        void stats() {
            DefenseUnitType watchman = type("watchman");
            assertEquals(EntityType.ZOMBIE, watchman.mob());
            assertEquals(40, watchman.health());
            assertEquals(5, watchman.damage());
            assertEquals(0, new BigDecimal("8000").compareTo(watchman.cost()));
            assertEquals(0, new BigDecimal("400").compareTo(watchman.upkeepPerDay()));

            DefenseUnitType golem = type("siege-golem");
            assertEquals(EntityType.IRON_GOLEM, golem.mob());
            assertEquals(250, golem.health());
            assertEquals(0, new BigDecimal("60000").compareTo(golem.cost()));
            assertEquals(0, new BigDecimal("3000").compareTo(golem.upkeepPerDay()));
        }

        @Test
        @DisplayName("equipment and enchantments load")
        void equipment() {
            DefenseUnitType guard = type("city-guard");
            assertEquals(Material.IRON_CHESTPLATE,
                    guard.equipment().get(DefenseUnitType.EquipmentSlotKey.CHESTPLATE));
            assertEquals(Material.SHIELD,
                    guard.equipment().get(DefenseUnitType.EquipmentSlotKey.OFF_HAND));

            DefenseUnitType sharpshooter = type("sharpshooter");
            assertTrue(sharpshooter.isRanged());
            assertEquals(5, sharpshooter.mainHandEnchantments().get("POWER"));
        }

        @Test
        @DisplayName("the Sentry debuffs instead of hitting things")
        void sentryIsSupport() {
            DefenseUnitType sentry = type("sentry");

            assertTrue(sentry.isSupport());
            assertEquals(0, sentry.damage());
            assertTrue(sentry.effect().isPresent());
        }

        @Test
        @DisplayName("the list is cheapest first, which is buying order")
        void sortedByCost() {
            List<DefenseUnitType> all = catalogue.all();

            assertEquals("sentry", all.get(0).key(), "6,000 C");
            assertEquals("siege-golem", all.get(all.size() - 1).key(), "60,000 C");
        }
    }

    // ==================================================================================
    // SPEC 12.4, placement
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 12.4 placement")
    class Placement {

        @Test
        @DisplayName("a unit is placed inside the city's own claims")
        void ownLandOnly() {
            assertTrue(defense.checkPlacement(mayor, city, type("watchman"), WORLD,
                    8.0, 64.0, 8.0).isSuccess(), "the core chunk is theirs");

            assertEquals("NOT_YOUR_LAND", reasonOf(defense.checkPlacement(mayor, city,
                    type("watchman"), WORLD, 5000.0, 64.0, 5000.0)));
        }

        @Test
        @DisplayName("SPEC 12.4: five units to start with")
        void unitLimit() {
            assertEquals(5, defense.maxUnits(city));

            for (int i = 0; i < 5; i++) {
                // Spread across chunks so the per-chunk cap is not what stops it.
                place("watchman", i * 16 + 8.0, 8.0);
                support.claims.registry().put(new dev.civitas.core.claim.Claim(
                        100L + i, city.id(), WORLD, i + 1, 0, System.currentTimeMillis(),
                        mayor, BigDecimal.ZERO, dev.civitas.core.claim.ClaimType.NORMAL, null));
            }

            Result<DefenseUnitType> sixth = defense.checkPlacement(mayor, city,
                    type("watchman"), WORLD, 8.0, 64.0, 8.0);
            assertEquals("UNIT_LIMIT", reasonOf(sixth));
        }

        @Test
        @DisplayName("SPEC 12.4: Fortification raises the cap, at two units a level")
        void fortificationRaisesTheCap() {
            // SPEC 5.7 says +1 and SPEC 12.4 says +2, and 12.4's stated range of 5 to 15 is
            // only arithmetic at +2. This is the resolution, asserted.
            await(support.daos.cityUpgrades().setLevel(city.id(),
                    UpgradeType.FORTIFICATION.key(), 5));
            await(upgrades.loadAll());

            assertEquals(15, defense.maxUnits(city), "SPEC 12.4's own 5 to 15");
        }

        @Test
        @DisplayName("SPEC 12.4: no more than three units in one chunk")
        void perChunkCap() {
            place("watchman", 8.0, 8.0);
            place("watchman", 9.0, 9.0);
            place("watchman", 10.0, 10.0);

            assertEquals("CHUNK_FULL", reasonOf(defense.checkPlacement(mayor, city,
                    type("watchman"), WORLD, 11.0, 64.0, 11.0)));
            assertEquals(3, registry.countInChunk(city.id(), WORLD, 0, 0));
        }

        @Test
        @DisplayName("MANAGE_DEFENSE is what gates buying and placing")
        void permission() {
            UUID member = support.givenMember(city, "Titus");
            CityRank citizen = city.rankByName("Citizen").orElseThrow();
            await(support.ranks.assign(mayor, city, member, citizen));

            assertEquals("NO_CITY_PERMISSION",
                    reasonOf(await(defense.purchase(member, city, type("watchman")))));
            assertEquals("NO_CITY_PERMISSION", reasonOf(defense.checkPlacement(member, city,
                    type("watchman"), WORLD, 8.0, 64.0, 8.0)));
        }
    }

    // ==================================================================================
    // Buying
    // ==================================================================================

    @Nested
    @DisplayName("Buying")
    class Buying {

        @Test
        @DisplayName("a purchase charges the treasury and hands over a stamped egg")
        void purchase() {
            BigDecimal before = city.treasury();

            ItemStack egg = await(defense.purchase(mayor, city, type("warhound"))).orElseThrow();

            assertEquals(Material.WOLF_SPAWN_EGG, egg.getType(),
                    "the unit's own egg, so it reads as what it is");
            assertEquals("warhound", defense.readEgg(egg).orElseThrow().typeKey());
            assertEquals(city.id(), defense.readEgg(egg).orElseThrow().cityId());
            assertEquals(0, before.subtract(new BigDecimal("10000")).compareTo(
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury()));
        }

        @Test
        @DisplayName("it is ledgered as a defense purchase")
        void ledgered() {
            await(defense.purchase(mayor, city, type("watchman")));

            assertEquals(1, await(support.daos.ledger()
                    .findByType(TransactionType.DEFENSE_PURCHASE.name(), 0L, 10)).size());
        }

        @Test
        @DisplayName("an ordinary spawn egg is not a purchased one")
        void plainEggIsNotAUnit() {
            assertTrue(defense.readEgg(new ItemStack(Material.ZOMBIE_SPAWN_EGG)).isEmpty());
            assertTrue(defense.readEgg(null).isEmpty());
        }

        @Test
        @DisplayName("a treasury that cannot pay buys nothing")
        void cannotAfford() {
            fundTreasury("100.00");

            assertEquals("TREASURY_SHORT",
                    reasonOf(await(defense.purchase(mayor, city, type("siege-golem")))));
        }
    }

    // ==================================================================================
    // Upkeep and losing units
    // ==================================================================================

    @Nested
    @DisplayName("Upkeep and losses")
    class Upkeep {

        @Test
        @DisplayName("SPEC 12.2: standing units add their fee to the city's daily bill")
        void dailyUpkeep() {
            place("watchman", 8.0, 8.0);
            place("archer", 9.0, 9.0);

            assertEquals(0, new BigDecimal("1100").compareTo(registry.dailyUpkeep(city.id())),
                    "400 plus 700");
        }

        @Test
        @DisplayName("SPEC 12.3: deactivated units stop costing, and their rows survive")
        void deactivation() {
            place("watchman", 8.0, 8.0);

            await(defense.setActive(city, false));

            assertEquals(0, BigDecimal.ZERO.compareTo(registry.dailyUpkeep(city.id())));
            assertEquals(1, registry.of(city.id()).size(),
                    "the unit is still owned, just not standing");
            assertEquals(0, registry.activeCount(city.id()));

            await(defense.setActive(city, true));
            assertEquals(1, registry.activeCount(city.id()), "and it comes back when paid");
        }

        @Test
        @DisplayName("SPEC 12.3 and 17.4 case 56: a dead unit is gone, and refunds nothing")
        void deathIsPermanent() {
            DefenseUnit unit = place("watchman", 8.0, 8.0);
            BigDecimal treasuryBefore = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();

            await(defense.onDeath(unit, UUID.randomUUID()));

            assertTrue(registry.byId(unit.id()).isEmpty());
            assertEquals(0, treasuryBefore.compareTo(
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury()),
                    "killing a unit, even your own, refunds nothing");
        }

        @Test
        @DisplayName("dismissing a unit refunds nothing either")
        void dismissRefundsNothing() {
            DefenseUnit unit = place("watchman", 8.0, 8.0);
            BigDecimal before = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();

            assertTrue(await(defense.dismiss(mayor, city, unit)).isSuccess());

            assertEquals(0, registry.of(city.id()).size());
            assertEquals(0, before.compareTo(
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury()));
        }

        @Test
        @DisplayName("SPEC 12.3: a disbanding city loses every unit")
        void disbandRemovesAll() {
            place("watchman", 8.0, 8.0);
            place("archer", 9.0, 9.0);

            await(defense.removeCity(city));

            assertEquals(0, registry.of(city.id()).size());
            assertEquals(0, await(support.daos.defenseUnits().findByCity(city.id())).size());
        }

        @Test
        @DisplayName("units survive a restart")
        void reload() {
            place("watchman", 8.0, 8.0);
            place("archer", 9.0, 9.0);

            assertEquals(2, await(registry.loadAll()));
            assertEquals(2, registry.activeCount(city.id()));
        }
    }

    // ==================================================================================
    // Config
    // ==================================================================================

    @Test
    @DisplayName("the caps and the wartime multiplier are config keys")
    void configurable() {
        assertEquals(5, catalogue.baseMaxUnits());
        assertEquals(2, catalogue.unitsPerFortificationLevel());
        assertEquals(3, catalogue.maxUnitsPerChunk());
        assertEquals(2.0, catalogue.wartimeMultiplier(), 0.001);
        assertEquals(5.0, catalogue.healthBonusPercentPerLevel(), 0.001);

        support.configs.get(ConfigFile.DEFENSE).set("placement.max-units-per-chunk", 1);
        assertEquals(1, catalogue.maxUnitsPerChunk());
    }

    @Test
    @DisplayName("defense can be turned off entirely")
    void disabled() {
        support.configs.get(ConfigFile.DEFENSE).set("enabled", false);

        assertFalse(catalogue.enabled());
        assertEquals("DEFENSE_DISABLED",
                reasonOf(await(defense.purchase(mayor, city, type("watchman")))));
    }

    @Test
    @DisplayName("a unit knows which chunk it stands in")
    void chunkMath() {
        DefenseUnit unit = place("watchman", 8.0, 8.0);

        assertEquals(0, unit.chunkX());
        assertEquals(0, unit.chunkZ());
        assertNotNull(unit.toRow());
    }

    // ==================================================================================
    // SPEC 25.4, materialisation
    // ==================================================================================

    @Nested
    @DisplayName("materialisation, SPEC 25.4")
    class Materialising {

        @BeforeEach
        void giveThisClassAWorld() {
            // Scoped to these tests deliberately. DefenseService.place() spawns its entity
            // from the database thread, and under Scheduler.direct() that thread is not the
            // main one, so MockBukkit refuses with "Asynchronous entity add!". Every defense
            // test written before this milestone passed partly BECAUSE no world existed and
            // the spawn silently did nothing — which is also why DefenseSpawner had never
            // actually run under test. These tests call the materializer directly, on the
            // test thread, so they can have one.
            server.addSimpleWorld(WORLD);

            // MockBukkit does not implement setRemoveWhenFarAway, which SPEC 31 case 106
            // requires and DefenseSpawner calls on every spawn — so the real spawner aborts
            // any test that uses it, and does so as a SKIP rather than a failure. This spawns
            // the same mob without that one call, so the health round trip can be asserted.
            materializer.useSpawn((unit, type, city, fortification) -> {
                var loc = new org.bukkit.Location(server.getWorld(WORLD),
                        unit.x(), unit.y(), unit.z());
                var spawned = (org.bukkit.entity.LivingEntity) server.getWorld(WORLD)
                        .spawnEntity(loc, type.mob());
                spawned.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)
                        .setBaseValue(type.health());
                spawned.setHealth(type.health());
                return java.util.Optional.of(spawned);
            });
        }

        /** A unit row, written straight to storage so no spawn happens on the wrong thread. */
        private DefenseUnit given() {
            int id = await(support.daos.defenseUnits().insert(
                    new dev.civitas.storage.row.DefenseUnitRow(0, city.id(), "city-guard",
                            WORLD, 8.0, 64.0, 8.0, new BigDecimal("900.00"), true, null, null)));
            DefenseUnit unit = new DefenseUnit(id, city.id(), "city-guard", WORLD,
                    8.0, 64.0, 8.0, new BigDecimal("900.00"), true, null, null);
            registry.put(unit);
            return unit;
        }

        @Test
        @DisplayName("SPEC 25.4: 40% out is 40% back")
        void healthSurvivesTheRoundTrip() {
            DefenseUnit unit = given();
            long now = 1_700_000_000_000L;

            assertTrue(materializer.materialize(unit, now), "it should come up");
            org.bukkit.entity.LivingEntity entity = registry.entityOf(unit.id()).orElseThrow();
            double maximum = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)
                    .getValue();
            entity.setHealth(maximum * 0.4);

            assertTrue(materializer.dematerialize(registry.byId(unit.id()).orElseThrow(), now));

            DefenseUnit down = registry.byId(unit.id()).orElseThrow();
            assertNotNull(down.health(), "health must be written on the way down");
            assertEquals(maximum * 0.4, down.health(), 0.01,
                    "before this milestone, despawning a unit was a free full heal");
            assertNotNull(down.dormantSince(), "and it is dormant from now");
        }

        @Test
        @DisplayName("it heals while dormant, and comes back with the healing applied")
        void dormantRegeneration() {
            DefenseUnit unit = given();
            long placed = 1_700_000_000_000L;
            assertTrue(materializer.materialize(unit, placed));

            org.bukkit.entity.LivingEntity entity = registry.entityOf(unit.id()).orElseThrow();
            double maximum = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)
                    .getValue();
            entity.setHealth(maximum * 0.4);
            materializer.dematerialize(registry.byId(unit.id()).orElseThrow(), placed);

            // Two hours later at 10% an hour: 40% plus 20%.
            long later = placed + 2 * 3_600_000L;
            assertTrue(materializer.materialize(registry.byId(unit.id()).orElseThrow(), later));

            DefenseUnit up = registry.byId(unit.id()).orElseThrow();
            assertEquals(maximum * 0.6, up.health(), maximum * 0.02);
            assertNull(up.dormantSince(),
                    "standing up ends dormancy, or it would be paid for those hours twice");
        }

        @Test
        @DisplayName("a unit that has never materialised is at full health, not at zero")
        void neverMaterializedIsFull() {
            // The V22 upgrade adds the column to rows that already exist. Read as zero, it
            // would kill every unit on the server the moment an operator upgraded.
            DefenseUnit unit = given();
            assertNull(unit.health());
            assertEquals(100.0, unit.healthOr(100.0), 0.001);
        }

        @Test
        @DisplayName("the checkpoint writes only what changed")
        void checkpointIsSparse() {
            DefenseUnit unit = given();
            materializer.materialize(unit, 1_700_000_000_000L);

            assertEquals(0, materializer.checkpoint(),
                    "nothing has taken damage, so nothing needs writing");

            registry.entityOf(unit.id()).orElseThrow().setHealth(5.0);
            assertEquals(1, materializer.checkpoint());
            assertEquals(5.0, registry.byId(unit.id()).orElseThrow().health(), 0.01);
        }
    }
}
