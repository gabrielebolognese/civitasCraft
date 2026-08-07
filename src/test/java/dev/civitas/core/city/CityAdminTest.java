package dev.civitas.core.city;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 9.4.2's admin paths through the city service.
 *
 * <h2>Every one of these exists for a situation the player rules cannot resolve</h2>
 * SPEC 17.1 case 5 is the clearest: a mayor banned from the server leaves a city nobody can act
 * for, and SPEC 5.3's transfer needs the target online and accepting — which is exactly what an
 * admin cannot arrange. The rest follow the same shape.
 *
 * <p>What is tested most carefully is which rules each one bypasses and which it does not.
 * {@code forceadd} ignores the cooldown and the cap because those are policy; it still refuses
 * a player already in another city, because that is the data model rather than a rule.
 */
class CityAdminTest {

    @TempDir
    Path directory;

    private static final long NOW = System.currentTimeMillis();

    private CityTestSupport support;
    private City city;
    private UUID mayor;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private City stored() {
        return support.registry.city(city.id()).orElseThrow();
    }

    // ==================================================================================
    // setmayor, SPEC 17.1 case 5
    // ==================================================================================

    @Nested
    @DisplayName("setmayor")
    class SetMayor {

        @Test
        @DisplayName("works on a member who is offline, which the player path cannot")
        void offlineSafe() {
            // The whole reason this exists. SPEC 5.3 requires the target online and accepting
            // within sixty seconds; a banned mayor's city has nobody who can arrange that.
            UUID citizen = support.givenMember(city, "Titus");

            assertTrue(await(support.cities.adminSetMayor(city, citizen)).isSuccess());

            assertEquals(citizen, stored().mayorUuid());
        }

        @Test
        @DisplayName("refuses somebody who is not in the city")
        void mustBeAMember() {
            UUID stranger = support.givenEligiblePlayer("Aeneas");

            assertEquals("NOT_A_MEMBER",
                    reasonOf(await(support.cities.adminSetMayor(city, stranger))));
        }

        @Test
        @DisplayName("refuses the mayor it already has")
        void noOp() {
            assertEquals("ALREADY_MAYOR",
                    reasonOf(await(support.cities.adminSetMayor(city, mayor))));
        }

        @Test
        @DisplayName("the change survives a reload")
        void persisted() {
            UUID citizen = support.givenMember(city, "Titus");
            await(support.cities.adminSetMayor(city, citizen));

            assertEquals(citizen, await(support.daos.cities().findById(city.id()))
                    .orElseThrow().mayorUuid());
        }
    }

    // ==================================================================================
    // freeze, SPEC 9.4.2
    // ==================================================================================

    @Nested
    @DisplayName("freeze")
    class Freeze {

        @Test
        @DisplayName("a frozen city cannot be changed by its own members")
        void blocksMutations() {
            // Nothing new is enforced by the freeze itself: every service has checked
            // isFrozen on every mutation since M2. This proves those checks are reachable.
            await(support.cities.adminFreeze(city, true));

            assertEquals("CITY_FROZEN", reasonOf(await(support.cities.setMotd(mayor, city,
                    "hello"))));
        }

        @Test
        @DisplayName("and can be unfrozen, which needs the bypass to work on a frozen city")
        void unfreezeWorksOnAFrozenCity() {
            // The reason the admin path cannot share updateSettings: that one refuses a frozen
            // city, so unfreezing through it would be impossible by construction.
            await(support.cities.adminFreeze(city, true));

            assertTrue(await(support.cities.adminFreeze(city, false)).isSuccess());

            assertFalse(stored().isFrozen());
            assertTrue(await(support.cities.setMotd(mayor, city, "hello")).isSuccess());
        }
    }

    // ==================================================================================
    // delete and restore, SPEC 17.1 case 3
    // ==================================================================================

    @Nested
    @DisplayName("delete and restore")
    class DeleteAndRestore {

        @Test
        @DisplayName("a deleted city can be restored inside the window")
        void roundTrip() {
            assertTrue(await(support.cities.adminDelete(city, NOW)).isSuccess());
            assertTrue(stored().isDeleted());

            assertTrue(await(support.cities.adminRestore(city, NOW + 1000L)).isSuccess());
            assertFalse(stored().isDeleted());
        }

        @Test
        @DisplayName("its land is still there when it comes back")
        void landSurvives() {
            // A restore that gave back a city with no claims would be a restore in name only.
            int before = support.claimRegistry.claimsOf(city.id()).size();

            await(support.cities.adminDelete(city, NOW));
            await(support.cities.adminRestore(city, NOW + 1000L));

            assertEquals(before, support.claimRegistry.claimsOf(city.id()).size());
        }

        @Test
        @DisplayName("after fourteen days it cannot be restored")
        void windowExpires() {
            await(support.cities.adminDelete(city, NOW));

            Result<City> late = await(support.cities.adminRestore(city,
                    NOW + TimeUnit.DAYS.toMillis(15)));

            assertEquals("RESTORE_WINDOW_PASSED", reasonOf(late));
        }

        @Test
        @DisplayName("deleting twice, or restoring what is not deleted, is refused")
        void refusesNonsense() {
            assertEquals("NOT_DELETED", reasonOf(await(support.cities.adminRestore(city, NOW))));

            await(support.cities.adminDelete(city, NOW));
            assertEquals("ALREADY_DELETED",
                    reasonOf(await(support.cities.adminDelete(city, NOW))));
        }
    }

    // ==================================================================================
    // forceadd and forceremove
    // ==================================================================================

    @Nested
    @DisplayName("forceadd and forceremove")
    class Membership {

        @Test
        @DisplayName("forceadd ignores the SPEC 5.2 switch cooldown")
        void ignoresTheCooldown() {
            // A player who just left another city cannot join one for 24 hours. An admin
            // putting somebody back where they belong should not have to wait a day.
            UUID player = support.givenEligiblePlayer("Titus");
            await(support.db.transaction(connection -> {
                support.daos.players().updateLastCityLeave(connection, player, NOW);
                return dev.civitas.util.Result.ok();
            }));

            assertTrue(await(support.cities.adminForceAdd(city, player, NOW)).isSuccess());
            assertTrue(stored().isMember(player));
        }

        @Test
        @DisplayName("it still refuses a player who is already in another city")
        void oneCityPerPlayer() {
            // Not a rule being bypassed but the data model: players have one city, and an
            // admin cannot make a player have two.
            City other = support.givenCity(support.givenEligiblePlayer("Dido"), "Carthago",
                    40, 40);
            UUID theirs = support.givenMember(other, "Hannibal");

            assertEquals("ALREADY_IN_CITY",
                    reasonOf(await(support.cities.adminForceAdd(city, theirs, NOW))));
        }

        @Test
        @DisplayName("forceremove takes a member out")
        void removes() {
            UUID citizen = support.givenMember(city, "Titus");

            assertTrue(await(support.cities.adminForceRemove(city, citizen)).isSuccess());

            assertFalse(stored().isMember(citizen));
        }

        @Test
        @DisplayName("it refuses the mayor, because that would leave nobody able to act")
        void notTheMayor() {
            // SPEC 9.4.2 has setmayor for this, and an admin should use it first.
            assertEquals("CANNOT_REMOVE_MAYOR",
                    reasonOf(await(support.cities.adminForceRemove(city, mayor))));
        }
    }

    // ==================================================================================
    // rename and forgivedebt
    // ==================================================================================

    @Nested
    @DisplayName("rename and forgivedebt")
    class Miscellany {

        @Test
        @DisplayName("rename is free and needs no permission")
        void renameIsFree() {
            BigDecimal before = city.treasury();

            assertTrue(await(support.cities.adminRename(city, "Roma_Nova")).isSuccess());

            assertEquals("Roma_Nova", stored().name());
            assertEquals(0, before.compareTo(stored().treasury()), "no 15,000 C fee");
        }

        @Test
        @DisplayName("it still refuses a name another city holds")
        void nameUniquenessSurvives() {
            // Structure rather than policy: the unique index would reject it anyway, and a
            // clean refusal is better than a constraint violation.
            support.givenCity(support.givenEligiblePlayer("Dido"), "Carthago", 40, 40);

            assertEquals("NAME_TAKEN",
                    reasonOf(await(support.cities.adminRename(city, "Carthago"))));
        }

        @Test
        @DisplayName("it still refuses a name no player could type")
        void nameShapeSurvives() {
            assertFalse(await(support.cities.adminRename(city, "a b c!")).isSuccess());
        }

        @Test
        @DisplayName("forgivedebt clears delinquency without payment")
        void forgivesDebt() {
            city.setDelinquentSince(NOW - TimeUnit.DAYS.toMillis(5));
            await(support.daos.cities().update(
                    await(support.daos.cities().findById(city.id())).orElseThrow()));

            // The cache is what the sweep reads, so that is what has to be cleared.
            assertTrue(city.isDelinquent());
            assertTrue(await(support.cities.adminForgiveDebt(city)).isSuccess());
            assertFalse(stored().isDelinquent());
        }

        @Test
        @DisplayName("a city that owes nothing is not 'forgiven'")
        void nothingToForgive() {
            assertEquals("NOT_DELINQUENT",
                    reasonOf(await(support.cities.adminForgiveDebt(city))));
        }
    }
}
