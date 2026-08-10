package dev.civitas.core.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
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
import dev.civitas.storage.row.WarRow;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * SPEC 11.8.5, crash safety.
 *
 * <p>The three requirements it states, tested one each: a rollback resumes from its checkpoint
 * rather than restarting, an unreadable log fails the war rather than being worked around, and
 * a failed rollback stays failed. The last is the one with teeth: "It must never silently give
 * up and reopen a griefed city."
 */
class RollbackCrashSafetyTest {

    @TempDir
    Path directory;

    private static final int WAR = 1;
    private static final String WORLD = "world";

    private ServerMock server;
    private WorldMock world;
    private ConfigManager configs;
    private DatabaseManager db;
    private DaoRegistry daos;
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

        await(daos.wars().insert(new WarRow(0, 1, 2, 0L, 0L, 0L, "ROLLING_BACK", 0, 0, null,
                BigDecimal.ZERO, null, null, 0)));
    }

    @AfterEach
    void tearDown() {
        if (db.isOpen()) {
            db.close();
        }
        MockBukkit.unmock();
    }

    private RollbackEngine newEngine() {
        return new RollbackEngine(daos, configs, new BukkitTilePayloadCodec(quiet()),
                new ChunkHasher(configs), quiet(), new Random(7));
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("crash-test");
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

    private void logChange(int x, String from, String to) {
        await(daos.warBlockLog().insertBatch(List.of(new WarBlockLogRow(0, WAR, ++sequence,
                WORLD, x, 64, 0, from, to, null, null, 1L))));
    }

    private void drive(RollbackEngine engine, RollbackJob job) {
        int guard = 0;
        while (job.status() == RollbackStatus.RUNNING && guard++ < 10_000) {
            if (!job.hasPending() && await(engine.fetchNextPage(job)) == 0) {
                return;
            }
            engine.applySlice(job);
        }
    }

    // ==================================================================================
    // Resuming, SPEC 11.8.5 and SPEC 17.4 case 37
    // ==================================================================================

    @Test
    @DisplayName("a rollback interrupted partway resumes from its checkpoint")
    void resumesFromCheckpoint() {
        // SPEC 17.4 case 37: "Resume from last checkpoint. Zone stays closed until rollback
        // completes."
        configs.get(ConfigFile.WAR).set("rollback.checkpoint-every-blocks", 10);
        configs.get(ConfigFile.WAR).set("rollback.blocks-per-tick", 10);
        configs.get(ConfigFile.WAR).set("rollback.read-page-size", 10);

        for (int x = 0; x < 60; x++) {
            world.getBlockAt(x, 64, 0).setType(Material.STONE);
            logChange(x, "minecraft:stone", "minecraft:air");
            world.getBlockAt(x, 64, 0).setType(Material.AIR);
        }

        // Start, apply a couple of slices, then stop as a crash would.
        RollbackEngine first = newEngine();
        RollbackJob job = await(first.begin(WAR));
        await(first.fetchNextPage(job));
        first.applySlice(job);
        await(first.fetchNextPage(job));
        first.applySlice(job);
        first.awaitCheckpoints(WAR);

        long checkpoint = await(daos.wars().findById(WAR)).orElseThrow()
                .rollbackCheckpointSequence();
        assertNotEquals(0L, checkpoint, "the engine must have written a checkpoint by now");

        // A fresh engine, as a restarted server would build.
        RollbackEngine restarted = newEngine();
        RollbackJob resumed = await(restarted.begin(WAR));
        assertEquals(checkpoint, resumed.cursor(),
                "a resumed rollback must start from the checkpoint, not from the beginning");

        drive(restarted, resumed);
        await(restarted.finish(resumed));

        for (int x = 0; x < 60; x++) {
            assertEquals(Material.STONE, world.getBlockAt(x, 64, 0).getType(),
                    "block " + x + " was missed across the restart");
        }
    }

    @Test
    @DisplayName("a resumed rollback does not replay what was already applied")
    void resumeDoesNotDoubleApply() {
        configs.get(ConfigFile.WAR).set("rollback.checkpoint-every-blocks", 5);
        configs.get(ConfigFile.WAR).set("rollback.blocks-per-tick", 5);
        configs.get(ConfigFile.WAR).set("rollback.read-page-size", 5);

        for (int x = 0; x < 20; x++) {
            logChange(x, "minecraft:stone", "minecraft:air");
        }

        RollbackEngine first = newEngine();
        RollbackJob job = await(first.begin(WAR));
        await(first.fetchNextPage(job));
        first.applySlice(job);
        long appliedBefore = job.applied();

        RollbackEngine restarted = newEngine();
        RollbackJob resumed = await(restarted.begin(WAR));
        drive(restarted, resumed);

        assertTrue(appliedBefore + resumed.applied() <= 20 + 5,
                "the two runs together applied " + (appliedBefore + resumed.applied())
                        + " entries from a 20 entry log, so work was repeated");
    }

    @Test
    @DisplayName("wars left mid-rollback by a crash are found on startup")
    void findsInterruptedWars() {
        // SPEC 11.8.5: "any war found in ROLLING_BACK on startup resumes rollback".
        List<Integer> interrupted = await(newEngine().findInterrupted());

        assertTrue(interrupted.contains(WAR));
    }

    // ==================================================================================
    // Failing, SPEC 11.8.5
    // ==================================================================================

    @Test
    @DisplayName("an unreadable log fails the war rather than completing quietly")
    void unreadableLogFailsTheWar() {
        // SPEC 11.8.5: "If the block log is corrupt or unreadable, the war is flagged
        // ROLLBACK_FAILED … It must never silently give up and reopen a griefed city."
        logChange(0, "minecraft:stone", "minecraft:air");

        RollbackEngine engine = newEngine();
        RollbackJob job = await(engine.begin(WAR));
        db.close();

        await(engine.fetchNextPage(job));

        assertEquals(RollbackStatus.FAILED, job.status());
        assertFalse(job.status() == RollbackStatus.COMPLETED,
                "a rollback that could not read its log must never report success");
    }

    @Test
    @DisplayName("a failed rollback does not mark the war resolved")
    void failedRollbackDoesNotResolve() {
        logChange(0, "minecraft:stone", "minecraft:air");

        RollbackEngine engine = newEngine();
        RollbackJob job = await(engine.begin(WAR));
        job.fail("LOG_UNREADABLE");
        await(engine.finish(job));

        String state = await(daos.wars().findById(WAR)).orElseThrow().state();

        assertNotEquals("RESOLVED", state,
                "a war whose rollback failed must not be resolved; the zone stays closed");
    }

    @Test
    @DisplayName("a failed job stays failed")
    void failureIsTerminal() {
        RollbackEngine engine = newEngine();
        RollbackJob job = await(engine.begin(WAR));
        job.fail("LOG_UNREADABLE");

        // Nothing the engine offers moves it back to running.
        assertEquals(0, engine.applySlice(job));
        assertEquals(0, await(engine.fetchNextPage(job)));
        assertEquals(RollbackStatus.FAILED, job.status());
    }

    // ==================================================================================
    // Freezing the log, SPEC 11.8.2 step 2
    // ==================================================================================

    @Test
    @DisplayName("a war being rolled back accepts no further log entries")
    void frozenLogRefusesNewEntries() {
        // SPEC 11.8.2 step 2. An entry arriving mid-replay describes a change the replay has
        // already passed, so it would survive the rollback meant to undo it.
        WarBlockLogger logger = new WarBlockLogger(daos.warBlockLog(),
                new BukkitTilePayloadCodec(quiet()), configs, quiet());

        assertTrue(logger.record(WAR, WORLD, 0, 64, 0, "minecraft:stone", "minecraft:air",
                null, null, 1L));

        logger.freeze(WAR);

        assertFalse(logger.record(WAR, WORLD, 1, 64, 0, "minecraft:stone", "minecraft:air",
                null, null, 1L));
        assertTrue(logger.isFrozen(WAR));
        assertTrue(logger.record(2, WORLD, 0, 64, 0, "minecraft:stone", "minecraft:air",
                null, null, 1L), "another war's log is unaffected");
    }
}
