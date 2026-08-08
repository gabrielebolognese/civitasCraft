package dev.civitas.core.abuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 21.10.5's placed-block cache, and the two quest exploits it closes.
 *
 * <p>SPEC 21.4 F9 and F10 are the same exploit twice: a quest that counts an action, completed
 * by undoing the action and repeating it. The cache is what makes the second placement of the
 * same block worth nothing.
 */
class PlacedBlockCacheTest {

    @TempDir
    Path directory;

    private ConfigManager configs;
    private PlacedBlockCache cache;

    private static final String WORLD = "world";
    private static final long NOON = 1_754_000_000_000L;

    private static java.util.logging.Logger quietLogger() {
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger(PlacedBlockCacheTest.class.getName());
        logger.setLevel(java.util.logging.Level.OFF);
        return logger;
    }

    @BeforeEach
    void setUp() {
        configs = new ConfigManager(dev.civitas.config.PluginResources.ofClasspath(
                directory.resolve("plugin").toFile(), quietLogger()));
        configs.loadAll();
        cache = new PlacedBlockCache(configs);
    }

    // ==================================================================================
    // F9, the building loop
    // ==================================================================================

    @Nested
    @DisplayName("F9: place and break the same block")
    class BuildingLoop {

        @Test
        @DisplayName("the first placement counts")
        void firstPlacementCounts() {
            assertTrue(cache.onPlace(WORLD, 10, 64, 10, NOON));
        }

        @Test
        @DisplayName("re-filling a hole you just made does not count")
        void refillDoesNotCount() {
            // "'Place 512 blocks' completed by placing and breaking one block 512 times."
            assertTrue(cache.onPlace(WORLD, 10, 64, 10, NOON));
            cache.onBreak(WORLD, 10, 64, 10, NOON + 1_000);

            assertFalse(cache.onPlace(WORLD, 10, 64, 10, NOON + 2_000),
                    "the second placement is the loop and must earn nothing");
        }

        @Test
        @DisplayName("the loop stays closed however many times it is run")
        void loopStaysClosed() {
            int counted = 0;
            for (int i = 0; i < 50; i++) {
                if (cache.onPlace(WORLD, 10, 64, 10, NOON + i * 1_000L)) {
                    counted++;
                }
                cache.onBreak(WORLD, 10, 64, 10, NOON + i * 1_000L + 500);
            }

            assertEquals(1, counted,
                    "fifty cycles of one block should earn exactly the first placement");
        }

        @Test
        @DisplayName("a different position is a different block")
        void neighbouringPositionsAreIndependent() {
            // The rule must not punish someone building a wall one block at a time.
            cache.onPlace(WORLD, 10, 64, 10, NOON);
            cache.onBreak(WORLD, 10, 64, 10, NOON);

            assertTrue(cache.onPlace(WORLD, 11, 64, 10, NOON), "x+1");
            assertTrue(cache.onPlace(WORLD, 10, 65, 10, NOON), "y+1");
            assertTrue(cache.onPlace(WORLD, 10, 64, 11, NOON), "z+1");
        }

        @Test
        @DisplayName("the same coordinates in another world are another block")
        void worldsAreIndependent() {
            cache.onPlace(WORLD, 10, 64, 10, NOON);
            cache.onBreak(WORLD, 10, 64, 10, NOON);

            assertTrue(cache.onPlace("world_nether", 10, 64, 10, NOON));
        }
    }

    // ==================================================================================
    // F10, the mining loop
    // ==================================================================================

    @Nested
    @DisplayName("F10: place an ore and mine it again")
    class MiningLoop {

        @Test
        @DisplayName("breaking something a player placed does not count")
        void breakingOwnPlacementDoesNotCount() {
            // "'Mine 128 iron ore' completed by placing and mining the same ore repeatedly."
            cache.onPlace(WORLD, 20, 30, 20, NOON);

            assertFalse(cache.onBreak(WORLD, 20, 30, 20, NOON + 1_000),
                    "a player-placed ore is not mining");
        }

        @Test
        @DisplayName("breaking something the world generated does count")
        void breakingNaturalBlockCounts() {
            assertTrue(cache.onBreak(WORLD, 20, 30, 20, NOON),
                    "nothing was placed here, so this is real mining");
        }

        @Test
        @DisplayName("the loop stays closed however many times it is run")
        void loopStaysClosed() {
            int counted = 0;
            for (int i = 0; i < 50; i++) {
                cache.onPlace(WORLD, 20, 30, 20, NOON + i * 1_000L);
                if (cache.onBreak(WORLD, 20, 30, 20, NOON + i * 1_000L + 500)) {
                    counted++;
                }
            }

            assertEquals(0, counted, "not one of fifty re-mines should have counted");
        }
    }

    // ==================================================================================
    // The TTL
    // ==================================================================================

    @Nested
    @DisplayName("the 24-hour TTL, SPEC 21.10.5")
    class Ttl {

        @Test
        @DisplayName("the memory expires, so a real rebuild a day later counts")
        void memoryExpires() {
            cache.onPlace(WORLD, 10, 64, 10, NOON);
            cache.onBreak(WORLD, 10, 64, 10, NOON);

            long nextDay = NOON + cache.ttlMillis() + 1;

            assertTrue(cache.onPlace(WORLD, 10, 64, 10, nextDay),
                    "a player genuinely rebuilding tomorrow is not running the F9 loop");
        }

        @Test
        @DisplayName("changing the TTL changes how long the memory lasts")
        void ttlIsConfigurable() {
            configs.get(ConfigFile.ECONOMY).set("anti-abuse.placed-block-cache-ttl-hours", 1);

            cache.onPlace(WORLD, 10, 64, 10, NOON);
            cache.onBreak(WORLD, 10, 64, 10, NOON);

            assertFalse(cache.onPlace(WORLD, 10, 64, 10, NOON + 30 * 60_000L),
                    "half an hour in, still remembered");
            assertTrue(cache.onPlace(WORLD, 10, 64, 10, NOON + 2 * 3_600_000L),
                    "two hours in, forgotten");
        }

        @Test
        @DisplayName("a sweep drops expired positions and the chunks left empty")
        void sweepReclaims() {
            cache.onPlace(WORLD, 10, 64, 10, NOON);
            assertEquals(1, cache.trackedChunks());

            assertEquals(0, cache.sweep(NOON + 1_000), "nothing has expired yet");
            assertEquals(1, cache.trackedChunks());

            assertEquals(1, cache.sweep(NOON + cache.ttlMillis() + 1));
            assertEquals(0, cache.trackedChunks(), "the empty chunk went with its positions");
        }
    }

    // ==================================================================================
    // The bound
    // ==================================================================================

    @Nested
    @DisplayName("memory-bounded with LRU eviction, SPEC 21.10.5")
    class Bounded {

        @Test
        @DisplayName("it stops growing at the configured chunk count")
        void evictsPastTheCap() {
            configs.get(ConfigFile.ECONOMY).set("anti-abuse.placed-block-cache-max-chunks", 8);

            for (int chunk = 0; chunk < 100; chunk++) {
                cache.onPlace(WORLD, chunk * 16, 64, 0, NOON);
            }

            assertTrue(cache.trackedChunks() <= 8,
                    "held " + cache.trackedChunks() + " chunks against a cap of 8");
        }

        @Test
        @DisplayName("eviction forgets, which lets a block count rather than refusing it")
        void evictionFailsTowardCountingIt() {
            // The safe direction. A forgotten position earns quest credit it marginally
            // should not have; the other direction would rob a player of credit they earned,
            // silently, because their build scrolled out of a cache they cannot see.
            configs.get(ConfigFile.ECONOMY).set("anti-abuse.placed-block-cache-max-chunks", 2);

            cache.onPlace(WORLD, 0, 64, 0, NOON);
            cache.onBreak(WORLD, 0, 64, 0, NOON);
            for (int chunk = 1; chunk < 10; chunk++) {
                cache.onPlace(WORLD, chunk * 16, 64, 0, NOON);
            }

            assertTrue(cache.onPlace(WORLD, 0, 64, 0, NOON),
                    "the evicted position counts again rather than being refused forever");
        }

        @Test
        @DisplayName("the working chunk survives while quiet ones are evicted")
        void leastRecentlyUsedGoesFirst() {
            configs.get(ConfigFile.ECONOMY).set("anti-abuse.placed-block-cache-max-chunks", 4);

            // A player working in one chunk, while other chunks are touched once each.
            cache.onPlace(WORLD, 0, 64, 0, NOON);
            for (int chunk = 1; chunk < 4; chunk++) {
                cache.onPlace(WORLD, chunk * 16, 64, 0, NOON);
                // Keep touching the first chunk, so it is never the eldest.
                cache.onPlace(WORLD, 1, 64, 1, NOON);
            }
            cache.onPlace(WORLD, 999 * 16, 64, 0, NOON);

            assertTrue(cache.wasPlayerPlaced(WORLD, 1, 64, 1, NOON),
                    "the chunk being worked in was evicted while idle ones survived");
        }
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    @Nested
    @DisplayName("reading, for contest verification")
    class Reading {

        @Test
        @DisplayName("it can say whether a player put a block somewhere")
        void reportsPlacement() {
            assertFalse(cache.wasPlayerPlaced(WORLD, 5, 70, 5, NOON));
            cache.onPlace(WORLD, 5, 70, 5, NOON);
            assertTrue(cache.wasPlayerPlaced(WORLD, 5, 70, 5, NOON));
        }

        @Test
        @DisplayName("breaking clears the placement rather than leaving both true")
        void breakClearsPlacement() {
            cache.onPlace(WORLD, 5, 70, 5, NOON);
            cache.onBreak(WORLD, 5, 70, 5, NOON);

            assertFalse(cache.wasPlayerPlaced(WORLD, 5, 70, 5, NOON),
                    "there is no block here any more, so nobody placed the one that is here");
            assertTrue(cache.wasRecentlyBroken(WORLD, 5, 70, 5, NOON));
        }

        @Test
        @DisplayName("placing over a break clears the break")
        void placeClearsBreak() {
            cache.onBreak(WORLD, 5, 70, 5, NOON);
            cache.onPlace(WORLD, 5, 70, 5, NOON);

            assertFalse(cache.wasRecentlyBroken(WORLD, 5, 70, 5, NOON));
            assertTrue(cache.wasPlayerPlaced(WORLD, 5, 70, 5, NOON));
        }

        @Test
        @DisplayName("clear forgets everything")
        void clearWorks() {
            cache.onPlace(WORLD, 5, 70, 5, NOON);
            cache.clear();

            assertEquals(0, cache.trackedChunks());
            assertFalse(cache.wasPlayerPlaced(WORLD, 5, 70, 5, NOON));
        }
    }
}
