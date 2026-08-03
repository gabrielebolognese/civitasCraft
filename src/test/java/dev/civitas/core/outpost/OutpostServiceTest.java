package dev.civitas.core.outpost;

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
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRank;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ClaimType;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Outposts, SPEC 7.
 *
 * <p>One test per rule in the SPEC 7.2 table and per case in SPEC 7.4, because every one of
 * those rules exists to stop an outpost being used as something other than an outpost, and a
 * rule that is not tested is a rule somebody will find the hole in.
 */
class OutpostServiceTest {

    private static final String WORLD = "world";

    @TempDir
    Path directory;

    private CityTestSupport support;
    private OutpostRegistry registry;
    private OutpostService outposts;
    private UUID mayor;
    private City city;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        registry = new OutpostRegistry(support.daos.outposts());
        outposts = new OutpostService(support.db, support.daos, support.registry,
                support.claimRegistry, support.claims, registry, support.treasury,
                support.configs, Scheduler.direct());

        mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);
        fundTreasury("10000000.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private void fundTreasury(String amount) {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal(amount)));
        city.setTreasury(new BigDecimal(amount));
    }

    private Result<Outpost> create(String name, int chunkX, int chunkZ) {
        return await(outposts.create(mayor, city, name, WORLD, chunkX, chunkZ,
                chunkX * 16 + 8.5, 64.0, chunkZ * 16 + 8.5, 0f, 0f));
    }

    private Outpost given(String name, int chunkX, int chunkZ) {
        Result<Outpost> created = create(name, chunkX, chunkZ);
        assertTrue(created.isSuccess(), reasonOf(created));
        return created.orElseThrow();
    }

    // ==================================================================================
    // SPEC 7.2, the rules table
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 7.2 rules")
    class Rules {

        @Test
        @DisplayName("an outpost is one chunk, and the claim knows it is an outpost")
        void oneChunk() {
            Outpost outpost = given("North", 40, 0);

            Claim claim = outposts.claimOf(outpost).orElseThrow();
            assertEquals(ClaimType.OUTPOST, claim.type());
            assertEquals(outpost.id(), claim.outpostId());
            assertEquals(2, support.claimRegistry.claimsOf(city.id()).size(),
                    "the core and the outpost, and nothing in between");
        }

        @Test
        @DisplayName("SPEC 7.2: at least 32 chunks from the city's own land")
        void minimumDistanceFromOwnCity() {
            Result<Outpost> tooClose = create("TooClose", 31, 0);
            assertEquals("TOO_CLOSE_TO_OWN_CITY", reasonOf(tooClose));

            assertTrue(create("FarEnough", 32, 0).isSuccess(), "exactly 32 is allowed");
        }

        @Test
        @DisplayName("SPEC 7.2: at least 8 chunks from another city's claims")
        void minimumDistanceFromOtherCity() {
            UUID other = support.givenEligiblePlayer("Numa");
            City alba = support.givenCity(other, "Alba", 100, 0);
            assertTrue(alba.id() != city.id());

            assertEquals("TOO_CLOSE_TO_OTHER_CITY", reasonOf(create("Near", 105, 0)));
            assertTrue(create("Clear", 108, 0).isSuccess(), "exactly 8 away is allowed");
        }

        @Test
        @DisplayName("an existing outpost does not push the next one 32 chunks away")
        void outpostsDoNotBlockEachOther() {
            given("First", 40, 0);

            // SPEC 7.2 measures from the city, and two outposts side by side break none of
            // the rules the distance exists to protect.
            assertTrue(create("Second", 41, 0).isSuccess());
        }

        @Test
        @DisplayName("SPEC 7.2: 25,000 flat plus three times the next normal chunk")
        void creationCost() {
            BigDecimal expected = new BigDecimal("25000").add(
                    support.claims.costs().price(
                                    support.claimRegistry.claimsOf(city.id()).size() + 1, 0,
                                    support.claims.activeMemberCount(city),
                                    city.ageMillis(System.currentTimeMillis()))
                            .total()
                            .multiply(new BigDecimal("3")));

            assertEquals(0, dev.civitas.core.economy.Money.floor(expected)
                    .compareTo(outposts.creationCost(city)));
        }

        @Test
        @DisplayName("the price actually leaves the treasury")
        void treasuryIsCharged() {
            BigDecimal cost = outposts.creationCost(city);
            BigDecimal before = city.treasury();

            given("North", 40, 0);

            assertEquals(0, before.subtract(cost).compareTo(
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury()));
        }

        @Test
        @DisplayName("SPEC 7.2: two outposts to start with, and no more")
        void slotLimit() {
            given("One", 40, 0);
            given("Two", 40, 40);

            Result<Outpost> third = create("Three", 40, 80);
            assertEquals("OUTPOST_LIMIT", reasonOf(third));
            assertEquals("2", ((Result.Failure<Outpost>) third).placeholders().get("limit"));
        }

        @Test
        @DisplayName("the slot limit is a config key, since SPEC 5.7 raises it to six")
        void slotLimitIsConfigurable() {
            support.configs.get(ConfigFile.CITIES).set("outposts.base-max", 4);
            given("One", 40, 0);
            given("Two", 40, 40);

            assertTrue(create("Three", 40, 80).isSuccess());
        }

        @Test
        @DisplayName("a chunk somebody already owns is refused")
        void chunkAlreadyClaimed() {
            given("North", 40, 0);

            assertEquals("CHUNK_CLAIMED", reasonOf(create("Overlap", 40, 0)));
        }

        @Test
        @DisplayName("OUTPOST_MANAGE is what gates it")
        void permissionGate() {
            UUID member = support.givenMember(city, "Titus");
            CityRank citizen = city.rankByName("Citizen").orElseThrow();
            await(support.ranks.assign(mayor, city, member, citizen));

            assertEquals("NO_CITY_PERMISSION", reasonOf(await(outposts.create(member, city,
                    "Sneaky", WORLD, 40, 0, 648.5, 64.0, 8.5, 0f, 0f))));

            await(support.ranks.setPermission(mayor, city, citizen,
                    CityPermission.OUTPOST_MANAGE, true));
            assertTrue(await(outposts.create(member, city, "Allowed", WORLD, 40, 0,
                    648.5, 64.0, 8.5, 0f, 0f)).isSuccess());
        }

        @Test
        @DisplayName("a delinquent or frozen city cannot found one")
        void blockedStates() {
            city.setDelinquentSince(System.currentTimeMillis());
            assertEquals("CITY_DELINQUENT", reasonOf(create("North", 40, 0)));

            city.setDelinquentSince(null);
            city.setFrozen(true);
            assertEquals("CITY_FROZEN", reasonOf(create("North", 40, 0)));
        }
    }

    // ==================================================================================
    // Names
    // ==================================================================================

    @Nested
    @DisplayName("Names")
    class Names {

        @Test
        @DisplayName("two outposts of one city cannot share a name")
        void namesAreUnique() {
            given("North", 40, 0);

            assertEquals("NAME_TAKEN", reasonOf(create("north", 40, 40)),
                    "and the comparison is case-insensitive, because players type them");
        }

        @Test
        @DisplayName("a name with spaces or punctuation is refused")
        void namesAreValidated() {
            assertEquals("NAME_INVALID", reasonOf(create("North Gate", 40, 0)));
            assertEquals("NAME_INVALID", reasonOf(create("north!", 40, 0)));
            assertEquals("NAME_MISSING", reasonOf(create("  ", 40, 0)));
        }

        @Test
        @DisplayName("renaming works, and still refuses a name in use")
        void rename() {
            Outpost north = given("North", 40, 0);
            given("South", 40, 40);

            assertTrue(await(outposts.rename(mayor, city, north, "Northgate")).isSuccess());
            assertEquals("Northgate", registry.byId(north.id()).orElseThrow().name());

            assertEquals("NAME_TAKEN",
                    reasonOf(await(outposts.rename(mayor, city, north, "South"))));
        }
    }

    // ==================================================================================
    // The warp point
    // ==================================================================================

    @Nested
    @DisplayName("The warp point")
    class Warp {

        @Test
        @DisplayName("setwarp moves it, inside the outpost's own chunk")
        void insideTheChunk() {
            Outpost outpost = given("North", 40, 0);

            assertTrue(await(outposts.setWarp(mayor, city, outpost, WORLD,
                    40 * 16 + 2.0, 70.0, 2.0, 90f, 0f)).isSuccess());
            assertEquals(70.0, registry.byId(outpost.id()).orElseThrow().warpY());
        }

        @Test
        @DisplayName("a warp point outside the chunk is refused")
        void outsideTheChunk() {
            Outpost outpost = given("North", 40, 0);

            // Otherwise setwarp is a teleport-anywhere command that happens to cost 100 C.
            assertEquals("OUTSIDE_OUTPOST", reasonOf(await(outposts.setWarp(mayor, city,
                    outpost, WORLD, 0.0, 64.0, 0.0, 0f, 0f))));
        }
    }

    // ==================================================================================
    // Deleting
    // ==================================================================================

    @Nested
    @DisplayName("Deleting")
    class Deleting {

        @Test
        @DisplayName("SPEC 7.3: deleting refunds half of what it cost, to the treasury")
        void refund() {
            BigDecimal cost = outposts.creationCost(city);
            Outpost outpost = given("North", 40, 0);
            BigDecimal afterCreate = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();

            assertTrue(await(outposts.delete(mayor, city, outpost)).isSuccess());

            BigDecimal expected = afterCreate.add(
                    dev.civitas.core.economy.Money.percentOf(cost, 50));
            assertEquals(0, expected.compareTo(await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury()));
        }

        @Test
        @DisplayName("the chunk goes with it, and the slot is freed")
        void chunkAndSlot() {
            Outpost outpost = given("North", 40, 0);
            assertEquals(1, registry.countOf(city.id()));

            await(outposts.delete(mayor, city, outpost));

            assertEquals(0, registry.countOf(city.id()));
            assertTrue(support.claimRegistry.at(WORLD, 40, 0).isEmpty());
        }
    }

    // ==================================================================================
    // SPEC 7.4: growing into your own outpost
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 7.4 conversion")
    class Conversion {

        @Test
        @DisplayName("an outpost the city grows up to becomes an ordinary claim")
        void convertsWhenAdjacent() {
            Outpost outpost = given("North", 32, 0);

            // Claim the chunk next to it. In the real plugin the claim listener fires this;
            // here it is called directly, which is the unit under test.
            support.claimRegistry.put(new Claim(9_000L, city.id(), WORLD, 31, 0,
                    System.currentTimeMillis(), mayor, BigDecimal.ZERO, ClaimType.NORMAL, null));

            List<Outpost> converted = await(outposts.convertAdjacent(city));

            assertEquals(List.of(outpost), converted);
            assertEquals(0, registry.countOf(city.id()), "the slot is free again");
            assertEquals(ClaimType.NORMAL,
                    support.claimRegistry.at(WORLD, 32, 0).orElseThrow().type());
        }

        @Test
        @DisplayName("SPEC 7.4: nothing is refunded for the conversion")
        void noRefund() {
            given("North", 32, 0);
            BigDecimal before = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();

            support.claimRegistry.put(new Claim(9_001L, city.id(), WORLD, 31, 0,
                    System.currentTimeMillis(), mayor, BigDecimal.ZERO, ClaimType.NORMAL, null));
            await(outposts.convertAdjacent(city));

            assertEquals(0, before.compareTo(await(support.daos.cities().findById(city.id()))
                            .orElseThrow().treasury()),
                    "growing to your own outpost must not be cheaper than growing");
        }

        @Test
        @DisplayName("a diagonal neighbour is not adjacency, here or anywhere else")
        void diagonalIsNotAdjacent() {
            given("North", 32, 0);

            support.claimRegistry.put(new Claim(9_002L, city.id(), WORLD, 31, 1,
                    System.currentTimeMillis(), mayor, BigDecimal.ZERO, ClaimType.NORMAL, null));

            assertTrue(await(outposts.convertAdjacent(city)).isEmpty());
            assertEquals(1, registry.countOf(city.id()));
        }

        @Test
        @DisplayName("another city's land next door converts nothing")
        void foreignNeighbourDoesNotConvert() {
            given("North", 40, 0);

            support.claimRegistry.put(new Claim(9_003L, city.id() + 1000, WORLD, 39, 0,
                    System.currentTimeMillis(), mayor, BigDecimal.ZERO, ClaimType.NORMAL, null));

            assertTrue(await(outposts.convertAdjacent(city)).isEmpty());
        }

        @Test
        @DisplayName("two outposts touching each other stay outposts")
        void outpostsDoNotConvertEachOther() {
            given("First", 40, 0);
            given("Second", 41, 0);

            assertTrue(await(outposts.convertAdjacent(city)).isEmpty(),
                    "the city body is what absorbs an outpost, not another outpost");
            assertEquals(2, registry.countOf(city.id()));
        }

        @Test
        @DisplayName("an outpost in another world is untouched by growth at home")
        void otherWorldIsSafe() {
            Outpost outpost = given("North", 40, 0);

            // Same coordinates, different world: adjacency is per-world everywhere in the
            // plugin, and this is one more place it has to be.
            support.claimRegistry.put(new Claim(9_004L, city.id(), "nether", 39, 0,
                    System.currentTimeMillis(), mayor, BigDecimal.ZERO, ClaimType.NORMAL, null));

            assertTrue(await(outposts.convertAdjacent(city)).isEmpty());
            assertTrue(registry.byId(outpost.id()).isPresent());
        }
    }

    // ==================================================================================
    // Upkeep, SPEC 7.2
    // ==================================================================================

    @Test
    @DisplayName("SPEC 7.2: each outpost adds its flat daily fee to the city's upkeep")
    void upkeep() {
        BigDecimal landOnly = support.upkeep.dailyUpkeep(
                support.claims.landValueOf(city.id()), 0,
                dev.civitas.storage.SqlDialect.zero(), 0);

        given("North", 40, 0);

        BigDecimal withOne = support.upkeep.dailyUpkeep(
                support.claims.landValueOf(city.id()), registry.countOf(city.id()),
                dev.civitas.storage.SqlDialect.zero(), 0);

        assertTrue(withOne.subtract(landOnly).compareTo(new BigDecimal("2000")) >= 0,
                "at least the flat 2,000 a day, plus the land the outpost itself is worth");
    }

    @Test
    @DisplayName("the registry reloads from storage")
    void reload() {
        given("North", 40, 0);
        given("South", 40, 40);

        assertEquals(2, await(registry.loadAll()));
        assertTrue(registry.byName(city.id(), "north").isPresent(),
                "and names still resolve case-insensitively after a restart");
    }

    @Test
    @DisplayName("a disbanded city's outposts are forgotten")
    void forgetCity() {
        given("North", 40, 0);
        registry.forgetCity(city.id());

        assertEquals(0, registry.countOf(city.id()));
        assertFalse(registry.total() > 0);
    }
}
