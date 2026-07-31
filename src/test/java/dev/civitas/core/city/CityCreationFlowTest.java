package dev.civitas.core.city;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.placement;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.EventBus;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 18.2: "Full city creation flow including all 9 preconditions failing individually."
 *
 * <p>Each precondition gets its own test that arranges exactly one thing wrong, so a failure
 * here says which rule broke rather than that "creation is broken".
 */
class CityCreationFlowTest {

    @TempDir
    Path directory;

    private CityTestSupport support;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    @Test
    @DisplayName("a founder who meets every precondition gets a city")
    void happyPath() {
        UUID founder = support.givenEligiblePlayer("Romulus");

        Result<City> result = await(support.cities.create(founder, "Roma", placement(0, 0)));

        assertTrue(result.isSuccess(), "creation failed: " + reasonOf(result));
        City city = result.orElseThrow();
        assertEquals("Roma", city.name());
        assertEquals(founder, city.mayorUuid());
        assertTrue(city.id() > 0);
    }

    // ==================================================================================
    // The nine preconditions, SPEC 5.1
    // ==================================================================================

    @Test
    @DisplayName("1. a player already in a city cannot found another")
    void precondition1AlreadyInACity() {
        UUID founder = support.givenEligiblePlayer("Romulus");
        support.givenCity(founder, "Roma", 0, 0);

        Result<City> second = await(support.cities.create(founder, "Ostia", placement(50, 50)));

        assertEquals("ALREADY_IN_CITY", reasonOf(second));
        assertEquals(1, support.registry.size());
    }

    @Test
    @DisplayName("2. a player below the playtime requirement is refused")
    void precondition2Playtime() {
        UUID founder = support.givenPlayer("Newbie", new BigDecimal("50000.00"),
                TimeUnit.MINUTES.toMillis(30));

        Result<City> result = await(support.cities.create(founder, "Roma", placement(0, 0)));

        assertEquals("INSUFFICIENT_PLAYTIME", reasonOf(result));
        assertEquals(0, support.registry.size());
    }

    @Test
    @DisplayName("3. a player who cannot afford the fee is refused, and told how short they are")
    void precondition3Balance() {
        UUID founder = support.givenPlayer("Pauper", new BigDecimal("500.00"),
                TimeUnit.HOURS.toMillis(10));

        Result<City> result = await(support.cities.create(founder, "Roma", placement(0, 0)));

        assertEquals("INSUFFICIENT_FUNDS", reasonOf(result));
        Result.Failure<City> failure = (Result.Failure<City>) result;
        assertEquals("10000.00", failure.placeholders().get("required"));
        assertEquals("9500.00", failure.placeholders().get("short"));
    }

    @Test
    @DisplayName("4. a name that breaks the length or pattern rule is refused")
    void precondition4NameShape() {
        UUID founder = support.givenEligiblePlayer("Romulus");

        assertEquals("NAME_LENGTH",
                reasonOf(await(support.cities.create(founder, "Ro", placement(0, 0)))));
        assertEquals("NAME_LENGTH", reasonOf(await(support.cities.create(founder,
                "ThisCityNameIsFarTooLongToBeAccepted", placement(0, 0)))));
        assertEquals("NAME_PATTERN",
                reasonOf(await(support.cities.create(founder, "Roma!", placement(0, 0)))));
        assertEquals("NAME_PATTERN",
                reasonOf(await(support.cities.create(founder, "Roma Nova", placement(0, 0)))));

        assertEquals(0, support.registry.size());
    }

    @Test
    @DisplayName("5. a taken or blocked name is refused, case-insensitively")
    void precondition5NameTaken() {
        UUID first = support.givenEligiblePlayer("Romulus");
        support.givenCity(first, "Roma", 0, 0);

        UUID second = support.givenEligiblePlayer("Remus");
        assertEquals("NAME_TAKEN",
                reasonOf(await(support.cities.create(second, "roma", placement(50, 50)))));
        assertEquals("NAME_TAKEN",
                reasonOf(await(support.cities.create(second, "ROMA", placement(50, 50)))));
        assertEquals("NAME_BLOCKED",
                reasonOf(await(support.cities.create(second, "admin", placement(50, 50)))));
    }

    @Test
    @DisplayName("6. a chunk that already belongs to a city is refused")
    void precondition6ChunkClaimed() {
        UUID first = support.givenEligiblePlayer("Romulus");
        support.givenCity(first, "Roma", 0, 0);

        UUID second = support.givenEligiblePlayer("Remus");
        Result<City> result = await(support.cities.create(second, "Ostia", placement(0, 0)));

        // The chunk check runs before the distance check, so the message names the closer
        // of the two problems.
        assertEquals("CHUNK_CLAIMED", reasonOf(result));
    }

    @Test
    @DisplayName("7. founding within the buffer of another city is refused")
    void precondition7TooClose() {
        UUID first = support.givenEligiblePlayer("Romulus");
        support.givenCity(first, "Roma", 0, 0);

        UUID second = support.givenEligiblePlayer("Remus");
        // cities.yml sets creation.min-distance-chunks to 5, so chunk 3 is inside the buffer.
        Result<City> tooClose = await(support.cities.create(second, "Ostia", placement(3, 0)));
        assertEquals("TOO_CLOSE", reasonOf(tooClose));

        Result.Failure<City> failure = (Result.Failure<City>) tooClose;
        assertEquals("5", failure.placeholders().get("chunks"));

        // Just outside the buffer it succeeds, which proves the boundary is where it should be.
        assertTrue(await(support.cities.create(second, "Ostia", placement(6, 0))).isSuccess());
    }

    @Test
    @DisplayName("8. a blacklisted world is refused")
    void precondition8BlacklistedWorld() {
        UUID founder = support.givenEligiblePlayer("Romulus");
        Placement inTheEnd = new Placement("world_the_end", 0, 0, 8.5, 64.0, 8.5, 0f, 0f);

        assertEquals("WORLD_BLACKLISTED",
                reasonOf(await(support.cities.create(founder, "Roma", inTheEnd))));
    }

    @Test
    @DisplayName("9. a world that is not city-enabled is refused")
    void precondition9WorldNotEnabled() {
        UUID founder = support.givenEligiblePlayer("Romulus");
        Placement elsewhere = new Placement("skyblock", 0, 0, 8.5, 64.0, 8.5, 0f, 0f);

        assertEquals("WORLD_NOT_ENABLED",
                reasonOf(await(support.cities.create(founder, "Roma", elsewhere))));
    }

    // ==================================================================================
    // What a successful founding actually writes, SPEC 5.1 steps 1 to 9
    // ==================================================================================

    @Nested
    @DisplayName("On success")
    class OnSuccess {

        @Test
        @DisplayName("the fee is deducted and recorded in the ledger")
        void feeIsChargedAndLedgered() {
            UUID founder = support.givenEligiblePlayer("Romulus");
            support.givenCity(founder, "Roma", 0, 0);

            PlayerRow player = support.playerRow(founder);
            assertEquals(0, new BigDecimal("40000.00").compareTo(player.balance()));

            List<LedgerRow> entries = await(support.daos.ledger().findByPlayer(founder, 0L, 10));
            assertEquals(1, entries.size());
            assertEquals(TransactionType.CITY_CREATE_FEE.name(), entries.get(0).type());
            assertEquals(0, new BigDecimal("-10000.00").compareTo(entries.get(0).amount()));
            assertEquals(0, new BigDecimal("40000.00").compareTo(entries.get(0).balanceAfter()));
        }

        @Test
        @DisplayName("the treasury starts empty, not seeded from the fee")
        void treasuryStartsEmpty() {
            UUID founder = support.givenEligiblePlayer("Romulus");
            City city = support.givenCity(founder, "Roma", 0, 0);

            assertEquals(0, BigDecimal.ZERO.compareTo(city.treasury()));
        }

        @Test
        @DisplayName("the five SPEC 5.4 ranks are created with their documented weights")
        void defaultRanksAreCreated() {
            UUID founder = support.givenEligiblePlayer("Romulus");
            City city = support.givenCity(founder, "Roma", 0, 0);

            assertEquals(5, city.ranks().size());
            assertEquals(100, city.rankByName("Mayor").orElseThrow().weight());
            assertEquals(80, city.rankByName("Co-Mayor").orElseThrow().weight());
            assertEquals(60, city.rankByName("Architect").orElseThrow().weight());
            assertEquals(40, city.rankByName("Citizen").orElseThrow().weight());
            assertEquals(20, city.rankByName("Recruit").orElseThrow().weight());

            assertEquals(PermissionSet.ALL, city.rankByName("Mayor").orElseThrow().permissions());
            assertEquals("Recruit", city.defaultRank().orElseThrow().name());
        }

        @Test
        @DisplayName("the Co-Mayor rank holds everything except the three SPEC 5.4 exclusions")
        void coMayorRank() {
            UUID founder = support.givenEligiblePlayer("Romulus");
            City city = support.givenCity(founder, "Roma", 0, 0);

            PermissionSet coMayor = city.rankByName("Co-Mayor").orElseThrow().permissions();
            assertFalse(coMayor.has(CityPermission.DISBAND));
            assertFalse(coMayor.has(CityPermission.TRANSFER));
            assertFalse(coMayor.has(CityPermission.MANAGE_RANKS));
            assertTrue(coMayor.has(CityPermission.DECLARE_WAR));
        }

        @Test
        @DisplayName("the founder is the mayor and holds every permission")
        void founderIsMayor() {
            UUID founder = support.givenEligiblePlayer("Romulus");
            City city = support.givenCity(founder, "Roma", 0, 0);

            assertTrue(city.isMayor(founder));
            assertTrue(city.isMember(founder));
            assertEquals(PermissionSet.ALL, city.permissionsOf(founder));
            assertEquals(CityRank.MAYOR_WEIGHT, city.weightOf(founder));
            assertEquals("Mayor", city.rankOf(founder).orElseThrow().name());
        }

        @Test
        @DisplayName("the core chunk is claimed, free, and typed CORE")
        void coreChunkIsClaimed() {
            UUID founder = support.givenEligiblePlayer("Romulus");
            City city = support.givenCity(founder, "Roma", 7, -3);

            var claim = await(support.daos.claims().findAt("world", 7, -3)).orElseThrow();
            assertEquals(city.id(), claim.cityId());
            assertEquals("CORE", claim.type());
            assertEquals(0, BigDecimal.ZERO.compareTo(claim.costPaid()));
            assertEquals(founder, claim.claimedBy());
        }

        @Test
        @DisplayName("spawn is recorded at the founder's exact position")
        void spawnIsRecorded() {
            UUID founder = support.givenEligiblePlayer("Romulus");
            City city = support.givenCity(founder, "Roma", 2, 2);

            assertEquals(2 * 16 + 8.5, city.spawnX(), 1e-9);
            assertEquals(64.0, city.spawnY(), 1e-9);
            assertEquals("world", city.coreWorld());
            assertEquals(2, city.coreChunkX());
            assertEquals(2, city.coreChunkZ());
        }

        @Test
        @DisplayName("the city is findable by name and by member")
        void registryIsIndexed() {
            UUID founder = support.givenEligiblePlayer("Romulus");
            City city = support.givenCity(founder, "Roma", 0, 0);

            assertEquals(city, support.registry.cityByName("Roma").orElseThrow());
            assertEquals(city, support.registry.cityByName("ROMA").orElseThrow());
            assertEquals(city, support.registry.cityOf(founder).orElseThrow());
            assertTrue(support.registry.isNameTaken("roma"));
        }

        @Test
        @DisplayName("a tag is derived, and a second similar name gets a distinct one")
        void tagsAreUnique() {
            UUID first = support.givenEligiblePlayer("Romulus");
            City roma = support.givenCity(first, "Roma", 0, 0);
            assertEquals("ROMA", roma.tag());

            UUID second = support.givenEligiblePlayer("Remus");
            City roman = support.givenCity(second, "Roman", 50, 50);

            assertNotNull(roman.tag());
            assertFalse(roman.tag().equalsIgnoreCase(roma.tag()),
                    "two cities cannot share a tag; the column is unique");
        }
    }

    // ==================================================================================
    // Lifecycle edge cases, SPEC 17.1
    // ==================================================================================

    @Test
    @DisplayName("SPEC 17.1 case 6: the loser of a same-name race gets a clean refusal, not SQL")
    void case6SimultaneousSameName() {
        // The in-memory check is bypassed by founding directly against the database, which
        // is what a genuine same-tick race looks like from the second founder's side.
        UUID first = support.givenEligiblePlayer("Romulus");
        support.givenCity(first, "Roma", 0, 0);
        support.registry.clear();

        UUID second = support.givenEligiblePlayer("Remus");
        Result<City> result = await(support.cities.create(second, "Roma", placement(50, 50)));

        assertEquals("NAME_TAKEN", reasonOf(result));
        assertEquals("city.create.name-taken", ((Result.Failure<City>) result).messageKey());
    }

    @Test
    @DisplayName("SPEC 17.1 case 7: founding is on cooldown after a disband")
    void case7DisbandCooldown() {
        UUID founder = support.givenEligiblePlayer("Romulus");
        City city = support.givenCity(founder, "Roma", 0, 0);
        assertTrue(await(support.cities.disband(founder, city)).isSuccess());

        Result<City> again = await(support.cities.create(founder, "Nova", placement(50, 50)));

        assertEquals("DISBAND_COOLDOWN", reasonOf(again));
    }

    @Test
    @DisplayName("a cancelled CityCreateEvent stops the city and charges nothing")
    void cancellingTheEventCharsNothing() {
        try (CityTestSupport cancelling = CityTestSupport.open(directory.resolve("cancel"),
                event -> {
                    event.setCancelled(true);
                    return false;
                })) {
            UUID founder = cancelling.givenEligiblePlayer("Romulus");

            Result<City> result = await(cancelling.cities.create(founder, "Roma", placement(0, 0)));

            assertEquals("CANCELLED", reasonOf(result));
            assertEquals(0, new BigDecimal("50000.00")
                    .compareTo(cancelling.playerRow(founder).balance()));
            assertEquals(0L, await(cancelling.daos.cities().count()));
        }
    }

    @Test
    @DisplayName("a failed founding leaves nothing behind, not even a ledger entry")
    void failureIsAtomic() {
        UUID founder = support.givenPlayer("Pauper", new BigDecimal("500.00"),
                TimeUnit.HOURS.toMillis(10));

        assertTrue(await(support.cities.create(founder, "Roma", placement(0, 0))).isFailure());

        assertEquals(0L, await(support.daos.cities().count()));
        assertEquals(0L, await(support.daos.cityRanks().count()));
        assertEquals(0L, await(support.daos.cityMembers().count()));
        assertEquals(0L, await(support.daos.claims().count()));
        assertEquals(0L, await(support.daos.ledger().count()));
        assertEquals(0, new BigDecimal("500.00").compareTo(support.playerRow(founder).balance()));
    }

    @Test
    @DisplayName("the cache survives a reload, rebuilt from what was persisted")
    void cacheReloadsFromStorage() {
        UUID founder = support.givenEligiblePlayer("Romulus");
        support.givenCity(founder, "Roma", 0, 0);
        UUID member = support.givenMember(support.registry.cityByName("Roma").orElseThrow(), "Titus");

        support.registry.clear();
        assertEquals(0, support.registry.size());

        assertEquals(1, (int) await(support.registry.loadAll()));

        City reloaded = support.registry.cityByName("Roma").orElseThrow();
        assertEquals(2, reloaded.memberCount());
        assertEquals(5, reloaded.ranks().size());
        assertTrue(reloaded.isMayor(founder));
        assertEquals(reloaded, support.registry.cityOf(member).orElseThrow());
    }
}
