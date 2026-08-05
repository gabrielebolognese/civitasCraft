package dev.civitas.core.protection;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRank;
import dev.civitas.core.city.CityTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every rule in SPEC 5.5, and the SPEC 18.2 requirement that rank permissions are actually
 * enforced on a block break.
 *
 * <p>Runs against the real claim and city caches with no server mocked, because
 * {@link ProtectionService} deliberately takes no Bukkit types. The matrix below is the
 * whole point of that design: it can be asserted directly instead of through eight listeners.
 */
class ProtectionServiceTest {

    private static final String WORLD = "world";
    private static final boolean NO_BYPASS = false;
    private static final boolean BYPASS = true;

    /** A chunk the city owns. */
    private static final int OWNED_X = 1;
    private static final int OWNED_Z = 0;

    /** A chunk nobody owns. */
    private static final int WILD_X = 400;
    private static final int WILD_Z = 400;

    @TempDir
    Path directory;

    private CityTestSupport support;
    private ProtectionService protection;
    private City city;
    private UUID mayor;
    private UUID outsider;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        protection = support.protection;

        mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);
        support.refreshPricing();

        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal("100000.00")));
        city.setTreasury(new BigDecimal("100000.00"));
        assertTrue(await(support.claims.claim(mayor, city, WORLD, OWNED_X, OWNED_Z)).isSuccess());

        outsider = support.givenEligiblePlayer("Stranger");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private ProtectionDecision check(UUID player, ProtectionAction action) {
        return protection.check(player, NO_BYPASS, WORLD, OWNED_X, OWNED_Z, action);
    }

    private ProtectionDecision checkWilderness(UUID player, ProtectionAction action) {
        return protection.check(player, NO_BYPASS, WORLD, WILD_X, WILD_Z, action);
    }

    /** Adds a member on a named default rank, so the flags come from cities.yml, not the test. */
    private UUID givenMemberOn(String rankName, String playerName) {
        UUID member = support.givenMember(city, playerName);
        CityRank rank = city.rankByName(rankName).orElseThrow();
        assertTrue(await(support.ranks.assign(mayor, city, member, rank)).isSuccess());
        return member;
    }

    // ==================================================================================
    // The cases that answer yes before membership is ever consulted
    // ==================================================================================

    @ParameterizedTest
    @EnumSource(ProtectionAction.class)
    @DisplayName("wilderness is never protected, whatever the action")
    void wildernessIsFree(ProtectionAction action) {
        assertTrue(checkWilderness(outsider, action).allowed(),
                action + " should be free outside any claim");
    }

    @ParameterizedTest
    @EnumSource(ProtectionAction.class)
    @DisplayName("civitas.bypass.claim allows everything, SPEC 10")
    void bypassAllowsEverything(ProtectionAction action) {
        assertTrue(protection.check(outsider, BYPASS, WORLD, OWNED_X, OWNED_Z, action).allowed(),
                action + " should be allowed with bypass");
    }

    @Test
    @DisplayName("a claim whose city no longer exists reads as wilderness, not as a locked box")
    void orphanedClaimIsNotProtectedForever() {
        // Soft-delete the city behind the cache's back and reload, leaving the claim rows
        // pointing at a city nobody can look up. Disbanding properly removes the claims too,
        // so this is corruption rather than a state the game produces; the point is that it
        // fails open and can be cleared up rather than locking the land for good.
        await(support.daos.cities().softDelete(city.id(), System.currentTimeMillis()));
        await(support.registry.loadAll());

        assertTrue(support.registry.city(city.id()).isEmpty(), "the city should be gone");
        assertTrue(support.claimRegistry.at(WORLD, OWNED_X, OWNED_Z).isPresent(),
                "but its claim should still be cached");

        assertTrue(check(outsider, ProtectionAction.BUILD).allowed());
    }

    // ==================================================================================
    // SPEC 18.2: rank permissions enforced on a block break
    // ==================================================================================

    @Nested
    @DisplayName("Build")
    class Build {

        @Test
        @DisplayName("a non-member cannot break or place, and is told whose land it is")
        void outsiderIsRefused() {
            ProtectionDecision decision = check(outsider, ProtectionAction.BUILD);

            assertTrue(decision.denied());
            assertEquals("NOT_A_MEMBER", decision.reason());
            assertEquals("protection.denied.build", decision.messageKey());
            assertEquals("Roma", decision.placeholders().get("city"));
        }

        @Test
        @DisplayName("a member whose rank lacks BUILD is refused")
        void memberWithoutBuildIsRefused() {
            UUID recruit = givenMemberOn("Recruit", "Titus");

            ProtectionDecision decision = check(recruit, ProtectionAction.BUILD);

            assertTrue(decision.denied());
            assertEquals("NO_CITY_PERMISSION", decision.reason());
            assertEquals("BUILD", decision.placeholders().get("permission"));
        }

        @Test
        @DisplayName("a member whose rank has BUILD may build")
        void memberWithBuildIsAllowed() {
            UUID citizen = givenMemberOn("Citizen", "Marcus");

            assertTrue(check(citizen, ProtectionAction.BUILD).allowed());
        }

        @Test
        @DisplayName("granting BUILD takes effect immediately, with no relog")
        void grantingTheFlagWorks() {
            UUID recruit = givenMemberOn("Recruit", "Titus");
            assertTrue(check(recruit, ProtectionAction.BUILD).denied());

            CityRank recruitRank = city.rankByName("Recruit").orElseThrow();
            assertTrue(await(support.ranks.setPermission(mayor, city, recruitRank,
                    dev.civitas.core.city.CityPermission.BUILD, true)).isSuccess());

            assertTrue(check(recruit, ProtectionAction.BUILD).allowed(),
                    "the cache is the read path, so a granted flag applies at once");
        }

        @Test
        @DisplayName("revoking BUILD takes effect immediately too")
        void revokingTheFlagWorks() {
            UUID citizen = givenMemberOn("Citizen", "Marcus");
            assertTrue(check(citizen, ProtectionAction.BUILD).allowed());

            CityRank citizenRank = city.rankByName("Citizen").orElseThrow();
            assertTrue(await(support.ranks.setPermission(mayor, city, citizenRank,
                    dev.civitas.core.city.CityPermission.BUILD, false)).isSuccess());

            assertTrue(check(citizen, ProtectionAction.BUILD).denied());
        }

        @Test
        @DisplayName("the mayor may always build, whatever their rank says")
        void mayorAlwaysBuilds() {
            CityRank mayorRank = city.rankByName("Mayor").orElseThrow();
            assertTrue(mayorRank.has(dev.civitas.core.city.CityPermission.BUILD));

            assertTrue(check(mayor, ProtectionAction.BUILD).allowed());
        }
    }

    // ==================================================================================
    // Containers, and the SPEC 5.4 read-only distinction
    // ==================================================================================

    @Nested
    @DisplayName("Containers")
    class Containers {

        @Test
        @DisplayName("CONTAINER_READONLY opens a chest but does not empty it, SPEC 5.4")
        void readOnlyMeansReadOnly() {
            UUID recruit = givenMemberOn("Recruit", "Titus");

            assertTrue(check(recruit, ProtectionAction.CONTAINER_OPEN).allowed(),
                    "a Recruit holds CONTAINER_READONLY and may look");
            assertTrue(check(recruit, ProtectionAction.CONTAINER_TAKE).denied(),
                    "but read-only must not permit taking, or it is not read-only");
        }

        @Test
        @DisplayName("full CONTAINER access permits both")
        void fullAccessTakes() {
            UUID citizen = givenMemberOn("Citizen", "Marcus");

            assertTrue(check(citizen, ProtectionAction.CONTAINER_OPEN).allowed());
            assertTrue(check(citizen, ProtectionAction.CONTAINER_TAKE).allowed());
        }

        @Test
        @DisplayName("a non-member cannot even open")
        void outsiderCannotOpen() {
            assertTrue(check(outsider, ProtectionAction.CONTAINER_OPEN).denied());
            assertTrue(check(outsider, ProtectionAction.CONTAINER_TAKE).denied());
        }

        @Test
        @DisplayName("the refusal for taking is its own message, not the generic one")
        void takeHasItsOwnMessage() {
            UUID recruit = givenMemberOn("Recruit", "Titus");

            assertEquals("protection.denied.container-take",
                    check(recruit, ProtectionAction.CONTAINER_TAKE).messageKey());
        }
    }

    // ==================================================================================
    // The rest of the SPEC 5.5 list
    // ==================================================================================

    @Nested
    @DisplayName("Other actions")
    class OtherActions {

        @Test
        @DisplayName("interaction needs INTERACT, which a Recruit has and an outsider does not")
        void interact() {
            UUID recruit = givenMemberOn("Recruit", "Titus");

            assertTrue(check(recruit, ProtectionAction.INTERACT).allowed());
            assertTrue(check(outsider, ProtectionAction.INTERACT).denied());
        }

        @Test
        @DisplayName("buckets are gated on BUILD, because they change blocks")
        void buckets() {
            UUID recruit = givenMemberOn("Recruit", "Titus");
            UUID citizen = givenMemberOn("Citizen", "Marcus");

            assertTrue(check(recruit, ProtectionAction.BUCKET).denied(),
                    "a Recruit may open doors but not pour lava");
            assertTrue(check(citizen, ProtectionAction.BUCKET).allowed());
        }

        @Test
        @DisplayName("trampling crops is gated on BUILD")
        void trample() {
            UUID recruit = givenMemberOn("Recruit", "Titus");

            assertTrue(check(recruit, ProtectionAction.FARMLAND_TRAMPLE).denied());
            assertTrue(check(outsider, ProtectionAction.FARMLAND_TRAMPLE).denied());
            assertTrue(check(mayor, ProtectionAction.FARMLAND_TRAMPLE).allowed());
        }

        @Test
        @DisplayName("harming a city's animals is gated on BUILD")
        void entityDamage() {
            UUID recruit = givenMemberOn("Recruit", "Titus");

            assertTrue(check(outsider, ProtectionAction.ENTITY_DAMAGE).denied());
            assertTrue(check(recruit, ProtectionAction.ENTITY_DAMAGE).denied());
            assertTrue(check(mayor, ProtectionAction.ENTITY_DAMAGE).allowed());
        }

        @Test
        @DisplayName("SPEC 5.5: PvP is off inside claims and free outside them")
        void pvp() {
            assertTrue(check(outsider, ProtectionAction.PVP).denied());
            assertEquals("PVP_DISABLED", check(outsider, ProtectionAction.PVP).reason());

            // No rank grants it and the mayor is not exempt: SPEC 5.5 makes PvP a function of
            // the war state, not of membership.
            assertTrue(check(mayor, ProtectionAction.PVP).denied());

            assertTrue(checkWilderness(outsider, ProtectionAction.PVP).allowed());
        }

        @Test
        @DisplayName("PVP is the one action no rank flag can ever grant")
        void pvpIsNotRankGoverned() {
            assertFalse(ProtectionAction.PVP.isRankGoverned());
            for (ProtectionAction action : ProtectionAction.values()) {
                if (action != ProtectionAction.PVP) {
                    assertTrue(action.isRankGoverned(), action + " should map to a rank flag");
                }
            }
        }

        @Test
        @DisplayName("villager trading is allowed by default and gated when turned off")
        void villagerTrading() {
            assertTrue(protection.villagerTradingEnabled(), "SPEC 5.5: default allowed");

            support.configs.get(ConfigFile.CITIES).set("protection.allow-villager-trading", false);
            assertFalse(protection.villagerTradingEnabled());

            // The listener only consults the action once the toggle is off.
            assertTrue(check(outsider, ProtectionAction.VILLAGER_TRADE).denied());
            assertTrue(check(givenMemberOn("Recruit", "Titus"),
                    ProtectionAction.VILLAGER_TRADE).allowed());
        }
    }

    // ==================================================================================
    // Cross-boundary rules
    // ==================================================================================

    @Nested
    @DisplayName("Boundaries")
    class Boundaries {

        @Test
        @DisplayName("a piston may move inside one city's land")
        void pistonWithinOneCity() {
            assertTrue(protection.allowsPistonBetween(WORLD, 0, 0, OWNED_X, OWNED_Z));
            assertTrue(protection.allowsPistonBetween(WORLD, 0, 0, 0, 0));
        }

        @Test
        @DisplayName("SPEC 5.5: a piston may never cross a claim boundary, in either direction")
        void pistonAcrossABoundary() {
            assertFalse(protection.allowsPistonBetween(WORLD, OWNED_X, OWNED_Z, 2, 0),
                    "claim to wilderness");
            assertFalse(protection.allowsPistonBetween(WORLD, 2, 0, OWNED_X, OWNED_Z),
                    "wilderness to claim");
        }

        @Test
        @DisplayName("a piston may move freely in wilderness")
        void pistonInWilderness() {
            assertTrue(protection.allowsPistonBetween(WORLD, WILD_X, WILD_Z, WILD_X + 1, WILD_Z));
        }

        @Test
        @DisplayName("two cities cannot push into each other")
        void pistonBetweenTwoCities() {
            UUID other = support.givenEligiblePlayer("Remus");
            City ostia = support.givenCity(other, "Ostia", 30, 0);

            assertFalse(protection.allowsPistonBetween(WORLD, OWNED_X, OWNED_Z,
                    ostia.coreChunkX(), ostia.coreChunkZ()));
        }

        @Test
        @DisplayName("fire and fluid follow the same boundary rule")
        void spreadAcrossABoundary() {
            assertTrue(protection.allowsSpreadBetween(WORLD, 0, 0, OWNED_X, OWNED_Z),
                    "a city may flood its own land");
            assertFalse(protection.allowsSpreadBetween(WORLD, OWNED_X, OWNED_Z, 2, 0),
                    "but not its neighbour's, nor the wilderness beyond its border");
            assertTrue(protection.allowsSpreadBetween(WORLD, WILD_X, WILD_Z, WILD_X, WILD_Z + 1));
        }

        @Test
        @DisplayName("SPEC 5.5: explosions are disabled inside claims and free outside")
        void explosions() {
            assertFalse(protection.allowsExplosionAt(WORLD, 0, 0));
            assertFalse(protection.allowsExplosionAt(WORLD, OWNED_X, OWNED_Z));
            assertTrue(protection.allowsExplosionAt(WORLD, WILD_X, WILD_Z));
        }
    }

    // ==================================================================================
    // Wiring
    // ==================================================================================

    @Test
    @DisplayName("every action names a message key that exists")
    void everyActionHasAMessage() {
        for (ProtectionAction action : ProtectionAction.values()) {
            assertTrue(action.messageKey().startsWith("protection.denied."),
                    action + " has an unexpected message key: " + action.messageKey());
        }
    }

    @Test
    @DisplayName("cityAt answers who owns a chunk, for listeners that need to name it")
    void cityAt() {
        assertEquals("Roma", protection.cityAt(WORLD, OWNED_X, OWNED_Z).orElseThrow().name());
        assertTrue(protection.cityAt(WORLD, WILD_X, WILD_Z).isEmpty());
    }

    @Test
    @DisplayName("the deny-message cooldown comes from config")
    void denyCooldownIsConfigurable() {
        assertEquals(2000L, protection.denyMessageCooldownMillis());

        support.configs.get(ConfigFile.CITIES).set("protection.deny-message-cooldown-ms", 500);
        assertEquals(500L, protection.denyMessageCooldownMillis());
    }

    // ==================================================================================
    // Allied trust, SPEC 14.2
    // ==================================================================================

    @Nested
    @DisplayName("reciprocal build access between allies, SPEC 14.2")
    class AlliedTrust {

        private UUID allyMember;
        private City ally;

        @BeforeEach
        void allyWithRoma() {
            UUID allyMayor = support.givenEligiblePlayer("Aeneas");
            ally = support.givenCity(allyMayor, "Ostia", 40, 40);
            allyMember = allyMayor;

            assertTrue(await(support.diplomacy.invite(mayor, city, ally)).isSuccess());
            assertTrue(await(support.diplomacy.accept(allyMayor, ally, city)).isSuccess());
        }

        @Test
        @DisplayName("an ally with no trust granted is still an outsider")
        void allianceAloneGrantsNothing() {
            assertFalse(check(allyMember, ProtectionAction.BUILD).allowed());
            assertFalse(check(allyMember, ProtectionAction.INTERACT).allowed());
        }

        @Test
        @DisplayName("trust grants BUILD and INTERACT")
        void trustGrantsBuildAndInteract() {
            assertTrue(await(support.diplomacy.setTrusted(mayor, city, ally, true)).isSuccess());

            assertTrue(check(allyMember, ProtectionAction.BUILD).allowed());
            assertTrue(check(allyMember, ProtectionAction.INTERACT).allowed());
        }

        @Test
        @DisplayName("trust never grants container access, SPEC 14.2 says so in as many words")
        void trustNeverGrantsContainers() {
            await(support.diplomacy.setTrusted(mayor, city, ally, true));

            assertFalse(check(allyMember, ProtectionAction.CONTAINER_OPEN).allowed(),
                    "a trusted ally must not be able to empty the city's chests");
            assertFalse(check(allyMember, ProtectionAction.CONTAINER_TAKE).allowed());
        }

        @Test
        @DisplayName("withdrawing trust takes the access back at once")
        void trustCanBeWithdrawn() {
            await(support.diplomacy.setTrusted(mayor, city, ally, true));
            assertTrue(check(allyMember, ProtectionAction.BUILD).allowed());

            await(support.diplomacy.setTrusted(mayor, city, ally, false));
            assertFalse(check(allyMember, ProtectionAction.BUILD).allowed());
        }

        @Test
        @DisplayName("a player in no city is not helped by anyone's alliance")
        void cityLessPlayersAreUnaffected() {
            await(support.diplomacy.setTrusted(mayor, city, ally, true));
            assertFalse(check(outsider, ProtectionAction.BUILD).allowed());
        }
    }
}
