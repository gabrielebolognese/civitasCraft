package dev.civitas.core.income;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SPEC 4.2.1 anti-AFK check.
 *
 * <p>SPEC 4.2.1 states the calibration precisely: "deliberately loose enough that a genuinely
 * active farmer always qualifies, and tight enough that a water-clock AFK machine does not.
 * Rotating in place, jump-clicking, and single-key macros all fail the check." Each of those
 * five sentences is a test below, because that paragraph is the specification of this class
 * and every one of them is a way somebody will try to beat it.
 */
class ActivityTrackerTest {

    @TempDir
    Path directory;

    private ConfigManager configs;
    private ActivityTracker tracker;
    private UUID player;

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("activity-test");
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        configs = new ConfigManager(PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        tracker = new ActivityTracker(configs);
        player = UUID.randomUUID();
    }

    // ==================================================================================
    // The calibration SPEC 4.2.1 names
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 4.2.1's own examples")
    class SpecExamples {

        @Test
        @DisplayName("a genuinely active farmer always qualifies")
        void farmerQualifies() {
            // Walking a field, harvesting, replanting: three kinds without trying.
            tracker.recordMovement(player, 40);
            tracker.record(player, ActivityKind.BROKE_BLOCK);
            tracker.record(player, ActivityKind.PLACED_BLOCK);

            assertTrue(tracker.wasActive(player));
        }

        @Test
        @DisplayName("a water-clock AFK machine does not")
        void waterClockFails() {
            // A boat in flowing water fires a move event every tick, forever, drifting a
            // little each time. Over a fifteen-minute interval that is plenty of distance.
            for (int tick = 0; tick < 20 * 60 * 15; tick++) {
                tracker.recordMovement(player, 0.01);
            }

            assertTrue(tracker.movedBlocks(player) > tracker.requiredDistance(),
                    "it did cover the distance, so the distance alone is not what stops it");
            assertEquals(1, tracker.distinctKinds(player));
            assertFalse(tracker.wasActive(player),
                    "an unbounded number of one kind is still one kind");
        }

        @Test
        @DisplayName("rotating in place fails")
        void rotatingFails() {
            // Looking around moves nothing, so nothing is recorded at all.
            assertEquals(0, tracker.distinctKinds(player));
            assertFalse(tracker.wasActive(player));
        }

        @Test
        @DisplayName("jump-clicking fails")
        void jumpClickingFails() {
            // Jumping covers distance and clicking hits things: two kinds, however long it
            // is left running.
            for (int i = 0; i < 10_000; i++) {
                tracker.recordMovement(player, 1.2);
                tracker.record(player, ActivityKind.FOUGHT);
            }

            assertEquals(2, tracker.distinctKinds(player));
            assertFalse(tracker.wasActive(player), "two is not three, at any volume");
        }

        @Test
        @DisplayName("a single-key macro fails")
        void singleKeyMacroFails() {
            for (int i = 0; i < 100_000; i++) {
                tracker.record(player, ActivityKind.BROKE_BLOCK);
            }

            assertEquals(1, tracker.distinctKinds(player));
            assertFalse(tracker.wasActive(player));
        }
    }

    // ==================================================================================
    // The mechanism
    // ==================================================================================

    @Nested
    @DisplayName("Counting")
    class Counting {

        @Test
        @DisplayName("repeating one kind adds nothing")
        void repeatsDoNotCount() {
            tracker.record(player, ActivityKind.BROKE_BLOCK);
            tracker.record(player, ActivityKind.BROKE_BLOCK);
            tracker.record(player, ActivityKind.BROKE_BLOCK);

            assertEquals(1, tracker.distinctKinds(player));
        }

        @Test
        @DisplayName("exactly three kinds is enough, and two is not")
        void thresholdIsThree() {
            tracker.record(player, ActivityKind.BROKE_BLOCK);
            tracker.record(player, ActivityKind.SPOKE);
            assertFalse(tracker.wasActive(player));

            tracker.record(player, ActivityKind.OPENED_INVENTORY);
            assertTrue(tracker.wasActive(player));
        }

        @Test
        @DisplayName("movement counts once the distance is covered, not before")
        void movementIsADistance() {
            tracker.recordMovement(player, 31.9);
            assertEquals(0, tracker.distinctKinds(player), "just short of 32 blocks");

            tracker.recordMovement(player, 0.2);
            assertEquals(1, tracker.distinctKinds(player));
        }

        @Test
        @DisplayName("distance accumulates across many small steps")
        void distanceAccumulates() {
            for (int step = 0; step < 64; step++) {
                tracker.recordMovement(player, 0.5);
            }

            assertEquals(32.0, tracker.movedBlocks(player), 0.001);
            assertTrue(tracker.kinds(player).contains(ActivityKind.MOVED));
        }

        @Test
        @DisplayName("players are counted separately")
        void perPlayer() {
            UUID other = UUID.randomUUID();
            tracker.record(player, ActivityKind.BROKE_BLOCK);
            tracker.record(player, ActivityKind.SPOKE);
            tracker.record(player, ActivityKind.FOUGHT);

            assertTrue(tracker.wasActive(player));
            assertFalse(tracker.wasActive(other), "one player's activity is not another's");
        }
    }

    // ==================================================================================
    // Intervals
    // ==================================================================================

    @Nested
    @DisplayName("Rolling over")
    class RollOver {

        @Test
        @DisplayName("closing an interval reports it and starts a clean one")
        void rollOverResets() {
            tracker.record(player, ActivityKind.BROKE_BLOCK);
            tracker.record(player, ActivityKind.PLACED_BLOCK);
            tracker.record(player, ActivityKind.SPOKE);

            assertTrue(tracker.rollOver(player));
            assertEquals(0, tracker.distinctKinds(player),
                    "the next interval starts from nothing, so activity cannot be banked");
            assertFalse(tracker.wasActive(player));
        }

        @Test
        @DisplayName("an inactive interval reports inactive and still resets")
        void inactiveRollOver() {
            tracker.record(player, ActivityKind.BROKE_BLOCK);

            assertFalse(tracker.rollOver(player));
            assertEquals(0, tracker.distinctKinds(player));
        }

        @Test
        @DisplayName("a player who logs out is forgotten")
        void forget() {
            tracker.record(player, ActivityKind.BROKE_BLOCK);
            tracker.forget(player);

            assertEquals(0, tracker.distinctKinds(player));
            assertEquals(0, tracker.tracked());
        }
    }

    // ==================================================================================
    // Config
    // ==================================================================================

    @Test
    @DisplayName("the threshold and the distance are config keys, per SPEC 4.2.1")
    void configurable() {
        assertEquals(3, tracker.requiredActions());
        assertEquals(32.0, tracker.requiredDistance(), 0.001);

        configs.get(ConfigFile.ECONOMY).set("income.stipend.required-actions", 2);
        tracker.record(player, ActivityKind.BROKE_BLOCK);
        tracker.record(player, ActivityKind.SPOKE);

        assertTrue(tracker.wasActive(player), "a server may loosen it");
    }
}
