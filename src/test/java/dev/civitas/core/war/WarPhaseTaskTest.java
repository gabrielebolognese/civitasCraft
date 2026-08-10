package dev.civitas.core.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.income.StipendTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 11.2's lifecycle, and the catch-up that makes it survive an outage.
 *
 * <p>The tests that earn their place are the ones where the clock jumps. A war's phases are
 * wall-clock windows and a server is not obliged to be running when one ends; an operator who
 * shuts down on day three of a seven-day war and returns on day ten must find it over and the
 * damage being restored, not still ACTIVE with the zone open and the log growing.
 */
class WarPhaseTaskTest {

    @TempDir
    Path directory;

    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final long START = 1_000_000_000L;

    private CityTestSupport support;
    private WarRegistry registry;
    private WarService wars;
    private WarPhaseTask task;
    private final List<Integer> rolledBack = new ArrayList<>();

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        registry = new WarRegistry(support.daos.wars());
        wars = new WarService(support.db, support.daos, support.registry, support.claimRegistry,
                support.diplomacyRegistry, registry, support.treasury, support.configs,
                dev.civitas.util.Scheduler.direct());

        task = new WarPhaseTask(wars, registry, support.daos.wars(), support.registry,
                Evacuation.empty(support.registry),
                war -> rolledBack.add(war.id()),
                silentNotifier(), support.configs, CityTestSupport.quietLogger(),
                () -> START);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private static StipendTask.Notifier silentNotifier() {
        return new StipendTask.Notifier() {
            @Override
            public void tell(UUID player, String key,
                             net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra) {
                // Nobody is online in a test.
            }
        };
    }

    /** A war on the books, in memory and in storage, without going through declaration. */
    private War givenWar(WarState state) {
        long prepEnds = START + 48 * HOUR;
        long warEnds = prepEnds + 7 * DAY;
        int id = CityTestSupport.await(support.daos.wars().insert(
                new dev.civitas.storage.row.WarRow(0, 1, 2, START, prepEnds, warEnds,
                        state.key(), 0, 0, null, new BigDecimal("50000.00"), null, null, 0)));
        War war = new War(id, 1, 2, START, prepEnds, warEnds, state, new BigDecimal("50000.00"));
        registry.remember(war);
        return war;
    }

    /**
     * The state on disk, waited for.
     *
     * <p>The task writes the state without blocking on it, because it runs on the server
     * thread and SPEC 2.1 forbids waiting on storage there. The in-memory state is what the
     * rest of the plugin reads and is asserted directly; this polls for the durable copy.
     */
    private String storedState(int warId, String expected) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String state = CityTestSupport.await(support.daos.wars().findById(warId))
                    .orElseThrow().state();
            if (expected.equals(state)) {
                return state;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return CityTestSupport.await(support.daos.wars().findById(warId)).orElseThrow().state();
    }

    // ==================================================================================
    // The ordinary path
    // ==================================================================================

    @Nested
    @DisplayName("the lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("a freshly declared war stays declared through the decline window")
        void staysDeclaredDuringTheWindow() {
            // SPEC 11.3 gives the defender six hours to walk away. Nothing may be destroyed
            // in that time, so nothing may advance either.
            War war = givenWar(WarState.DECLARED);

            task.tick(START + HOUR);

            assertEquals(WarState.DECLARED, war.state());
        }

        @Test
        @DisplayName("once the decline window closes it moves to PREP")
        void movesToPrep() {
            War war = givenWar(WarState.DECLARED);

            task.tick(START + 7 * HOUR);

            assertEquals(WarState.PREP, war.state());
            assertEquals("PREP", storedState(war.id(), "PREP"));
        }

        @Test
        @DisplayName("PREP holds for its full 48 hours")
        void prepHolds() {
            War war = givenWar(WarState.PREP);

            task.tick(START + 47 * HOUR);

            assertEquals(WarState.PREP, war.state());
        }

        @Test
        @DisplayName("PREP ending starts the war and locks the zone")
        void prepEndsIntoActive() {
            // The moment damage starts being logged. SPEC 11.4 fixes the zone here and never
            // recomputes it.
            War war = givenWar(WarState.PREP);

            task.tick(START + 49 * HOUR);

            assertEquals(WarState.ACTIVE, war.state());
            assertEquals("ACTIVE", storedState(war.id(), "ACTIVE"));
        }

        @Test
        @DisplayName("the war ends into a rollback, not into nothing")
        void activeEndsIntoRollback() {
            War war = givenWar(WarState.ACTIVE);

            task.tick(START + 48 * HOUR + 8 * DAY);

            assertEquals(WarState.ROLLING_BACK, war.state());
            assertEquals("ROLLING_BACK", storedState(war.id(), "ROLLING_BACK"));
            assertTrue(rolledBack.contains(war.id()),
                    "the rollback engine must actually be started, or the damage stays");
        }
    }

    // ==================================================================================
    // Catching up, which is what the whole design is for
    // ==================================================================================

    @Nested
    @DisplayName("catching up after an outage")
    class CatchUp {

        @Test
        @DisplayName("a war that should have finished while the server was down is rolled back")
        void skipsStraightToRollback() {
            // Declared, then the server is away for a fortnight. Every boundary passed
            // unattended and the war must still end correctly.
            War war = givenWar(WarState.DECLARED);

            task.tick(START + 20 * DAY);

            assertEquals(WarState.ROLLING_BACK, war.state());
            assertTrue(rolledBack.contains(war.id()));
        }

        @Test
        @DisplayName("and the zone is still computed on the way past")
        void computesTheZoneEvenWhenSkipping() {
            // The reason the task walks every phase rather than jumping to the last: a war
            // that reached ACTIVE without a zone would log nothing and restore nothing.
            support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
            War war = givenWar(WarState.DECLARED);
            registry.forget(war.id());
            War withCity = new War(war.id(), support.registry.cityByName("Roma").orElseThrow().id(),
                    2, START, war.prepEndsAt(), war.warEndsAt(), WarState.DECLARED,
                    new BigDecimal("50000.00"));
            registry.remember(withCity);

            task.tick(START + 20 * DAY);

            assertFalse(withCity.zone().isEmpty(),
                    "a war must never reach ACTIVE without a zone, however fast it got there");
        }

        @Test
        @DisplayName("a war already in PREP that missed its start still ends properly")
        void prepStraightToRollback() {
            War war = givenWar(WarState.PREP);

            task.tick(START + 30 * DAY);

            assertEquals(WarState.ROLLING_BACK, war.state());
            assertTrue(rolledBack.contains(war.id()));
        }

        @Test
        @DisplayName("nothing happens when the recorded phase already matches the clock")
        void quietWhenNothingIsDue() {
            War war = givenWar(WarState.ACTIVE);

            task.tick(START + 50 * HOUR);

            assertEquals(WarState.ACTIVE, war.state());
            assertTrue(rolledBack.isEmpty());
        }
    }

    // ==================================================================================
    // States the clock does not own
    // ==================================================================================

    @Nested
    @DisplayName("states the clock must not touch")
    class Terminal {

        @Test
        @DisplayName("a cancelled war is left alone however much time passes")
        void cancelledStaysCancelled() {
            // A declined war is over. Advancing it would restart a fight nobody agreed to.
            War war = givenWar(WarState.CANCELLED);

            task.tick(START + 100 * DAY);

            assertEquals(WarState.CANCELLED, war.state());
            assertTrue(rolledBack.isEmpty());
        }

        @Test
        @DisplayName("a war already rolling back is not restarted")
        void rollingBackIsNotRestarted() {
            // The engine owns that transition. Starting it twice would replay a log that is
            // half applied.
            War war = givenWar(WarState.ROLLING_BACK);

            task.tick(START + 100 * DAY);
            task.tick(START + 200 * DAY);

            assertEquals(WarState.ROLLING_BACK, war.state());
            assertTrue(rolledBack.isEmpty(), "the trigger must not fire again");
        }

        @Test
        @DisplayName("a failed rollback is never advanced past")
        void failedStaysFailed() {
            // SPEC 11.8.5: it must never silently give up and reopen a griefed city.
            War war = givenWar(WarState.ROLLBACK_FAILED);

            task.tick(START + 100 * DAY);

            assertEquals(WarState.ROLLBACK_FAILED, war.state());
        }
    }

    @Test
    @DisplayName("the decline window is read from war.yml")
    void declineWindowIsConfigured() {
        assertEquals(6 * HOUR, task.declineWindowMillis());
    }
}
