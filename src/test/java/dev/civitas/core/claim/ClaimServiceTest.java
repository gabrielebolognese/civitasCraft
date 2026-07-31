package dev.civitas.core.claim;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.civitas.api.event.ChunkClaimEvent;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRank;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.util.EventBus;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 18.2: "Claim, unclaim, and contiguity rejection", against a real database.
 *
 * <p>Also covers the SPEC 17.2 claim edge cases that belong to M3.
 */
class ClaimServiceTest {

    private static final String WORLD = "world";

    @TempDir
    Path directory;

    private CityTestSupport support;
    private UUID mayor;
    private City city;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);
        support.refreshPricing();
        fundTreasury("10000000.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private void fundTreasury(String amount) {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal(amount)));
        city.setTreasury(new BigDecimal(amount));
    }

    private Result<Claim> claim(int chunkX, int chunkZ) {
        return await(support.claims.claim(mayor, city, WORLD, chunkX, chunkZ));
    }

    private Result<Claim> unclaim(int chunkX, int chunkZ) {
        return await(support.claims.unclaim(mayor, city, WORLD, chunkX, chunkZ));
    }

    /** Buys a horizontal run east from the core, which is already at 0,0. */
    private void givenLine(int length) {
        for (int x = 1; x <= length; x++) {
            assertTrue(claim(x, 0).isSuccess(), "fixture claim " + x + " failed");
        }
    }

    // ==================================================================================
    // Claiming, SPEC 6.3
    // ==================================================================================

    @Nested
    @DisplayName("Claiming")
    class Claiming {

        @Test
        @DisplayName("a chunk beside the core is bought, cached and charged to the treasury")
        void happyPath() {
            BigDecimal before = city.treasury();

            Result<Claim> result = claim(1, 0);

            assertTrue(result.isSuccess(), reasonOf(result));
            Claim claim = result.orElseThrow();
            assertEquals(ClaimType.NORMAL, claim.type());
            assertEquals(city.id(), claim.cityId());

            assertTrue(support.claimRegistry.at(WORLD, 1, 0).isPresent(), "not in the cache");
            assertEquals(2, support.claimRegistry.countOf(city.id()), "core plus one");
            assertEquals(0, before.subtract(claim.costPaid()).compareTo(city.treasury()));
        }

        @Test
        @DisplayName("the second chunk costs the flat starter price, less the young-city discount")
        void starterPrice() {
            // SPEC 6.2 lists 500 C flat for the first eight chunks, and SPEC 15.1 takes a
            // quarter off while the city is under 14 days old. A city founded a moment ago
            // is squarely inside that window, so 375 is the price a real founder pays.
            assertEquals(0, new BigDecimal("375.00").compareTo(claim(1, 0).orElseThrow().costPaid()));
        }

        @Test
        @DisplayName("the price climbs with each chunk, reaching the curve at the ninth")
        void priceClimbs() {
            // Every chunk here is one step from the core, so the SPEC 6.2 distance
            // multiplier stays at 1 and only the chunk index moves the price. A straight
            // line would start charging for distance at chunk 5 and muddle the two.
            int[][] ringAroundCore = {{1, 0}, {0, 1}, {1, 1}, {-1, 0}, {0, -1}, {-1, -1}};
            for (int[] chunk : ringAroundCore) {
                assertTrue(claim(chunk[0], chunk[1]).isSuccess(),
                        "fixture claim " + chunk[0] + "," + chunk[1] + " failed");
            }

            // The core counts toward the index, so core plus six is seven chunks and the
            // next purchase is the eighth: the last one inside the flat starter band, at
            // 500 less the young-city quarter.
            assertEquals(7, support.claimRegistry.countOf(city.id()));
            assertEquals(0, new BigDecimal("375.00").compareTo(
                    claim(1, -1).orElseThrow().costPaid()));

            // The ninth leaves the band: 6,235 on the curve, times the same 0.75.
            BigDecimal ninth = claim(-1, 1).orElseThrow().costPaid();
            assertTrue(ninth.compareTo(new BigDecimal("4600")) > 0,
                    "the ninth chunk should be on the curve, but cost " + ninth);
        }

        @Test
        @DisplayName("a ledger entry records every purchase, SPEC 1.5")
        void purchaseIsLedgered() {
            claim(1, 0);

            List<LedgerRow> entries = await(support.daos.ledger()
                    .findByType(TransactionType.CHUNK_CLAIM.name(), 0L, 10));
            assertEquals(1, entries.size());
            assertEquals(0, new BigDecimal("-375.00").compareTo(entries.get(0).amount()));
            assertEquals(city.id(), entries.get(0).cityId());
        }

        @Test
        @DisplayName("a chunk touching only at a corner is refused, SPEC 6.1")
        void diagonalIsRefused() {
            assertEquals("NOT_ADJACENT", reasonOf(claim(1, 1)));
            assertEquals("NOT_ADJACENT", reasonOf(claim(5, 5)));
        }

        @Test
        @DisplayName("a chunk another city owns is refused, SPEC 17.2 case 15")
        void takenChunkIsRefused() {
            UUID other = support.givenEligiblePlayer("Remus");
            City ostia = support.givenCity(other, "Ostia", 20, 0);
            await(support.daos.cities().updateTreasury(ostia.id(), new BigDecimal("100000.00")));
            ostia.setTreasury(new BigDecimal("100000.00"));
            assertTrue(await(support.claims.claim(other, ostia, WORLD, 19, 0)).isSuccess());

            // Roma walks east until it meets Ostia's land. Ostia holds chunks 19 and 20, so
            // with a buffer of 5 the last chunk Roma may take is 13.
            givenLine(13);
            assertEquals("TOO_CLOSE", reasonOf(claim(14, 0)));

            // With no buffer configured, the chunk itself is what refuses.
            support.configs.get(dev.civitas.config.ConfigFile.CITIES).set("claims.buffer-chunks", 0);
            for (int x = 14; x <= 18; x++) {
                assertTrue(claim(x, 0).isSuccess(), "fixture claim " + x + " failed");
            }
            assertEquals("CHUNK_CLAIMED", reasonOf(claim(19, 0)));
        }

        @Test
        @DisplayName("a chunk the city already owns reports that, not a generic refusal")
        void ownChunkIsRefusedClearly() {
            claim(1, 0);

            assertEquals("ALREADY_OWNED", reasonOf(claim(1, 0)));
        }

        @Test
        @DisplayName("a member without CLAIM cannot buy land")
        void permissionIsRequired() {
            UUID member = support.givenMember(city, "Titus");
            support.refreshPricing();

            assertEquals("NO_CITY_PERMISSION",
                    reasonOf(await(support.claims.claim(member, city, WORLD, 1, 0))));

            CityRank architect = city.rankByName("Architect").orElseThrow();
            assertTrue(architect.has(CityPermission.CLAIM));
            assertTrue(await(support.ranks.assign(mayor, city, member, architect)).isSuccess());
            assertTrue(await(support.claims.claim(member, city, WORLD, 1, 0)).isSuccess());
        }

        @Test
        @DisplayName("a treasury that cannot pay is refused and charged nothing")
        void insufficientTreasury() {
            fundTreasury("100.00");

            Result<Claim> result = claim(1, 0);

            assertEquals("TREASURY_SHORT", reasonOf(result));
            assertEquals(0, new BigDecimal("100.00").compareTo(
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury()));
            assertEquals(1, support.claimRegistry.countOf(city.id()), "only the core");
        }

        @Test
        @DisplayName("SPEC 17.2 case 13: a claim that exactly empties the treasury is allowed")
        void case13ExactlyEmptiesTreasury() {
            fundTreasury("375.00");

            assertTrue(claim(1, 0).isSuccess());
            assertEquals(0, BigDecimal.ZERO.compareTo(city.treasury()));
        }

        @Test
        @DisplayName("a frozen or delinquent city cannot buy land")
        void frozenAndDelinquent() {
            city.setFrozen(true);
            assertEquals("CITY_FROZEN", reasonOf(claim(1, 0)));
            city.setFrozen(false);

            city.setDelinquentSince(System.currentTimeMillis());
            assertEquals("CITY_DELINQUENT", reasonOf(claim(1, 0)));
            city.setDelinquentSince(null);

            assertTrue(claim(1, 0).isSuccess());
        }

        @Test
        @DisplayName("SPEC 6.3 precondition 5: another city's buffer is respected")
        void bufferIsRespected() {
            UUID other = support.givenEligiblePlayer("Remus");
            support.givenCity(other, "Ostia", 20, 0);

            // cities.yml sets claims.buffer-chunks to 5 and Ostia's core is at chunk 20, so
            // chunk 15 is the first one inside its buffer and chunk 14 is the last one free.
            givenLine(14);
            assertEquals("TOO_CLOSE", reasonOf(claim(15, 0)));
        }

        @Test
        @DisplayName("SPEC 17.2 case 20: a chunk beyond the key's range is refused, not aliased")
        void case20OutOfRange() {
            assertEquals("OUT_OF_RANGE",
                    reasonOf(await(support.claims.claim(mayor, city, WORLD,
                            ChunkKey.MAX_COORD + 1, 0))));
        }

        @Test
        @DisplayName("SPEC 17.2 case 23: the same coordinates in another world are separate land")
        void case23WorldIsPartOfTheKey() {
            claim(1, 0);

            // A city with no anchor in a world cannot start one by claiming; that is what
            // outposts are for. The point here is that the chunk is not treated as taken.
            assertEquals("NO_ANCHOR_IN_WORLD",
                    reasonOf(await(support.claims.claim(mayor, city, "world_nether", 1, 0))));
            assertTrue(support.claimRegistry.at("world_nether", 1, 0).isEmpty());
        }

        @Test
        @DisplayName("a cancelled ChunkClaimEvent buys nothing and charges nothing")
        void cancelledEvent() {
            // Only the claim event is cancelled; founding a city must still work, or the
            // test would prove nothing about claiming.
            try (CityTestSupport cancelling = CityTestSupport.open(directory.resolve("cancel"),
                    new CancelOnly(ChunkClaimEvent.class))) {
                UUID founder = cancelling.givenEligiblePlayer("Romulus");
                City roma = cancelling.givenCity(founder, "Roma", 0, 0);
                cancelling.refreshPricing();
                await(cancelling.daos.cities().updateTreasury(roma.id(), new BigDecimal("50000.00")));
                roma.setTreasury(new BigDecimal("50000.00"));

                Result<Claim> result =
                        await(cancelling.claims.claim(founder, roma, WORLD, 1, 0));

                assertEquals("CANCELLED", reasonOf(result));
                assertEquals(0, new BigDecimal("50000.00").compareTo(
                        await(cancelling.daos.cities().findById(roma.id())).orElseThrow().treasury()));
                assertEquals(1L, await(cancelling.daos.claims().count()), "only the core");
            }
        }
    }

    /** Cancels one event type and lets everything else through. */
    private record CancelOnly(Class<?> cancelled) implements EventBus {
        @Override
        public boolean fire(dev.civitas.api.event.CivitasEvent event) {
            return !cancelled.isInstance(event);
        }
    }

    // ==================================================================================
    // Radius, SPEC 6.3 and 17.2 case 17
    // ==================================================================================

    @Nested
    @DisplayName("Radius claiming")
    class Radius {

        @Test
        @DisplayName("a 3x3 square buys nine chunks, priced as nine successive purchases")
        void threeByThree() {
            // Nothing of this square can reach the core at 0,0.
            Result<List<Claim>> result =
                    await(support.claims.claimRadius(mayor, city, WORLD, 20, 0, 3));
            assertEquals("NOT_ADJACENT", reasonOf(result));

            // Now the square's near edge touches the city, even though its centre does not.
            givenLine(4);
            Result<List<Claim>> adjacent =
                    await(support.claims.claimRadius(mayor, city, WORLD, 6, 0, 3));

            assertTrue(adjacent.isSuccess(), reasonOf(adjacent));
            assertEquals(9, adjacent.orElseThrow().size());

            List<BigDecimal> costs = adjacent.orElseThrow().stream().map(Claim::costPaid).toList();
            assertTrue(costs.get(8).compareTo(costs.get(0)) > 0,
                    "a square must be priced as successive chunks, not nine copies of the first");
        }

        @Test
        @DisplayName("SPEC 17.2 case 17: a square with an invalid chunk buys none of it")
        void case17IsAtomic() {
            givenLine(4);

            UUID other = support.givenEligiblePlayer("Remus");
            City ostia = support.givenCity(other, "Ostia", 40, 0);
            await(support.daos.cities().updateTreasury(ostia.id(), new BigDecimal("100000.00")));
            ostia.setTreasury(new BigDecimal("100000.00"));
            assertTrue(await(support.claims.claim(other, ostia, WORLD, 39, 0)).isSuccess());

            long before = await(support.daos.claims().count());
            BigDecimal treasuryBefore = city.treasury();

            // One chunk of this square sits inside Ostia's buffer.
            Result<List<Claim>> result =
                    await(support.claims.claimRadius(mayor, city, WORLD, 33, 0, 3));

            assertTrue(result.isFailure());
            assertEquals(before, await(support.daos.claims().count()),
                    "an atomic square must leave nothing behind");
            assertEquals(0, treasuryBefore.compareTo(
                    await(support.daos.cities().findById(city.id())).orElseThrow().treasury()));
        }

        @Test
        @DisplayName("the radius is bounded by claims.radius-claim-max")
        void radiusBounds() {
            assertEquals("RADIUS_OUT_OF_BOUNDS",
                    reasonOf(await(support.claims.claimRadius(mayor, city, WORLD, 1, 0, 7))));
            assertEquals("RADIUS_OUT_OF_BOUNDS",
                    reasonOf(await(support.claims.claimRadius(mayor, city, WORLD, 1, 0, 0))));
        }
    }

    // ==================================================================================
    // Unclaiming, SPEC 6.4
    // ==================================================================================

    @Nested
    @DisplayName("Unclaiming")
    class Unclaiming {

        @Test
        @DisplayName("releasing a chunk refunds half to the treasury, never to a player")
        void refundGoesToTheTreasury() {
            Claim bought = claim(1, 0).orElseThrow();
            BigDecimal treasuryAfterBuying = city.treasury();
            BigDecimal playerBefore = support.playerRow(mayor).balance();

            assertTrue(unclaim(1, 0).isSuccess());

            // Half of the 375 actually paid, not half of the undiscounted list price.
            assertEquals(0, treasuryAfterBuying.add(new BigDecimal("187.50"))
                    .compareTo(city.treasury()));
            assertEquals(0, playerBefore.compareTo(support.playerRow(mayor).balance()),
                    "SPEC 6.4: the refund must not reach a player, or claim-flipping pays");
            assertTrue(support.claimRegistry.at(WORLD, 1, 0).isEmpty());
        }

        @Test
        @DisplayName("the refund is ledgered")
        void refundIsLedgered() {
            claim(1, 0);
            unclaim(1, 0);

            List<LedgerRow> entries = await(support.daos.ledger()
                    .findByType(TransactionType.CHUNK_UNCLAIM_REFUND.name(), 0L, 10));
            assertEquals(1, entries.size());
            assertEquals(0, new BigDecimal("187.50").compareTo(entries.get(0).amount()));
        }

        @Test
        @DisplayName("the core chunk can never be released, SPEC 6.4")
        void coreIsProtected() {
            assertEquals("CORE_CHUNK", reasonOf(unclaim(0, 0)));
            assertTrue(support.claimRegistry.at(WORLD, 0, 0).isPresent());
        }

        @Test
        @DisplayName("a chunk another city owns cannot be released")
        void notYourLand() {
            UUID other = support.givenEligiblePlayer("Remus");
            support.givenCity(other, "Ostia", 50, 50);

            assertEquals("NOT_YOUR_CLAIM", reasonOf(unclaim(50, 50)));
            assertEquals("NOT_YOUR_CLAIM", reasonOf(unclaim(99, 99)));
        }

        @Test
        @DisplayName("a member without UNCLAIM cannot release land")
        void permissionIsRequired() {
            claim(1, 0);
            UUID member = support.givenMember(city, "Titus");
            support.refreshPricing();

            assertEquals("NO_CITY_PERMISSION",
                    reasonOf(await(support.claims.unclaim(member, city, WORLD, 1, 0))));
        }

        @Test
        @DisplayName("SPEC 17.2 case 22: releasing the spawn chunk is refused")
        void case22SpawnChunkIsProtected() {
            claim(1, 0);
            // Move spawn into the newly bought chunk.
            city.setSpawn(1 * 16 + 8.5, 64.0, 8.5, 0f, 0f);

            assertEquals("CONTAINS_SPAWN", reasonOf(unclaim(1, 0)));
        }

        @Test
        @DisplayName("blocks are not removed; the land simply stops being protected")
        void buildingsSurvive() {
            claim(1, 0);
            assertTrue(unclaim(1, 0).isSuccess());

            // Nothing in the plugin touches the world on unclaim, so the only observable
            // effect is that ownership is gone.
            assertTrue(support.claimRegistry.at(WORLD, 1, 0).isEmpty());
            assertEquals(1, support.claimRegistry.countOf(city.id()));
        }
    }

    // ==================================================================================
    // Contiguity rejection, SPEC 18.2
    // ==================================================================================

    @Nested
    @DisplayName("Contiguity")
    class ContiguityRejection {

        @Test
        @DisplayName("SPEC 17.2 case 12: releasing a middle chunk that would split the city")
        void case12SplitIsRefused() {
            givenLine(3);

            Result<Claim> result = unclaim(1, 0);

            assertEquals("BREAKS_CONTIGUITY", reasonOf(result));
            Result.Failure<Claim> failure = (Result.Failure<Claim>) result;
            assertEquals("2", failure.placeholders().get("count"));
            assertTrue(failure.placeholders().get("chunks").contains("2,0"),
                    "the refusal must name the orphans: " + failure.placeholders());

            assertTrue(support.claimRegistry.at(WORLD, 1, 0).isPresent(), "nothing was released");
        }

        @Test
        @DisplayName("releasing the end of a line is allowed, because nothing is stranded")
        void endOfLineIsFine() {
            givenLine(3);

            assertTrue(unclaim(3, 0).isSuccess());
            assertEquals(3, support.claimRegistry.countOf(city.id()));
        }

        @Test
        @DisplayName("a ring can lose any one chunk, because it has two paths")
        void ringSurvives() {
            // Build a 3x3 block around the core, then release the middle-east chunk.
            givenLine(1);
            assertTrue(claim(1, 1).isSuccess());
            assertTrue(claim(0, 1).isSuccess());

            assertTrue(unclaim(1, 0).isSuccess(),
                    "with a path through 0,1 and 1,1 the corner is still reachable");
        }

        @Test
        @DisplayName("outposts are excluded from the check, SPEC 6.1")
        void outpostsAreExcluded() {
            givenLine(2);

            // A detached outpost chunk, written directly because outpost creation is M10.
            await(support.daos.claims().insert(new dev.civitas.storage.row.ClaimRow(0, city.id(),
                    WORLD, 40, 40, System.currentTimeMillis(), mayor, new BigDecimal("0.00"),
                    ClaimType.OUTPOST.name(), null)));
            await(support.claimRegistry.loadAll());

            assertEquals(4, support.claimRegistry.countOf(city.id()));
            assertEquals(3, support.claimRegistry.contiguousChunksOf(city.id(), WORLD).size(),
                    "the outpost must not appear in the contiguity set");

            // The outpost is detached, yet the city is still considered whole.
            assertTrue(unclaim(2, 0).isSuccess());
        }

        @Test
        @DisplayName("an outpost itself can be released without a contiguity complaint")
        void outpostCanBeReleased() {
            await(support.daos.claims().insert(new dev.civitas.storage.row.ClaimRow(0, city.id(),
                    WORLD, 40, 40, System.currentTimeMillis(), mayor, new BigDecimal("100.00"),
                    ClaimType.OUTPOST.name(), null)));
            await(support.claimRegistry.loadAll());

            assertTrue(unclaim(40, 40).isSuccess());
        }

        @Test
        @DisplayName("contiguity can be turned off, and then a split is allowed")
        void enforcementIsConfigurable() {
            givenLine(3);
            support.configs.get(dev.civitas.config.ConfigFile.CITIES)
                    .set("claims.enforce-contiguity", false);

            assertTrue(unclaim(1, 0).isSuccess());
        }
    }

    // ==================================================================================
    // The cache
    // ==================================================================================

    @Test
    @DisplayName("the cache survives a reload, rebuilt from what was persisted")
    void cacheReloads() {
        givenLine(3);

        support.claimRegistry.clear();
        assertEquals(0, support.claimRegistry.size());

        assertEquals(4, (int) await(support.claimRegistry.loadAll()));
        assertEquals(4, support.claimRegistry.countOf(city.id()));
        assertTrue(support.claimRegistry.at(WORLD, 2, 0).isPresent());
        assertEquals(ClaimType.CORE,
                support.claimRegistry.at(WORLD, 0, 0).orElseThrow().type());
    }

    @Test
    @DisplayName("disbanding a city releases its land from the cache and refunds the mayor")
    void disbandReleasesLand() {
        givenLine(2);
        // Two chunks at the discounted starter price of 375 each, so 375 comes back.
        BigDecimal landRefund = new BigDecimal("375.00");
        BigDecimal mayorBefore = support.playerRow(mayor).balance();
        // The mayor is the only member, so SPEC 17.1 case 10 also hands them the treasury.
        BigDecimal treasuryShare = await(support.daos.cities().findById(city.id()))
                .orElseThrow().treasury();

        assertTrue(await(support.cities.disband(mayor, city)).isSuccess());

        assertEquals(0, support.claimRegistry.countOf(city.id()));
        assertTrue(support.claimRegistry.at(WORLD, 1, 0).isEmpty());

        assertEquals(0, mayorBefore.add(treasuryShare).add(landRefund)
                        .compareTo(support.playerRow(mayor).balance()),
                "SPEC 5.3 requires 50% of cost_paid back to the mayor on top of their share");
    }

    @Test
    @DisplayName("wilderness reads as unowned, which is what land protection will ask")
    void wildernessIsEmpty() {
        assertTrue(support.claimRegistry.at(WORLD, 500, 500).isEmpty());
        assertTrue(support.claimRegistry.atBlock(WORLD, 8000, 8000).isEmpty());
        assertFalse(support.claimRegistry.isClaimed(WORLD, 500, 500));
    }

    @Test
    @DisplayName("block coordinates resolve to the right chunk, including negative ones")
    void blockLookup() {
        assertTrue(support.claimRegistry.atBlock(WORLD, 0, 0).isPresent());
        assertTrue(support.claimRegistry.atBlock(WORLD, 15, 15).isPresent());
        assertTrue(support.claimRegistry.atBlock(WORLD, -1, -1).isEmpty(),
                "block -1 is chunk -1, which the city does not own");
    }
}
