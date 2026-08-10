package dev.civitas.core.war;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.storage.row.WarRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 11.10, allies joining a war.
 *
 * <p>The rule that carries the most weight is the PREP-only window, and not for the reason it
 * first appears. SPEC 11.4 fixes the war zone when ACTIVE begins, so a city that joined
 * afterwards would have its land inside nobody's zone: damage to it would be neither logged by
 * M17 nor restored by M18. The window is what keeps SPEC 1.2's promise honest for allies.
 */
class WarAlliesTest {

    @TempDir
    Path directory;

    private static final long NOW = System.currentTimeMillis();
    private static final BigDecimal WAGER = new BigDecimal("50000.00");

    private CityTestSupport support;
    private WarRegistry registry;
    private WarAllies allies;
    private City attacker;
    private City defender;
    private City friend;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        registry = new WarRegistry(support.daos.wars());
        allies = new WarAllies(support.db, support.daos, support.registry,
                support.diplomacyRegistry, registry, support.treasury, support.configs,
                Scheduler.direct());

        attacker = support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
        defender = support.givenCity(support.givenEligiblePlayer("Dido"), "Carthago", 40, 40);
        friend = support.givenCity(support.givenEligiblePlayer("Aeneas"), "Lavinium", 80, 80);
        fund(friend, "100000.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private void fund(City city, String amount) {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal(amount)));
        city.setTreasury(new BigDecimal(amount));
    }

    /** Puts two cities into a settled alliance, through the real service. */
    private void ally(City one, City other) {
        assertTrue(await(support.diplomacy.invite(one.mayorUuid(), one, other)).isSuccess());
        Result<dev.civitas.core.diplomacy.Alliance> accepted =
                await(support.diplomacy.accept(other.mayorUuid(), other, one));
        assertTrue(accepted.isSuccess(), reasonOf(accepted));
        assertTrue(support.diplomacyRegistry.areAllied(one.id(), other.id()));
    }

    private War givenWar(WarState state) {
        int id = await(support.daos.wars().insert(new WarRow(0, attacker.id(), defender.id(),
                NOW, NOW + 1000L, NOW + 2000L, state.key(), 0, 0, null, WAGER, null, null, 0)));
        War war = new War(id, attacker.id(), defender.id(), NOW, NOW + 1000L, NOW + 2000L,
                state, WAGER);
        registry.remember(war);
        return war;
    }

    // ==================================================================================
    // Joining
    // ==================================================================================

    @Nested
    @DisplayName("joining an ally's war")
    class Joining {

        @Test
        @DisplayName("an ally may join during PREP")
        void joinsDuringPrep() {
            ally(friend, attacker);
            War war = givenWar(WarState.PREP);

            Result<War> joined = await(allies.join(friend.mayorUuid(), friend, war, true, NOW));

            assertTrue(joined.isSuccess(), reasonOf(joined));
            assertTrue(war.isAttackerSide(friend.id()));
            assertTrue(war.involves(friend.id()));
        }

        @Test
        @DisplayName("joining escrows a quarter of the primary wager, once")
        void escrowsTheStake() {
            ally(friend, attacker);
            War war = givenWar(WarState.PREP);
            BigDecimal before = friend.treasury();

            await(allies.join(friend.mayorUuid(), friend, war, true, NOW));

            BigDecimal stored = await(support.daos.cities().findById(friend.id()))
                    .orElseThrow().treasury();
            assertEquals(0, before.subtract(new BigDecimal("12500.00")).compareTo(stored),
                    "SPEC 11.10 stakes 25% of the primary wager");
        }

        @Test
        @DisplayName("joining is refused once the fighting has started")
        void refusedDuringActive() {
            // The window that keeps an ally's land inside a zone. See the class note.
            ally(friend, attacker);
            War war = givenWar(WarState.ACTIVE);

            assertEquals("NOT_PREP",
                    reasonOf(await(allies.join(friend.mayorUuid(), friend, war, true, NOW))));
        }

        @Test
        @DisplayName("a city that is not allied to that side cannot join it")
        void refusedWhenNotAllied() {
            War war = givenWar(WarState.PREP);

            assertEquals("NOT_ALLIED",
                    reasonOf(await(allies.join(friend.mayorUuid(), friend, war, true, NOW))));
        }

        @Test
        @DisplayName("a city may not join against its own ally")
        void refusedWhenAlliedWithTheEnemy() {
            // SPEC 11.10 states this directly. Without it a city could help one ally attack
            // another and keep both alliances.
            ally(friend, attacker);
            ally(friend, defender);
            War war = givenWar(WarState.PREP);

            assertEquals("ALLIED_WITH_ENEMY",
                    reasonOf(await(allies.join(friend.mayorUuid(), friend, war, true, NOW))));
        }

        @Test
        @DisplayName("joining twice is refused")
        void refusedWhenAlreadyIn() {
            ally(friend, attacker);
            War war = givenWar(WarState.PREP);
            await(allies.join(friend.mayorUuid(), friend, war, true, NOW));

            assertEquals("ALREADY_IN",
                    reasonOf(await(allies.join(friend.mayorUuid(), friend, war, true, NOW))));
        }

        @Test
        @DisplayName("a city already fighting its own war cannot join another")
        void refusedWhenAlreadyAtWar() {
            // SPEC 17.4 case 52 allows an ally to be in a different war; SPEC 11.3 still gives
            // every city one war at a time, and an ally is no exception.
            ally(friend, attacker);
            War ownWar = new War(99, friend.id(), 12345, NOW, NOW + 1000L, NOW + 2000L,
                    WarState.ACTIVE, WAGER);
            registry.remember(ownWar);
            War war = givenWar(WarState.PREP);

            assertEquals("ALREADY_AT_WAR",
                    reasonOf(await(allies.join(friend.mayorUuid(), friend, war, true, NOW))));
        }

        @Test
        @DisplayName("a treasury that cannot cover the stake refuses the join")
        void refusedWhenPoor() {
            ally(friend, attacker);
            fund(friend, "100.00");
            War war = givenWar(WarState.PREP);

            assertEquals("TREASURY_SHORT",
                    reasonOf(await(allies.join(friend.mayorUuid(), friend, war, true, NOW))));
        }

        @Test
        @DisplayName("a member without MANAGE_DIPLOMACY cannot commit the city")
        void needsPermission() {
            ally(friend, attacker);
            War war = givenWar(WarState.PREP);
            java.util.UUID citizen = support.givenMember(friend, "Ascanius");

            assertEquals("NO_PERMISSION",
                    reasonOf(await(allies.join(citizen, friend, war, true, NOW))));
        }
    }

    // ==================================================================================
    // Sides
    // ==================================================================================

    @Nested
    @DisplayName("once joined")
    class Sides {

        @Test
        @DisplayName("an ally counts as an enemy of the other side")
        void alliesAreEnemies() {
            ally(friend, attacker);
            War war = givenWar(WarState.PREP);
            await(allies.join(friend.mayorUuid(), friend, war, true, NOW));

            assertTrue(war.areEnemies(friend.id(), defender.id()),
                    "an ally must be able to fight, and be fought");
            assertFalse(war.areEnemies(friend.id(), attacker.id()),
                    "and must not be an enemy of the side it joined");
        }

        @Test
        @DisplayName("an ally's land is part of the zone the war is fought over")
        void allyLandJoinsTheZone() {
            // SPEC 11.10: "Allied claims become part of the war zone and are subject to grief
            // and rollback." The zone is built when ACTIVE begins, so a PREP join is included.
            ally(friend, attacker);
            War war = givenWar(WarState.PREP);
            await(allies.join(friend.mayorUuid(), friend, war, true, NOW));

            WarService wars = new WarService(support.db, support.daos, support.registry,
                    support.claimRegistry, support.diplomacyRegistry, registry, support.treasury,
                    support.configs, Scheduler.direct());
            WarZone zone = wars.computeZone(war);

            assertTrue(zone.containsChunk("world", 80, 80),
                    "the ally's core chunk should be inside the zone");
        }

        @Test
        @DisplayName("an alliance formed across the sides afterwards is found")
        void crossSideAlliancesAreDetected() {
            // The route SPEC 11.10's second sentence really describes: nothing forbids two
            // cities allying after they are already on opposite sides.
            War war = givenWar(WarState.PREP);
            ally(attacker, defender);

            assertEquals(1, allies.breakCrossSideAlliances(war).size());
        }

        @Test
        @DisplayName("a war with no cross-side alliance finds none")
        void noFalsePositives() {
            ally(friend, attacker);
            War war = givenWar(WarState.PREP);
            await(allies.join(friend.mayorUuid(), friend, war, true, NOW));

            assertTrue(allies.breakCrossSideAlliances(war).isEmpty());
        }
    }

    @Test
    @DisplayName("the ally stake is read from war.yml")
    void stakeIsConfigured() {
        War war = givenWar(WarState.PREP);

        assertEquals(0, new BigDecimal("12500.00").compareTo(allies.allyStake(war)));
    }
}
