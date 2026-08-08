package dev.civitas.core.waystation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.mining.MiningClaimRegistry;
import dev.civitas.core.mining.MiningClaimService;
import dev.civitas.storage.row.WaystationChunkRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SPEC 39.10's waystations: the rules, not the arithmetic, which its cost-engine test owns. */
class WaystationServiceTest {

    private static final String RESOURCE = "resource";
    private static final String RESOURCE_NETHER = "resource_nether";

    @TempDir
    Path directory;

    private CityTestSupport support;
    private WaystationRegistry registry;
    private WaystationService waystations;
    private MiningClaimRegistry mining;
    private UUID mayor;
    private City city;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        registry = new WaystationRegistry(support.daos.waystations());
        mining = new MiningClaimRegistry(support.daos.miningClaims(),
                CityTestSupport.quietLogger());
        await(mining.loadAll());
        waystations = new WaystationService(support.db, support.daos, registry,
                support.treasury, mining, support.configs, Scheduler.direct());

        mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);
        fundTreasury("10000000.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.join();
    }

    private void fundTreasury(String amount) {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal(amount)));
        city.setTreasury(new BigDecimal(amount));
    }

    private Result<Waystation> create(String world, int chunkX, int chunkZ) {
        return await(waystations.create(mayor, city, world, chunkX, chunkZ,
                chunkX * 16.0 + 8, 64, chunkZ * 16.0 + 8, 0f, 0f));
    }

    private static String reasonOf(Result<?> result) {
        return result instanceof Result.Failure<?> failure ? failure.reason() : "SUCCESS";
    }

    // ==================================================================================
    // Founding, SPEC 39.10
    // ==================================================================================

    @Nested
    @DisplayName("founding a waystation")
    class Founding {

        @Test
        @DisplayName("one goes up in a resource world and the treasury pays for it")
        void founds() {
            BigDecimal before = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();

            Result<Waystation> founded = create(RESOURCE, 100, 100);

            assertTrue(founded.isSuccess(), reasonOf(founded));
            assertEquals(1, registry.of(city.id()).size());
            assertEquals(1, registry.chunkCount(founded.orElseThrow().id()));
            assertTrue(await(support.daos.cities().findById(city.id())).orElseThrow()
                    .treasury().compareTo(before) < 0, "the treasury paid");
        }

        @Test
        @DisplayName("SPEC 39.10: only in a resource world")
        void resourceWorldsOnly() {
            assertEquals("WRONG_WORLD", reasonOf(create("world", 100, 100)));
            assertEquals("WRONG_WORLD", reasonOf(create("world_nether", 100, 100)));
            assertTrue(create(RESOURCE_NETHER, 100, 100).isSuccess(),
                    "but the resource nether is one");
        }

        @Test
        @DisplayName("SPEC 39.10: one per city per world, and the second is refused")
        void onePerWorld() {
            assertTrue(create(RESOURCE, 100, 100).isSuccess());

            assertEquals("WAYSTATION_EXISTS", reasonOf(create(RESOURCE, 500, 500)));
            assertEquals(1, registry.of(city.id()).size());
        }

        @Test
        @DisplayName("but one in each resource world is two, which is the intended maximum")
        void oneInEachWorld() {
            assertTrue(create(RESOURCE, 100, 100).isSuccess());
            assertTrue(create(RESOURCE_NETHER, 100, 100).isSuccess());

            assertEquals(2, registry.of(city.id()).size());
        }

        @Test
        @DisplayName("a chunk another waystation holds is refused")
        void chunkAlreadyHeld() {
            assertTrue(create(RESOURCE, 100, 100).isSuccess());

            UUID otherMayor = support.givenEligiblePlayer("Remus");
            City other = support.givenCity(otherMayor, "Alba", 50, 50);
            await(support.daos.cities().updateTreasury(other.id(), new BigDecimal("10000000")));
            other.setTreasury(new BigDecimal("10000000"));

            assertEquals("CHUNK_CLAIMED", reasonOf(await(waystations.create(otherMayor, other,
                    RESOURCE, 100, 100, 1608, 64, 1608, 0f, 0f))));
        }

        @Test
        @DisplayName("a chunk somebody's mining claim holds is refused")
        void miningClaimHere() {
            // SPEC 39.10: the two "coexist and do not overlap". A player may hold both, but
            // not on the same ground.
            MiningClaimService mines = new MiningClaimService(support.db,
                    support.daos.miningClaims(), mining, support.economy,
                    new dev.civitas.core.world.WorldRegistry(support.configs),
                    support.configs, Scheduler.direct());
            UUID miner = support.givenEligiblePlayer("Miner");
            await(support.economy.give(miner, new BigDecimal("100000"),
                    dev.civitas.core.economy.TransactionType.ADMIN_GIVE, null, null));

            assertTrue(await(mines.claim(miner, RESOURCE, 200, 200, 1,
                    System.currentTimeMillis())).isSuccess());

            assertEquals("MINING_CLAIM_HERE", reasonOf(create(RESOURCE, 200, 200)));
        }

        @Test
        @DisplayName("a member without OUTPOST_MANAGE cannot found one")
        void needsPermission() {
            UUID citizen = support.givenMember(city, "Titus");

            assertEquals("NO_CITY_PERMISSION", reasonOf(await(waystations.create(citizen, city,
                    RESOURCE, 100, 100, 1608, 64, 1608, 0f, 0f))));
        }

        @Test
        @DisplayName("switching the feature off refuses everything")
        void disabled() {
            support.configs.get(ConfigFile.CITIES).set("waystations.enabled", false);

            assertEquals("WAYSTATIONS_DISABLED", reasonOf(create(RESOURCE, 100, 100)));
        }
    }

    // ==================================================================================
    // The two pools, SPEC 39.10
    // ==================================================================================

    @Nested
    @DisplayName("the waystation pool is separate from the outpost pool, SPEC 39.10")
    class SeparatePools {

        @Test
        @DisplayName("a city at its outpost cap may still found both waystations")
        void outpostCapDoesNotBlockAWaystation() {
            // "Separate pool from the 2 to 6 outpost limit." The obvious way to get this wrong
            // is to count them together, and a city that had spent its outposts would then
            // quietly be unable to reach the resource worlds at all.
            dev.civitas.core.outpost.OutpostRegistry outpostRegistry =
                    new dev.civitas.core.outpost.OutpostRegistry(support.daos.outposts());
            dev.civitas.core.outpost.OutpostService outposts =
                    new dev.civitas.core.outpost.OutpostService(support.db, support.daos,
                            support.registry, support.claimRegistry, support.claims,
                            outpostRegistry, support.treasury, support.configs,
                            Scheduler.direct());

            assertTrue(await(outposts.create(mayor, city, "North", "world", 40, 0,
                    648, 64, 8, 0f, 0f)).isSuccess());
            assertTrue(await(outposts.create(mayor, city, "South", "world", 0, 40,
                    8, 64, 648, 0f, 0f)).isSuccess());
            assertEquals(outposts.maxOutposts(city), outpostRegistry.countOf(city.id()),
                    "the city is at its outpost cap");

            assertTrue(create(RESOURCE, 100, 100).isSuccess(),
                    "which must not stop it founding a waystation");
            assertTrue(create(RESOURCE_NETHER, 100, 100).isSuccess());
        }
    }

    // ==================================================================================
    // The second chunk, SPEC 39.10
    // ==================================================================================

    @Nested
    @DisplayName("growing to two chunks")
    class Growing {

        private Waystation waystation;

        @BeforeEach
        void setUp() {
            waystation = create(RESOURCE, 100, 100).orElseThrow();
        }

        private Result<WaystationChunkRow> expand(int chunkX, int chunkZ) {
            return await(waystations.expand(mayor, city, RESOURCE, chunkX, chunkZ));
        }

        @Test
        @DisplayName("a bordering chunk joins it")
        void bordersJoin() {
            assertTrue(expand(101, 100).isSuccess());

            assertEquals(2, registry.chunkCount(waystation.id()));
            assertEquals(1, registry.of(city.id()).size(), "still one waystation, of two chunks");
        }

        @Test
        @DisplayName("SPEC 39.10: edge-connected, so a corner does not count")
        void cornersDoNot() {
            assertEquals("NOT_ADJACENT", reasonOf(expand(101, 101)));
        }

        @Test
        @DisplayName("two chunks is the maximum")
        void twoIsTheCap() {
            assertTrue(expand(101, 100).isSuccess());

            assertEquals("WAYSTATION_FULL", reasonOf(expand(102, 100)));
            assertEquals(2, registry.chunkCount(waystation.id()));
        }

        @Test
        @DisplayName("the second chunk costs more than the first, SPEC 39.10")
        void secondCostsMore() {
            // 90,000 against 60,000 before distance.
            assertTrue(waystations.costs().chunkCost(2, 0)
                    .compareTo(waystations.costs().chunkCost(1, 0)) > 0);
        }

        @Test
        @DisplayName("expanding where there is no waystation says so")
        void nothingToExpand() {
            assertEquals("NO_WAYSTATION", reasonOf(
                    await(waystations.expand(mayor, city, RESOURCE_NETHER, 100, 100))));
        }
    }

    // ==================================================================================
    // Upkeep and removal
    // ==================================================================================

    @Nested
    @DisplayName("upkeep and removal")
    class Running {

        @Test
        @DisplayName("upkeep sums every chunk of every waystation")
        void upkeepSums() {
            Waystation first = create(RESOURCE, 100, 100).orElseThrow();
            Waystation second = create(RESOURCE_NETHER, 300, 300).orElseThrow();

            assertEquals(0, waystations.upkeepFor(first).add(waystations.upkeepFor(second))
                    .compareTo(waystations.upkeepFor(city)));
        }

        @Test
        @DisplayName("a second chunk doubles that waystation's upkeep")
        void upkeepScalesWithChunks() {
            Waystation waystation = create(RESOURCE, 100, 100).orElseThrow();
            BigDecimal one = waystations.upkeepFor(waystation);

            assertTrue(await(waystations.expand(mayor, city, RESOURCE, 101, 100)).isSuccess());

            assertEquals(0, one.multiply(new BigDecimal("2"))
                    .compareTo(waystations.upkeepFor(waystation)));
        }

        @Test
        @DisplayName("a city with none owes nothing")
        void noneOwesNothing() {
            assertEquals(0, BigDecimal.ZERO.compareTo(waystations.upkeepFor(city)));
        }

        @Test
        @DisplayName("deleting refunds half of every chunk and frees the world")
        void deleteRefundsAndFrees() {
            Waystation waystation = create(RESOURCE, 100, 100).orElseThrow();
            assertTrue(await(waystations.expand(mayor, city, RESOURCE, 101, 100)).isSuccess());

            BigDecimal expected = registry.chunksOf(waystation.id()).stream()
                    .map(chunk -> waystations.costs().refundFor(chunk.costPaid()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal before = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();

            assertTrue(await(waystations.delete(mayor, city, RESOURCE)).isSuccess());

            assertEquals(0, before.add(expected).compareTo(
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury()));
            assertTrue(registry.of(city.id(), RESOURCE).isEmpty());
            assertFalse(registry.isClaimed(RESOURCE, 100, 100),
                    "and both chunks are released, not just the founding one");
            assertFalse(registry.isClaimed(RESOURCE, 101, 100));
        }

        @Test
        @DisplayName("the world is free to be founded in again afterwards")
        void freedWorldCanBeRefounded() {
            create(RESOURCE, 100, 100);
            assertTrue(await(waystations.delete(mayor, city, RESOURCE)).isSuccess());

            assertTrue(create(RESOURCE, 400, 400).isSuccess());
        }

        @Test
        @DisplayName("a disbanded city takes its waystations with it")
        void disbandRemovesThem() {
            create(RESOURCE, 100, 100);
            create(RESOURCE_NETHER, 100, 100);

            assertEquals(2, await(waystations.removeCity(city.id())));

            assertTrue(registry.of(city.id()).isEmpty());
            assertFalse(registry.isClaimed(RESOURCE, 100, 100));
        }
    }

    // ==================================================================================
    // The registry
    // ==================================================================================

    @Nested
    @DisplayName("the registry")
    class Cache {

        @Test
        @DisplayName("it reloads from storage, chunks included")
        void reloads() {
            Waystation waystation = create(RESOURCE, 100, 100).orElseThrow();
            assertTrue(await(waystations.expand(mayor, city, RESOURCE, 101, 100)).isSuccess());

            WaystationRegistry fresh = new WaystationRegistry(support.daos.waystations());
            assertEquals(1, await(fresh.loadAll()));

            assertEquals(2, fresh.chunkCount(waystation.id()));
            assertTrue(fresh.at(RESOURCE, 101, 100).isPresent());
            assertEquals(city.id(), fresh.at(RESOURCE, 100, 100).orElseThrow().cityId());
        }

        @Test
        @DisplayName("a chunk nobody holds is nobody's")
        void unheldChunk() {
            assertFalse(registry.isClaimed(RESOURCE, 900, 900));
            assertTrue(registry.at(RESOURCE, 900, 900).isEmpty());
        }
    }
}
