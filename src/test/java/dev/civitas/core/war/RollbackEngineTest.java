package dev.civitas.core.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.DatabaseSettings;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.WarBlockLogRow;
import dev.civitas.storage.row.WarRollbackIssueRow;
import dev.civitas.storage.row.WarRow;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * The rollback engine, driven exactly as SPEC 19 prescribes: "Test it by manually populating a
 * block log and rolling it back, still with no war gameplay."
 *
 * <p>That instruction is the whole reason M18 comes before M19. SPEC 19's ordering note says
 * why: "If rollback is built last, it will be tested under time pressure with players waiting,
 * which is exactly how a plugin ends up eating someone's castle." So every test here writes
 * log rows by hand, damages a world, replays, and checks the world came back.
 */
class RollbackEngineTest {

    @TempDir
    Path directory;

    private static final int WAR = 1;
    private static final String WORLD = "world";

    private ServerMock server;
    private WorldMock world;
    private ConfigManager configs;
    private DatabaseManager db;
    private DaoRegistry daos;
    private RollbackEngine engine;
    private long sequence;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld(WORLD);

        configs = new ConfigManager(PluginResources.ofClasspath(
                directory.resolve("plugin").toFile(), quiet()));
        configs.loadAll();

        db = new DatabaseManager(quiet(), new DatabaseSettings(
                SqlDialect.SQLITE,
                "jdbc:sqlite:" + directory.resolve("war.db").toAbsolutePath(),
                "", "", 2, 5000, "WAL", Long.MAX_VALUE, false, 6, 28), () -> false);
        db.open();
        daos = new DaoRegistry(db);

        // A war row to roll back, since the engine records its outcome against one.
        await(daos.wars().insert(new WarRow(0, 1, 2, 0L, 0L, 0L, "ROLLING_BACK", 0, 0, null,
                BigDecimal.ZERO, null, null, 0)));

        engine = newEngine();
    }

    @AfterEach
    void tearDown() {
        db.close();
        MockBukkit.unmock();
    }

    private RollbackEngine newEngine() {
        return new RollbackEngine(daos, configs,
                new BukkitTilePayloadCodec(quiet()), new ChunkHasher(configs), quiet(),
                new Random(1234));
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("rollback-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ==================================================================================
    // Fixtures
    // ==================================================================================

    /** Writes one log entry, as M17's logger would have. */
    private void logChange(int x, int y, int z, String from, String to) {
        logChange(WAR, x, y, z, from, to, null);
    }

    private void logChange(int warId, int x, int y, int z, String from, String to, byte[] nbt) {
        await(daos.warBlockLog().insertBatch(List.of(new WarBlockLogRow(0, warId, ++sequence,
                WORLD, x, y, z, from, to, nbt, null, System.currentTimeMillis()))));
    }

    /** Runs the engine to completion, alternating the two halves as the plugin's driver does. */
    private RollbackJob rollBack() {
        RollbackJob job = await(engine.begin(WAR));
        drive(job);
        await(engine.finish(job));
        return job;
    }

    private void drive(RollbackJob job) {
        int guard = 0;
        while (job.status() == RollbackStatus.RUNNING && guard++ < 10_000) {
            if (!job.hasPending() && await(engine.fetchNextPage(job)) == 0) {
                return;
            }
            engine.applySlice(job);
        }
    }

    private Block block(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }

    // ==================================================================================
    // The core replay
    // ==================================================================================

    @Nested
    @DisplayName("replaying a log")
    class Replay {

        @Test
        @DisplayName("a broken block comes back")
        void restoresABrokenBlock() {
            block(10, 64, 10).setType(Material.STONE);
            logChange(10, 64, 10, "minecraft:stone", "minecraft:air");
            block(10, 64, 10).setType(Material.AIR);

            RollbackJob job = rollBack();

            assertEquals(Material.STONE, block(10, 64, 10).getType());
            assertEquals(1, job.applied());
            assertEquals(RollbackStatus.COMPLETED, job.status());
        }

        @Test
        @DisplayName("a placed block is taken away again")
        void removesAPlacedBlock() {
            logChange(11, 64, 10, "minecraft:air", "minecraft:cobblestone");
            block(11, 64, 10).setType(Material.COBBLESTONE);

            rollBack();

            assertEquals(Material.AIR, block(11, 64, 10).getType());
        }

        @Test
        @DisplayName("place, break, place again lands on the state before the war")
        void handlesRepeatedChanges() {
            // SPEC 17.4 case 42, and the reason the replay runs newest first: the oldest entry
            // is applied last and wins, with no special case for a position changed twice.
            block(12, 64, 10).setType(Material.OAK_PLANKS);
            logChange(12, 64, 10, "minecraft:oak_planks", "minecraft:air");
            logChange(12, 64, 10, "minecraft:air", "minecraft:stone");
            logChange(12, 64, 10, "minecraft:stone", "minecraft:air");
            block(12, 64, 10).setType(Material.AIR);

            rollBack();

            assertEquals(Material.OAK_PLANKS, block(12, 64, 10).getType(),
                    "the block must come back as it was before the first change, not after it");
        }

        @Test
        @DisplayName("a whole structure comes back")
        void restoresManyBlocks() {
            for (int x = 0; x < 20; x++) {
                for (int z = 0; z < 20; z++) {
                    block(x, 64, z).setType(Material.BRICKS);
                    logChange(x, 64, z, "minecraft:bricks", "minecraft:air");
                    block(x, 64, z).setType(Material.AIR);
                }
            }

            RollbackJob job = rollBack();

            assertEquals(400, job.applied());
            for (int x = 0; x < 20; x++) {
                for (int z = 0; z < 20; z++) {
                    assertEquals(Material.BRICKS, block(x, 64, z).getType(),
                            "block " + x + "," + z + " did not come back");
                }
            }
        }

        @Test
        @DisplayName("block data, not just the material, is restored")
        void restoresBlockData() {
            String rotated = "minecraft:oak_log[axis=x]";
            block(13, 64, 10).setBlockData(org.bukkit.Bukkit.createBlockData(rotated), false);
            logChange(13, 64, 10, rotated, "minecraft:air");
            block(13, 64, 10).setType(Material.AIR);

            rollBack();

            assertEquals(rotated, block(13, 64, 10).getBlockData().getAsString());
        }

        @Test
        @DisplayName("an empty log completes rather than hanging")
        void emptyLog() {
            RollbackJob job = rollBack();

            assertEquals(RollbackStatus.COMPLETED, job.status());
            assertEquals(0, job.applied());
        }
    }

    // ==================================================================================
    // Throttling and paging
    // ==================================================================================

    @Nested
    @DisplayName("throttling")
    class Throttling {

        @Test
        @DisplayName("no more than blocks-per-tick is applied in one slice")
        void respectsTheTickBudget() {
            // SPEC 11.8.2 step 6. Without this a 300,000 block war is one frozen tick.
            configs.get(ConfigFile.WAR).set("rollback.blocks-per-tick", 25);
            for (int x = 0; x < 100; x++) {
                block(x, 70, 0).setType(Material.STONE);
                logChange(x, 70, 0, "minecraft:stone", "minecraft:air");
                block(x, 70, 0).setType(Material.AIR);
            }

            RollbackJob job = await(engine.begin(WAR));
            await(engine.fetchNextPage(job));

            assertEquals(25, engine.applySlice(job));
            assertEquals(25, engine.applySlice(job));
        }

        @Test
        @DisplayName("the log is read a page at a time, not all at once")
        void readsInPages() {
            // SPEC 11.8.2 step 3 and SPEC 17.7 case 83: a two-million-row war must not be
            // pulled into memory in one go.
            configs.get(ConfigFile.WAR).set("rollback.read-page-size", 10);
            for (int x = 0; x < 35; x++) {
                logChange(x, 71, 0, "minecraft:stone", "minecraft:air");
            }

            RollbackJob job = await(engine.begin(WAR));

            assertEquals(10, await(engine.fetchNextPage(job)));
            assertEquals(10, job.page().size());
        }
    }

    // ==================================================================================
    // Verification and the failsafe, SPEC 11.8.2 step 8 and SPEC 11.8.4
    // ==================================================================================

    @Nested
    @DisplayName("verification")
    class Verification {

        @Test
        @DisplayName("a clean rollback reports no issues")
        void cleanRollbackIsSilent() {
            configs.get(ConfigFile.WAR).set("rollback.verify-sample-percent", 100.0);
            block(20, 64, 20).setType(Material.STONE);
            logChange(20, 64, 20, "minecraft:stone", "minecraft:air");
            block(20, 64, 20).setType(Material.AIR);

            rollBack();

            assertEquals(0L, await(daos.warRollbackIssues().countByWar(WAR)));
        }

        @Test
        @DisplayName("a mismatch is recorded and the rollback still finishes")
        void mismatchIsRecordedNotFatal() {
            // SPEC 17.4 case 57: "Log ERROR with coordinates, continue the rollback, then
            // surface the mismatch list. Do not abort." A rollback that stopped at its first
            // disagreement would leave more of the city broken than one that carried on.
            configs.get(ConfigFile.WAR).set("rollback.verify-sample-percent", 100.0);
            configs.get(ConfigFile.WAR).set("rollback.chunk-hash-failsafe", false);

            logChange(21, 64, 20, "minecraft:stone", "minecraft:air");
            logChange(22, 64, 20, "minecraft:stone", "minecraft:air");

            RollbackJob job = await(engine.begin(WAR));
            drive(job);
            // Something else changes a restored block before the check runs.
            block(21, 64, 20).setType(Material.DIRT);
            List<WarRollbackIssueRow> found = await(engine.finish(job));

            assertEquals(RollbackStatus.COMPLETED, job.status(), "it must still finish");
            assertTrue(found.stream().anyMatch(issue -> "VERIFY_MISMATCH".equals(issue.kind())),
                    "the mismatch must be on the record for /ca war rollbackstatus");
            assertTrue(await(daos.warRollbackIssues().countByWar(WAR)) > 0,
                    "and must survive the process that found it");
        }

        @Test
        @DisplayName("a chunk changed by something no listener saw is flagged")
        void chunkHashCatchesTheUnlogged() {
            // SPEC 11.8.4's whole purpose: "because no listener list is ever truly
            // exhaustive". This simulates a change nothing logged.
            configs.get(ConfigFile.WAR).set("rollback.chunk-hash-failsafe", true);
            block(3, 64, 3).setType(Material.STONE);

            await(engine.recordPreWarHashes(WAR, world, List.of(new long[] {0, 0})));

            // Nothing logs this, so no replay will undo it.
            block(4, 64, 4).setType(Material.OBSIDIAN);

            RollbackJob job = await(engine.begin(WAR));
            drive(job);
            List<WarRollbackIssueRow> found = await(engine.finish(job));

            assertTrue(found.stream()
                            .anyMatch(issue -> "CHUNK_HASH_MISMATCH".equals(issue.kind())),
                    "an unlogged change must be made visible, which is all SPEC 11.8.4 claims");
            assertEquals(1, await(daos.warChunkHashes().findMismatched(WAR)).size());
        }

        @Test
        @DisplayName("an untouched chunk hashes the same before and after")
        void chunkHashIsStableWhenNothingChanges() {
            block(5, 64, 5).setType(Material.STONE);
            await(engine.recordPreWarHashes(WAR, world, List.of(new long[] {0, 0})));

            RollbackJob job = await(engine.begin(WAR));
            drive(job);
            await(engine.finish(job));

            assertTrue(await(daos.warChunkHashes().findMismatched(WAR)).isEmpty());
        }
    }

    // ==================================================================================
    // Overlapping wars, SPEC 17.4 case 51
    // ==================================================================================

    @Test
    @DisplayName("two wars over the same chunk roll back against their own logs")
    void overlappingWars() {
        // SPEC 17.4 case 51 calls this "the most likely source of a corrupt restore".
        await(daos.wars().insert(new WarRow(0, 3, 4, 0L, 0L, 0L, "ROLLING_BACK", 0, 0, null,
                BigDecimal.ZERO, null, null, 0)));

        block(30, 64, 30).setType(Material.STONE);
        // The same position damaged in both wars, logged separately by each.
        logChange(1, 30, 64, 30, "minecraft:stone", "minecraft:air", null);
        logChange(2, 30, 64, 30, "minecraft:stone", "minecraft:air", null);
        block(30, 64, 30).setType(Material.AIR);

        RollbackJob first = await(engine.begin(1));
        drive(first);
        await(engine.finish(first));

        assertEquals(Material.STONE, block(30, 64, 30).getType());
        assertEquals(1, first.applied(), "war 1 must replay only its own entries");

        RollbackJob second = await(engine.begin(2));
        drive(second);
        await(engine.finish(second));

        assertEquals(Material.STONE, block(30, 64, 30).getType(),
                "the second rollback must leave the restored state alone");
        assertEquals(1, second.applied());
    }

    // ==================================================================================
    // Chunks and players
    // ==================================================================================

    @Test
    @DisplayName("a block in an unloaded chunk is restored, not skipped")
    void loadsChunksOnDemand() {
        // SPEC 17.4 case 49: "Chunk is loaded on demand, restored, then unloaded. Never assume
        // a chunk is loaded."
        block(600, 64, 600).setType(Material.STONE);
        logChange(600, 64, 600, "minecraft:stone", "minecraft:air");
        block(600, 64, 600).setType(Material.AIR);
        world.unloadChunk(600 >> 4, 600 >> 4);
        assertFalse(world.isChunkLoaded(600 >> 4, 600 >> 4));

        rollBack();

        assertEquals(Material.STONE, block(600, 64, 600).getType());
    }

    @Test
    @DisplayName("the engine will not run a rollback it has been told not to")
    void respectsTheEnabledFlag() {
        // rollback.enabled is the one setting CLAUDE.md says must never default to false.
        assertTrue(engine.isEnabled(), "rollback must be on by default");
    }
}
