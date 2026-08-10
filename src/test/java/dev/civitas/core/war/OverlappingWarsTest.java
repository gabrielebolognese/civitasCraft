package dev.civitas.core.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.DatabaseSettings;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.WarBlockLogRow;
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
 * SPEC 17.4 case 51: two wars overlapping geographically.
 *
 * <h2>Why SPEC singles this out</h2>
 * "Test this explicitly, it is the most likely source of a corrupt restore." It is the one case
 * where two correct rollbacks can produce a wrong world, because each one is correct only about
 * its own war.
 *
 * <p>The shape of the danger: a position inside two zones has two logs, and each log's oldest
 * entry records the state before <em>that war</em> first touched it. Those are different states
 * whenever the wars did not start together. A replay that restores its own oldest entry is
 * right about its war and can still be wrong about the world, because the state the position
 * must end at is the one before the <em>earliest</em> war that covered it.
 *
 * <p>Wars can overlap in ordinary play: SPEC 11.4 puts a one-chunk perimeter around every zone,
 * so two wars fought by neighbouring cities share ground even before an operator lowers
 * {@code claims.buffer-chunks} from its default of 5.
 */
class OverlappingWarsTest {

    @TempDir
    Path directory;

    private static final String WORLD = "world";
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";
    private static final String AIR = "minecraft:air";

    private ServerMock server;
    private WorldMock world;
    private ConfigManager configs;
    private DatabaseManager db;
    private DaoRegistry daos;
    private RollbackEngine engine;
    private long sequence;

    private WarRegistry registry;
    private OverlapSeeder seeder;
    private War earlierWar;
    private War laterWar;
    private int earlier;
    private int later;

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

        // Two wars: one that started first and one that started later and ends later. Equal
        // durations, which SPEC 11.2 fixes at seven days, so end order follows start order.
        earlierWar = givenWar(1_000L, 8_000L);
        laterWar = givenWar(3_000L, 10_000L);
        earlier = earlierWar.id();
        later = laterWar.id();

        registry = new WarRegistry(daos.wars());
        registry.remember(earlierWar);
        registry.remember(laterWar);
        seeder = new OverlapSeeder(daos.warBlockLog(), registry, quiet());

        engine = new RollbackEngine(daos, configs, new BukkitTilePayloadCodec(quiet()),
                new ChunkHasher(configs), quiet(), new Random(1234));
    }

    @AfterEach
    void tearDown() {
        db.close();
        MockBukkit.unmock();
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("overlapping-war-test");
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

    /**
     * A war whose zone is the one chunk every test in this class fights over.
     *
     * <p>{@code prepEndsAt} is when ACTIVE begins, which is what decides whose history reaches
     * furthest back and therefore which war owns a shared position.
     */
    private War givenWar(long startsAt, long endsAt) {
        int id = await(daos.wars().insert(new WarRow(0, 1, 2, startsAt, startsAt, endsAt,
                WarState.ACTIVE.key(), 0, 0, null, BigDecimal.ZERO, null, null, 0)));
        War war = new War(id, 1, 2, startsAt, startsAt, endsAt, WarState.ACTIVE,
                BigDecimal.ZERO);
        war.zone(WarZone.of(List.of(new dev.civitas.core.claim.Claim(id, 1, WORLD, 1, 0,
                startsAt, java.util.UUID.randomUUID(), BigDecimal.ZERO,
                dev.civitas.core.claim.ClaimType.CORE, null)), 0));
        return war;
    }

    /**
     * What the plugin does when a war becomes ACTIVE.
     *
     * <p>Called at the point in each test where the later war starts, because that is when the
     * seeding has to happen: before the war can log anything of its own.
     */
    private int startLaterWar() {
        return await(seeder.seed(laterWar));
    }

    /** One log entry, as M17's recorder would have written it. */
    private void logChange(int warId, int x, int y, int z, String from, String to) {
        await(daos.warBlockLog().insertBatch(List.of(new WarBlockLogRow(0, warId, ++sequence,
                WORLD, x, y, z, from, to, null, null, System.currentTimeMillis()))));
    }

    /** A change inside both zones, which SPEC 17.4 case 51 says logs to both. */
    private void logToBoth(int x, int y, int z, String from, String to) {
        logChange(earlier, x, y, z, from, to);
        logChange(later, x, y, z, from, to);
    }

    private void rollBack(int warId) {
        RollbackJob job = await(engine.begin(warId));
        int guard = 0;
        while (job.status() == RollbackStatus.RUNNING && guard++ < 10_000) {
            if (!job.hasPending() && await(engine.fetchNextPage(job)) == 0) {
                break;
            }
            engine.applySlice(job);
        }
        await(engine.finish(job));
    }

    private Block block(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }

    // ==================================================================================
    // The straightforward half
    // ==================================================================================

    @Nested
    @DisplayName("damage logged to both wars")
    class LoggedToBoth {

        @Test
        @DisplayName("a shared position comes back whichever war restores it")
        void bothRestoreTheSameState() {
            // The easy case, and the one that works by construction: both zones covered the
            // position when it changed, so both logs agree about what was there.
            block(10, 64, 10).setType(Material.STONE);
            logToBoth(10, 64, 10, STONE, AIR);
            block(10, 64, 10).setType(Material.AIR);

            rollBack(earlier);
            assertEquals(Material.STONE, block(10, 64, 10).getType());

            rollBack(later);
            assertEquals(Material.STONE, block(10, 64, 10).getType(),
                    "the second rollback must not undo the first");
        }

        @Test
        @DisplayName("each war keeps its own log")
        void logsStaySeparate() {
            logChange(earlier, 11, 64, 10, STONE, AIR);
            logChange(later, 12, 64, 10, STONE, AIR);

            assertEquals(1L, await(daos.warBlockLog().countByWar(earlier)));
            assertEquals(1L, await(daos.warBlockLog().countByWar(later)));
        }

        @Test
        @DisplayName("a war only restores its own zone's damage")
        void oneWarDoesNotTouchTheOther() {
            block(13, 64, 10).setType(Material.STONE);
            block(14, 64, 10).setType(Material.STONE);
            logChange(earlier, 13, 64, 10, STONE, AIR);
            logChange(later, 14, 64, 10, STONE, AIR);
            block(13, 64, 10).setType(Material.AIR);
            block(14, 64, 10).setType(Material.AIR);

            rollBack(earlier);

            assertEquals(Material.STONE, block(13, 64, 10).getType());
            assertEquals(Material.AIR, block(14, 64, 10).getType(),
                    "the other war's damage is still that war's to restore");
        }
    }

    // ==================================================================================
    // The case SPEC warns about
    // ==================================================================================

    @Nested
    @DisplayName("damage that predates the second war")
    class PredatingDamage {

        @Test
        @DisplayName("a block broken before the second war started still comes back")
        void earlierDamageSurvivesTheLaterRollback() {
            // The corrupting shape, spelled out:
            //   t1  P is stone. The earlier war is running; the later one has not started.
            //   t2  P is broken. Only the earlier war's zone covers it, so only it logs
            //       old=stone.
            //   t3  The later war starts. P is now in both zones.
            //   t4  P is placed as dirt. Both logs record old=air.
            //   t8  The earlier war ends and restores P to stone. Correct.
            //   t10 The later war ends. Its oldest entry for P says old=air, which was true
            //       when it was written and is not the state the world must end at.
            // A replay that trusts its own oldest entry alone puts the hole back.
            block(20, 64, 10).setType(Material.STONE);
            logChange(earlier, 20, 64, 10, STONE, AIR);
            block(20, 64, 10).setType(Material.AIR);

            assertEquals(1, startLaterWar(), "the later war inherits what the earlier one knew");

            logToBoth(20, 64, 10, AIR, DIRT);
            block(20, 64, 10).setType(Material.DIRT);

            rollBack(earlier);
            assertEquals(Material.STONE, block(20, 64, 10).getType(),
                    "the earlier war restores the state from before it began");

            rollBack(later);
            assertEquals(Material.STONE, block(20, 64, 10).getType(),
                    "and the later war must leave that alone, not restore its own idea of "
                            + "'before', which is a hole the earlier war already filled in");
        }

        @Test
        @DisplayName("the rule holds when the wars roll back in the other order")
        void orderDoesNotMatter() {
            // SPEC says "rollback order is by war end time", but a server that was down when
            // the earlier war ended catches both up in one sweep, and an admin may force-end
            // either one first. The final state must not depend on which ran first.
            block(21, 64, 10).setType(Material.STONE);
            logChange(earlier, 21, 64, 10, STONE, AIR);
            block(21, 64, 10).setType(Material.AIR);
            startLaterWar();
            logToBoth(21, 64, 10, AIR, DIRT);
            block(21, 64, 10).setType(Material.DIRT);

            rollBack(later);
            rollBack(earlier);

            assertEquals(Material.STONE, block(21, 64, 10).getType());
        }

        @Test
        @DisplayName("a position only the later war ever saw is restored by it normally")
        void positionsOutsideTheOverlapAreUnaffected() {
            // The fix must not make the later war shy about its own ground.
            startLaterWar();
            block(22, 64, 10).setType(Material.STONE);
            logChange(later, 22, 64, 10, STONE, AIR);
            block(22, 64, 10).setType(Material.AIR);

            rollBack(earlier);
            rollBack(later);

            assertEquals(Material.STONE, block(22, 64, 10).getType());
        }

        @Test
        @DisplayName("three overlapping wars still land on the oldest state")
        void threeDeep() {
            War thirdWar = givenWar(5_000L, 12_000L);
            registry.remember(thirdWar);

            block(23, 64, 10).setType(Material.STONE);
            logChange(earlier, 23, 64, 10, STONE, AIR);
            block(23, 64, 10).setType(Material.AIR);

            startLaterWar();
            logToBoth(23, 64, 10, AIR, DIRT);
            block(23, 64, 10).setType(Material.DIRT);

            // The third war starts on ground two wars have already fought over, and must end
            // up knowing about the oldest of them.
            await(seeder.seed(thirdWar));
            logChange(thirdWar.id(), 23, 64, 10, DIRT, AIR);
            block(23, 64, 10).setType(Material.AIR);

            rollBack(earlier);
            rollBack(later);
            rollBack(thirdWar.id());

            assertEquals(Material.STONE, block(23, 64, 10).getType(),
                    "the oldest recorded state wins however many wars covered the position");
        }
    }

    @Test
    @DisplayName("a war with no overlap is unaffected by the rule")
    void noOverlapNoCost() {
        // The common case by a wide margin: most wars share ground with nothing.
        block(30, 64, 10).setType(Material.STONE);
        logChange(earlier, 30, 64, 10, STONE, AIR);
        block(30, 64, 10).setType(Material.AIR);

        rollBack(earlier);

        assertTrue(block(30, 64, 10).getType() == Material.STONE);
    }
}
