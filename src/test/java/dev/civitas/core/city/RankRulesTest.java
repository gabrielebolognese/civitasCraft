package dev.civitas.core.city;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 18.1: "cannot grant what you lack" and "cannot edit equal or higher weight", plus
 * SPEC 18.2's "rank creation, assignment, and permission enforcement".
 *
 * <p>These are the two rules that decide whether a city's hierarchy can be climbed from
 * inside. If either leaks, any member who reaches Co-Mayor can make themselves mayor in all
 * but name, so each is tested from the perspective of the member trying to break it.
 */
class RankRulesTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private UUID mayor;
    private UUID coMayor;
    private UUID citizen;
    private City city;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);

        mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);

        coMayor = support.givenMember(city, "Numa");
        citizen = support.givenMember(city, "Tullus");

        await(support.ranks.assign(mayor, city, coMayor, city.rankByName("Co-Mayor").orElseThrow()));
        await(support.ranks.assign(mayor, city, citizen, city.rankByName("Citizen").orElseThrow()));

        // SPEC 5.4 gives Co-Mayor everything except DISBAND, TRANSFER and MANAGE_RANKS. The
        // rules under test here are about editing ranks, so grant that one flag; without it
        // every test would stop at the permission check and prove nothing about the rules.
        await(support.ranks.setPermission(mayor, city, city.rankByName("Co-Mayor").orElseThrow(),
                CityPermission.MANAGE_RANKS, true));
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    // ==================================================================================
    // Cannot grant what you lack
    // ==================================================================================

    @Test
    @DisplayName("a Co-Mayor cannot grant DISBAND, which their own rank does not hold")
    void cannotGrantUnheldPermission() {
        CityRank citizenRank = city.rankByName("Citizen").orElseThrow();

        Result<CityRank> result = await(support.ranks.setPermission(coMayor, city, citizenRank,
                CityPermission.DISBAND, true));

        assertEquals("CANNOT_GRANT_UNHELD", reasonOf(result));
        assertFalse(citizenRank.has(CityPermission.DISBAND));
    }

    @Test
    @DisplayName("the refusal names the flag that was missing")
    void refusalNamesTheFlag() {
        CityRank citizenRank = city.rankByName("Citizen").orElseThrow();

        // TRANSFER is one of the three flags SPEC 5.4 withholds from Co-Mayor.
        Result<CityRank> result = await(support.ranks.setPermission(coMayor, city, citizenRank,
                CityPermission.TRANSFER, true));

        Result.Failure<CityRank> failure = (Result.Failure<CityRank>) result;
        assertEquals("TRANSFER", failure.placeholders().get("permissions"));
    }

    @Test
    @DisplayName("a Co-Mayor can grant a permission their own rank does hold")
    void canGrantHeldPermission() {
        CityRank citizenRank = city.rankByName("Citizen").orElseThrow();
        assertFalse(citizenRank.has(CityPermission.CLAIM));

        assertTrue(await(support.ranks.setPermission(coMayor, city, citizenRank,
                CityPermission.CLAIM, true)).isSuccess());
        assertTrue(citizenRank.has(CityPermission.CLAIM));
    }

    @Test
    @DisplayName("revoking is always allowed, even for a flag the actor does not hold")
    void revokingNeedsNoGrant() {
        CityRank citizenRank = city.rankByName("Citizen").orElseThrow();
        assertTrue(await(support.ranks.setPermission(mayor, city, citizenRank,
                CityPermission.DISBAND, true)).isSuccess());

        // The Co-Mayor lacks DISBAND but may still take it away; the danger is escalation,
        // not reduction.
        assertTrue(await(support.ranks.setPermission(coMayor, city, citizenRank,
                CityPermission.DISBAND, false)).isSuccess());
        assertFalse(citizenRank.has(CityPermission.DISBAND));
    }

    @Test
    @DisplayName("the mayor holds everything, so the mayor may grant anything")
    void mayorMayGrantAnything() {
        CityRank citizenRank = city.rankByName("Citizen").orElseThrow();

        assertTrue(await(support.ranks.setPermission(mayor, city, citizenRank,
                CityPermission.DISBAND, true)).isSuccess());
        assertTrue(citizenRank.has(CityPermission.DISBAND));
    }

    // ==================================================================================
    // Cannot edit equal or higher weight
    // ==================================================================================

    @Test
    @DisplayName("a Co-Mayor cannot edit their own rank")
    void cannotEditOwnRank() {
        CityRank coMayorRank = city.rankByName("Co-Mayor").orElseThrow();

        Result<CityRank> result = await(support.ranks.setPermission(coMayor, city, coMayorRank,
                CityPermission.DISBAND, true));

        assertEquals("RANK_OUTRANKS_ACTOR", reasonOf(result));
    }

    @Test
    @DisplayName("a member cannot edit a rank above their own")
    void cannotEditHigherRank() {
        CityRank coMayorRank = city.rankByName("Co-Mayor").orElseThrow();

        Result<CityRank> result = await(support.ranks.setPermission(citizen, city, coMayorRank,
                CityPermission.BUILD, false));

        // The permission check fires first: a Citizen has no MANAGE_RANKS at all.
        assertEquals("NO_CITY_PERMISSION", reasonOf(result));

        // Give them MANAGE_RANKS and the weight rule is what stops them.
        CityRank citizenRank = city.rankByName("Citizen").orElseThrow();
        assertTrue(await(support.ranks.setPermission(mayor, city, citizenRank,
                CityPermission.MANAGE_RANKS, true)).isSuccess());

        assertEquals("RANK_OUTRANKS_ACTOR", reasonOf(await(support.ranks.setPermission(
                citizen, city, coMayorRank, CityPermission.BUILD, false))));
    }

    @Test
    @DisplayName("equal weight is not enough, so peers cannot demote each other")
    void equalWeightIsNotEnough() {
        CityRank peerRank = await(support.ranks.create(mayor, city, "Peer", 40)).orElseThrow();
        assertTrue(await(support.ranks.setPermission(mayor, city, peerRank,
                CityPermission.MANAGE_RANKS, true)).isSuccess());
        assertTrue(await(support.ranks.assign(mayor, city, citizen, peerRank)).isSuccess());

        CityRank otherAtSameWeight = city.rankByName("Citizen").orElseThrow();
        assertEquals(peerRank.weight(), otherAtSameWeight.weight());

        assertEquals("RANK_OUTRANKS_ACTOR", reasonOf(await(support.ranks.setPermission(
                citizen, city, otherAtSameWeight, CityPermission.BUILD, false))));
    }

    @Test
    @DisplayName("a rank cannot be created at or above the creator's own weight")
    void cannotCreateAtOrAboveOwnWeight() {
        assertEquals("WEIGHT_TOO_HIGH",
                reasonOf(await(support.ranks.create(coMayor, city, "Rival", 80))));
        assertEquals("WEIGHT_TOO_HIGH",
                reasonOf(await(support.ranks.create(coMayor, city, "Rival", 90))));
        assertTrue(await(support.ranks.create(coMayor, city, "Deputy", 79)).isSuccess());
    }

    @Test
    @DisplayName("nobody may create a rank at the mayor's reserved weight")
    void mayorWeightIsReserved() {
        assertEquals("WEIGHT_RESERVED",
                reasonOf(await(support.ranks.create(mayor, city, "Regent", 100))));
        assertEquals("WEIGHT_RESERVED",
                reasonOf(await(support.ranks.create(mayor, city, "Regent", 150))));
    }

    // ==================================================================================
    // Assignment, SPEC 18.2
    // ==================================================================================

    @Test
    @DisplayName("a rank is created, assigned, and takes effect on the member's permissions")
    void createAssignAndEnforce() {
        CityRank builder = await(support.ranks.create(mayor, city, "Builder", 50)).orElseThrow();
        assertTrue(builder.permissions().isEmpty(), "a new rank starts with nothing");

        assertTrue(await(support.ranks.setPermission(mayor, city, builder,
                CityPermission.BUILD, true)).isSuccess());
        assertTrue(await(support.ranks.assign(mayor, city, citizen, builder)).isSuccess());

        assertEquals("Builder", city.rankOf(citizen).orElseThrow().name());
        assertTrue(city.hasPermission(citizen, CityPermission.BUILD));
        assertFalse(city.hasPermission(citizen, CityPermission.CLAIM));
        assertEquals(50, city.weightOf(citizen));
    }

    @Test
    @DisplayName("assignment is persisted, not only cached")
    void assignmentIsPersisted() {
        CityRank architect = city.rankByName("Architect").orElseThrow();
        assertTrue(await(support.ranks.assign(mayor, city, citizen, architect)).isSuccess());

        support.registry.clear();
        await(support.registry.loadAll());

        City reloaded = support.registry.cityByName("Roma").orElseThrow();
        assertEquals("Architect", reloaded.rankOf(citizen).orElseThrow().name());
        assertEquals(architect.id(), support.playerRow(citizen).rankId());
    }

    @Test
    @DisplayName("a member cannot be given a rank at or above the assigner's own")
    void cannotAssignAtOrAboveOwnWeight() {
        CityRank coMayorRank = city.rankByName("Co-Mayor").orElseThrow();

        assertEquals("RANK_OUTRANKS_ACTOR",
                reasonOf(await(support.ranks.assign(coMayor, city, citizen, coMayorRank))));
    }

    @Test
    @DisplayName("the mayor's own rank cannot be reassigned; mayorship transfers instead")
    void mayorCannotBeReranked() {
        CityRank citizenRank = city.rankByName("Citizen").orElseThrow();

        assertEquals("CANNOT_RANK_MAYOR",
                reasonOf(await(support.ranks.assign(mayor, city, mayor, citizenRank))));
    }

    @Test
    @DisplayName("promote and demote step one rank at a time by weight")
    void promoteAndDemote() {
        assertEquals("Citizen", city.rankOf(citizen).map(CityRank::name).orElseThrow());

        assertEquals("Architect",
                await(support.ranks.promote(mayor, city, citizen)).orElseThrow().name());
        assertEquals("Co-Mayor",
                await(support.ranks.promote(mayor, city, citizen)).orElseThrow().name());
        assertEquals("Architect",
                await(support.ranks.demote(mayor, city, citizen)).orElseThrow().name());
    }

    @Test
    @DisplayName("demoting past the bottom rank is refused rather than silently doing nothing")
    void demoteBelowLowest() {
        await(support.ranks.assign(mayor, city, citizen, city.rankByName("Recruit").orElseThrow()));

        assertEquals("ALREADY_LOWEST", reasonOf(await(support.ranks.demote(mayor, city, citizen))));
    }

    // ==================================================================================
    // Rank lifecycle
    // ==================================================================================

    @Test
    @DisplayName("deleting a rank moves its members to the default rank rather than kicking them")
    void deleteMovesMembers() {
        CityRank architect = city.rankByName("Architect").orElseThrow();
        assertTrue(await(support.ranks.assign(mayor, city, citizen, architect)).isSuccess());

        assertTrue(await(support.ranks.delete(mayor, city, architect)).isSuccess());

        assertTrue(city.rankByName("Architect").isEmpty());
        assertTrue(city.isMember(citizen), "deleting a rank must not remove its members");
        assertEquals("Recruit", city.rankOf(citizen).orElseThrow().name());
    }

    @Test
    @DisplayName("the mayor rank cannot be deleted")
    void mayorRankIsUndeletable() {
        CityRank mayorRank = city.rankByName("Mayor").orElseThrow();

        assertEquals("CANNOT_DELETE_MAYOR_RANK",
                reasonOf(await(support.ranks.delete(mayor, city, mayorRank))));
    }

    @Test
    @DisplayName("a rank name must be unique within its city")
    void rankNamesAreUnique() {
        assertEquals("RANK_EXISTS", reasonOf(await(support.ranks.create(mayor, city, "Citizen", 45))));
        assertEquals("RANK_EXISTS", reasonOf(await(support.ranks.create(mayor, city, "citizen", 45))));
    }

    @Test
    @DisplayName("moving the default flag leaves exactly one rank holding it")
    void defaultRankIsSingular() {
        CityRank citizenRank = city.rankByName("Citizen").orElseThrow();
        assertTrue(await(support.ranks.setDefault(mayor, city, citizenRank)).isSuccess());

        assertEquals(1, city.ranks().stream().filter(CityRank::isDefault).count());
        assertEquals("Citizen", city.defaultRank().orElseThrow().name());
    }
}
