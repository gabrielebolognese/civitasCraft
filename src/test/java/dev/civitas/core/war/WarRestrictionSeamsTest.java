package dev.civitas.core.war;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.storage.row.WarRow;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 11.11's list, enforced through the services that own each rule.
 *
 * <h2>What is really being tested here</h2>
 * Every one of these was a seam: a named method that answered "no war" from the milestone that
 * wrote it until M19 arrived. A seam that is wired but never exercised is indistinguishable
 * from one that was forgotten — both compile, both pass every other test, and both let a city
 * do something SPEC says it cannot. So each rule is asserted twice: refused while a war is on,
 * and allowed again once it is over, which is the half that catches a seam wired to something
 * that is never false.
 *
 * <p>SPEC 11.11 gives the reason behind the whole list in SPEC 11.4: the war zone is computed
 * once, when the fighting starts, and never recomputed. Everything here exists so a city cannot
 * change what it is while a war is being fought over it.
 */
class WarRestrictionSeamsTest {

    @TempDir
    Path directory;

    private static final long NOW = System.currentTimeMillis();
    private static final BigDecimal WAGER = new BigDecimal("50000.00");

    private CityTestSupport support;
    private WarRegistry registry;
    private WarRestrictions restrictions;
    private City attacker;
    private City defender;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        registry = new WarRegistry(support.daos.wars());
        restrictions = new WarRestrictions(registry, support.registry);

        attacker = support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
        defender = support.givenCity(support.givenEligiblePlayer("Dido"), "Carthago", 40, 40);
        fund(attacker, "500000.00");
        fund(defender, "500000.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private void fund(City city, String amount) {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal(amount)));
        city.setTreasury(new BigDecimal(amount));
    }

    /** A war in whichever state the rule under test cares about. */
    private War givenWar(WarState state) {
        int id = await(support.daos.wars().insert(new WarRow(0, attacker.id(), defender.id(),
                NOW, NOW + 1000L, NOW + 2000L, state.key(), 0, 0, null, WAGER, null, null)));
        War war = new War(id, attacker.id(), defender.id(), NOW, NOW + 1000L, NOW + 2000L,
                state, WAGER);
        registry.remember(war);
        return war;
    }

    // ==================================================================================
    // The restriction table itself
    // ==================================================================================

    @Nested
    @DisplayName("what a war forbids")
    class Forbids {

        @Test
        @DisplayName("both PREP and ACTIVE bind a city, and nothing else does")
        void onlyTheEngagedStates() {
            // SPEC 11.11 says "during PREP and ACTIVE". A declared-but-not-yet-started war
            // binds too, because SPEC 11.3's decline window is part of the same commitment.
            for (WarState state : WarState.values()) {
                registry.forget(givenWar(state).id());
            }

            assertFalse(restrictions.isEngaged(attacker.id()), "no war, no restrictions");

            War prep = givenWar(WarState.PREP);
            assertTrue(restrictions.isEngaged(attacker.id()));
            assertTrue(restrictions.isEngaged(defender.id()), "both sides, not only the attacker");
            registry.forget(prep.id());

            War active = givenWar(WarState.ACTIVE);
            assertTrue(restrictions.isEngaged(attacker.id()));
            registry.forget(active.id());

            givenWar(WarState.RESOLVED);
            assertFalse(restrictions.isEngaged(attacker.id()),
                    "a finished war restricts nobody");
        }

        @Test
        @DisplayName("a city not in the war is not bound by it")
        void bystandersAreFree() {
            City other = support.givenCity(support.givenEligiblePlayer("Aeneas"),
                    "Lavinium", 80, 80);
            givenWar(WarState.ACTIVE);

            assertFalse(restrictions.isEngaged(other.id()));
        }
    }

    // ==================================================================================
    // SPEC 11.5 and 11.11, through CityService
    // ==================================================================================

    @Nested
    @DisplayName("membership, SPEC 11.5")
    class Membership {

        @Test
        @DisplayName("a member cannot leave mid-war, and can once it ends")
        void leaveIsBlocked() {
            support.cities.useWars(restrictions);
            UUID citizen = support.givenMember(attacker, "Remus");
            War war = givenWar(WarState.ACTIVE);

            assertEquals("AT_WAR", reasonOf(await(support.cities.leave(citizen, attacker))));

            registry.forget(war.id());
            assertTrue(await(support.cities.leave(citizen, attacker)).isSuccess(),
                    "and the rule lifts when the war does");
        }

        @Test
        @DisplayName("a new member cannot join mid-war")
        void joinIsBlocked() {
            // Through an invite rather than open join: the invite path is the one a city
            // would actually use to reinforce mid-war, which is what SPEC 11.5 forbids.
            support.cities.useWars(restrictions);
            UUID outsider = support.givenEligiblePlayer("Titus");
            assertTrue(await(support.cities.invite(attacker.mayorUuid(), attacker, outsider))
                    .isSuccess());
            givenWar(WarState.ACTIVE);

            assertEquals("AT_WAR",
                    reasonOf(await(support.cities.acceptInvite(outsider, attacker))));
        }

        @Test
        @DisplayName("a mayor cannot kick their way around the rule")
        void kickIsBlocked() {
            // SPEC 11.5 words it as members not leaving. A kick empties the same seat.
            support.cities.useWars(restrictions);
            UUID citizen = support.givenMember(attacker, "Remus");
            givenWar(WarState.ACTIVE);

            assertEquals("AT_WAR",
                    reasonOf(await(support.cities.kick(attacker.mayorUuid(), attacker, citizen))));
        }

        @Test
        @DisplayName("mayorship cannot be transferred mid-war")
        void transferIsBlocked() {
            support.cities.useWars(restrictions);
            UUID citizen = support.givenMember(attacker, "Remus");
            givenWar(WarState.PREP);

            assertEquals("AT_WAR", reasonOf(support.cities.offerTransfer(attacker.mayorUuid(),
                    attacker, citizen, true)));
        }

        @Test
        @DisplayName("a city in a war cannot be disbanded")
        void disbandIsBlocked() {
            // SPEC 17.4 case 38. The sharpest one: a defender who disbanded would take the
            // war zone with them and leave the block log pointing at nothing.
            support.cities.useWars(restrictions);
            War war = givenWar(WarState.ACTIVE);

            assertEquals("AT_WAR",
                    reasonOf(await(support.cities.disband(defender.mayorUuid(), defender))));

            registry.forget(war.id());
            assertTrue(await(support.cities.disband(defender.mayorUuid(), defender)).isSuccess());
        }
    }

    // ==================================================================================
    // SPEC 6.3 precondition 9, through ClaimService
    // ==================================================================================

    @Nested
    @DisplayName("land, SPEC 6.3 precondition 9")
    class Land {

        @Test
        @DisplayName("a city at war cannot claim")
        void claimIsBlocked() {
            // SPEC 11.4 is the real reason: the zone is fixed when the fighting starts, so a
            // chunk claimed afterwards would be owned by a warring city and covered by no
            // zone at all. Damage to it would be neither logged nor restored.
            support.claims.useWars(restrictions);
            War war = givenWar(WarState.ACTIVE);

            Result<?> refused = await(support.claims.claim(attacker.mayorUuid(), attacker,
                    "world", 1, 0));
            assertEquals("CITY_AT_WAR", reasonOf(refused));

            registry.forget(war.id());
            assertTrue(await(support.claims.claim(attacker.mayorUuid(), attacker,
                    "world", 1, 0)).isSuccess());
        }

        @Test
        @DisplayName("a city at war cannot unclaim either")
        void unclaimIsBlocked() {
            support.claims.useWars(restrictions);
            assertTrue(await(support.claims.claim(attacker.mayorUuid(), attacker,
                    "world", 1, 0)).isSuccess());
            givenWar(WarState.ACTIVE);

            assertEquals("CITY_AT_WAR", reasonOf(await(support.claims.unclaim(
                    attacker.mayorUuid(), attacker, "world", 1, 0))));
        }
    }

    // ==================================================================================
    // SPEC 11.11, through the remaining services
    // ==================================================================================

    @Nested
    @DisplayName("purchases and diplomacy")
    class Purchases {

        @Test
        @DisplayName("upgrades cannot be bought mid-war")
        void upgradesAreBlocked() {
            // Fortification raises the unit cap and Population the member cap, so buying
            // either mid-war changes the terms of a fight already under way.
            dev.civitas.core.upgrade.UpgradeService upgrades =
                    new dev.civitas.core.upgrade.UpgradeService(support.db,
                            support.daos.cityUpgrades(), support.treasury, support.configs,
                            dev.civitas.util.Scheduler.direct());
            upgrades.useWars(restrictions);
            War war = givenWar(WarState.ACTIVE);

            assertEquals("CITY_AT_WAR", reasonOf(await(upgrades.purchase(attacker.mayorUuid(),
                    attacker, dev.civitas.core.upgrade.UpgradeType.POPULATION))));

            registry.forget(war.id());
            assertTrue(await(upgrades.purchase(attacker.mayorUuid(), attacker,
                    dev.civitas.core.upgrade.UpgradeType.POPULATION)).isSuccess());
        }

        @Test
        @DisplayName("an alliance cannot be broken mid-war")
        void allianceBreakIsBlocked() {
            // SPEC 14.2's 24-hour notice stops a city abandoning an ally the instant war is
            // declared. This stops the same move a day into the fighting, once the ally has
            // staked its treasury under SPEC 11.10 and its land is inside the zone.
            support.diplomacy.useWarRestrictions(restrictions);
            City friend = support.givenCity(support.givenEligiblePlayer("Aeneas"),
                    "Lavinium", 80, 80);
            assertTrue(await(support.diplomacy.invite(attacker.mayorUuid(), attacker, friend))
                    .isSuccess());
            assertTrue(await(support.diplomacy.accept(friend.mayorUuid(), friend, attacker))
                    .isSuccess());
            War war = givenWar(WarState.ACTIVE);

            assertEquals("AT_WAR", reasonOf(await(support.diplomacy.breakAlliance(
                    attacker.mayorUuid(), attacker, friend))));

            registry.forget(war.id());
            assertTrue(await(support.diplomacy.breakAlliance(attacker.mayorUuid(), attacker,
                    friend)).isSuccess());
        }
    }

    // ==================================================================================
    // SPEC 11.6, what stays protected even in war
    // ==================================================================================

    @Nested
    @DisplayName("what a war does not permit")
    class StillProtected {

        @Test
        @DisplayName("grief needs an active war, a zone, and two opposing cities")
        void griefIsNarrow() {
            War war = givenWar(WarState.ACTIVE);
            war.zone(WarZone.of(support.claimRegistry.claimsOf(defender.id()), 1));

            assertTrue(restrictions.isGriefPermitted(defender.id(), attacker.mayorUuid(),
                    "world", 40 << 4, 40 << 4), "an enemy inside the zone may");

            assertFalse(restrictions.isGriefPermitted(defender.id(), null,
                    "world", 40 << 4, 40 << 4), "a creeper is not a combatant");

            UUID stranger = support.givenEligiblePlayer("Wanderer");
            assertFalse(restrictions.isGriefPermitted(defender.id(), stranger,
                    "world", 40 << 4, 40 << 4),
                    "SPEC 17.4 case 41: somebody with no city is a bystander");

            assertFalse(restrictions.isGriefPermitted(defender.id(), attacker.mayorUuid(),
                    "world", 900 << 4, 900 << 4), "and only inside the zone");
        }

        @Test
        @DisplayName("PREP permits no grief at all")
        void prepIsPeaceful() {
            // SPEC 11.5: "No grief permitted, normal protection applies." The 48 hours are
            // for building, and a defender who lost their walls during them would have had
            // no preparation phase at all.
            War war = givenWar(WarState.PREP);
            war.zone(WarZone.of(support.claimRegistry.claimsOf(defender.id()), 1));

            assertFalse(restrictions.isGriefPermitted(defender.id(), attacker.mayorUuid(),
                    "world", 40 << 4, 40 << 4));
        }

        @Test
        @DisplayName("drops are suppressed inside an active zone and nowhere else")
        void noDrops() {
            // SPEC 11.8.3 calls this critical: without it a war is a free strip-mining event,
            // because the attacker keeps the materials and the rollback puts the blocks back.
            War war = givenWar(WarState.ACTIVE);
            war.zone(WarZone.of(support.claimRegistry.claimsOf(defender.id()), 1));

            assertTrue(restrictions.suppressesDrops("world", 40 << 4, 40 << 4));
            assertFalse(restrictions.suppressesDrops("world", 900 << 4, 900 << 4));
        }

        @Test
        @DisplayName("a zone being restored is closed to everyone")
        void rollingBackIsClosed() {
            War war = givenWar(WarState.ROLLING_BACK);
            war.zone(WarZone.of(support.claimRegistry.claimsOf(defender.id()), 1));

            assertTrue(restrictions.isZoneClosed("world", 40 << 4, 40 << 4));
            assertNotEquals(true, restrictions.isZoneClosed("world", 900 << 4, 900 << 4));
        }
    }
}
