package dev.civitas.core.war;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
 * SPEC 9.4.5's admin paths through the war system.
 *
 * <h2>Why these are separate methods rather than a bypass flag</h2>
 * Every precondition in SPEC 11.3 exists to stop a <em>player</em> doing something. An admin is
 * expected to be able to do it anyway, and expressing that as a flag on the player path would
 * put the bypass inside the branch a player takes — one bug away from being reachable by
 * anybody. A separate method cannot leak into the player path because the player path does not
 * call it.
 *
 * <p>The money is what these tests watch most closely. A cancelled war refunds both wagers in
 * full (SPEC 11.9's "Admin cancelled" row), and a forced ending pays exactly what the same
 * result would have paid had the war run its course. An admin command that quietly minted or
 * destroyed coins would be invisible until an audit, which is precisely the situation SPEC 1.5
 * exists to prevent.
 */
class WarAdminTest {

    @TempDir
    Path directory;

    private static final long NOW = System.currentTimeMillis();
    private static final BigDecimal WAGER = new BigDecimal("50000.00");
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000009");

    private CityTestSupport support;
    private WarRegistry registry;
    private WarService wars;
    private City attacker;
    private City defender;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        registry = new WarRegistry(support.daos.wars());
        wars = new WarService(support.db, support.daos, support.registry, support.claimRegistry,
                support.diplomacyRegistry, registry, support.treasury, support.configs,
                Scheduler.direct());

        attacker = support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
        defender = support.givenCity(support.givenEligiblePlayer("Dido"), "Carthago", 40, 40);
        fund(attacker, "0.00");
        fund(defender, "0.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private void fund(City city, String amount) {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal(amount)));
        city.setTreasury(new BigDecimal(amount));
    }

    private BigDecimal treasuryOf(City city) {
        return await(support.daos.cities().findById(city.id())).orElseThrow().treasury();
    }

    /** A war already escrowed, which is the state every one of these commands acts on. */
    private War givenWar(WarState state) {
        int id = await(support.daos.wars().insert(new WarRow(0, attacker.id(), defender.id(),
                NOW, NOW + 1000L, NOW + 2000L, state.key(), 0, 0, null, WAGER, null, null, 0)));
        War war = new War(id, attacker.id(), defender.id(), NOW, NOW + 1000L, NOW + 2000L,
                state, WAGER);
        registry.remember(war);
        return war;
    }

    // ==================================================================================
    // Cancel
    // ==================================================================================

    @Nested
    @DisplayName("/ca war cancel")
    class Cancel {

        @Test
        @DisplayName("both wagers come back in full")
        void refundsBothInFull() {
            // SPEC 11.9's "Admin cancelled" row. The war did not happen, so nobody should be
            // poorer for it — unlike a draw, where the money also comes back but the war did.
            War war = givenWar(WarState.ACTIVE);

            Result<War> cancelled = await(wars.adminCancel(ADMIN, war.id(), "duplication bug",
                    NOW));

            assertTrue(cancelled.isSuccess(), reasonOf(cancelled));
            assertEquals(0, WAGER.compareTo(treasuryOf(attacker)));
            assertEquals(0, WAGER.compareTo(treasuryOf(defender)));
        }

        @Test
        @DisplayName("a war that was being fought still rolls back")
        void activeCancelStillRestores() {
            // The war did not happen as far as the record is concerned. The damage did.
            War war = givenWar(WarState.ACTIVE);

            await(wars.adminCancel(ADMIN, war.id(), "duplication bug", NOW));

            assertEquals(WarState.ROLLING_BACK, war.state());
        }

        @Test
        @DisplayName("a war still in preparation simply stops")
        void prepCancelJustEnds() {
            War war = givenWar(WarState.PREP);

            await(wars.adminCancel(ADMIN, war.id(), "declared by mistake", NOW));

            assertEquals(WarState.CANCELLED, war.state(), "nothing was destroyed to restore");
        }

        @Test
        @DisplayName("nobody gets a win or a loss on their record")
        void noWinner() {
            War war = givenWar(WarState.ACTIVE);
            war.winnerCityId(attacker.id());

            await(wars.adminCancel(ADMIN, war.id(), "admin error", NOW));

            assertNull(war.winnerCityId(),
                    "a W for a war an admin stopped would be a lie on the leaderboard");
        }

        @Test
        @DisplayName("a war that is already over cannot be cancelled")
        void refusesAFinishedWar() {
            War war = givenWar(WarState.RESOLVED);

            assertEquals("ALREADY_FINISHED",
                    reasonOf(await(wars.adminCancel(ADMIN, war.id(), "too late", NOW))));
        }

        @Test
        @DisplayName("a war that does not exist is refused rather than half-processed")
        void refusesAnUnknownWar() {
            assertEquals("NO_SUCH_WAR",
                    reasonOf(await(wars.adminCancel(ADMIN, 9999, "typo", NOW))));
        }

        @Test
        @DisplayName("the money is conserved: what was escrowed is what comes back")
        void moneyIsConserved() {
            War war = givenWar(WarState.ACTIVE);
            BigDecimal before = treasuryOf(attacker).add(treasuryOf(defender));

            await(wars.adminCancel(ADMIN, war.id(), "bug", NOW));

            assertEquals(0, before.add(WAGER).add(WAGER)
                    .compareTo(treasuryOf(attacker).add(treasuryOf(defender))));
        }
    }

    // ==================================================================================
    // Force end
    // ==================================================================================

    @Nested
    @DisplayName("/ca war forceend")
    class ForceEnd {

        @Test
        @DisplayName("the named side is recorded as the winner, whatever the score said")
        void winnerIsImposed() {
            // The whole point of the command: an admin uses it when the scores do not reflect
            // what happened, so the imposed result must beat the computed one.
            War war = givenWar(WarState.ACTIVE);
            war.addScore(false, 500);

            await(wars.adminForceEnd(ADMIN, war.id(), "attacker", "defender exploited", NOW));

            assertEquals(attacker.id(), war.winnerCityId(),
                    "the defender was ahead and the admin overrode it");
        }

        @Test
        @DisplayName("a draw records no winner")
        void drawHasNoWinner() {
            War war = givenWar(WarState.ACTIVE);

            await(wars.adminForceEnd(ADMIN, war.id(), "draw", "both sides at fault", NOW));

            assertNull(war.winnerCityId());
        }

        @Test
        @DisplayName("the war goes to the restore, not straight to resolved")
        void stillRollsBack() {
            War war = givenWar(WarState.ACTIVE);

            await(wars.adminForceEnd(ADMIN, war.id(), "attacker", "reason", NOW));

            assertEquals(WarState.ROLLING_BACK, war.state(),
                    "SPEC 9.4.5 says forceend runs rollback");
        }

        @Test
        @DisplayName("the stored row agrees with what happened in memory")
        void persisted() {
            // A forced ending that only existed in memory would be undone by the next restart,
            // and the phase task would find a war that should have finished hours ago.
            War war = givenWar(WarState.ACTIVE);

            await(wars.adminForceEnd(ADMIN, war.id(), "defender", "reason", NOW));

            WarRow stored = await(support.daos.wars().findById(war.id())).orElseThrow();
            assertEquals(WarState.ROLLING_BACK.key(), stored.state());
            assertEquals(defender.id(), stored.winnerCityId());
        }

        @Test
        @DisplayName("a finished war is refused")
        void refusesAFinishedWar() {
            War war = givenWar(WarState.RESOLVED);

            assertEquals("ALREADY_FINISHED", reasonOf(
                    await(wars.adminForceEnd(ADMIN, war.id(), "draw", "reason", NOW))));
        }
    }

    // ==================================================================================
    // Extend and immunity
    // ==================================================================================

    @Nested
    @DisplayName("/ca war extend and immunity")
    class ExtendAndImmunity {

        @Test
        @DisplayName("extending moves the end time and keeps everything else")
        void extendKeepsTheWar() {
            War war = givenWar(WarState.ACTIVE);
            war.addScore(true, 30);
            long before = war.warEndsAt();

            Result<War> extended = await(wars.adminExtend(war.id(),
                    TimeUnit.HOURS.toMillis(6)));

            assertTrue(extended.isSuccess(), reasonOf(extended));
            War now = registry.war(war.id()).orElseThrow();
            assertEquals(before + TimeUnit.HOURS.toMillis(6), now.warEndsAt());
            assertEquals(30, now.attackerScore(), "the score is not reset by an extension");
            assertEquals(WarState.ACTIVE, now.state());
        }

        @Test
        @DisplayName("the extension is stored, so a restart does not undo it")
        void extendPersists() {
            War war = givenWar(WarState.ACTIVE);
            long before = war.warEndsAt();

            await(wars.adminExtend(war.id(), TimeUnit.HOURS.toMillis(6)));

            assertEquals(before + TimeUnit.HOURS.toMillis(6),
                    await(support.daos.wars().findById(war.id())).orElseThrow().warEndsAt());
        }

        @Test
        @DisplayName("immunity is granted for the hours asked for")
        void immunityIsGranted() {
            long until = NOW + TimeUnit.HOURS.toMillis(48);

            assertTrue(await(wars.adminGrantImmunity(defender, until)).isSuccess());

            assertEquals(until, await(support.daos.cities().findById(defender.id()))
                    .orElseThrow().warProtectionUntil());
            assertNotEquals(0L, defender.warProtectionUntil(), "and the cache agrees");
        }
    }
}
