package dev.civitas.core.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import dev.civitas.storage.row.WarRow;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * SPEC 18.3's protocol, as far as a test can run it.
 *
 * <h2>What this is and what it is not</h2>
 * SPEC 18.3 is a <b>manual</b> protocol: two accounts, a live Paper server, screenshots, a
 * restart mid-war, a SIGKILL mid-rollback, and three clean passes before launch. None of that
 * can live in a test suite, and {@code WAR_TEST_PROTOCOL.md} is where an operator runs it.
 *
 * <p>What a test <em>can</em> do is the part that is really about code rather than about
 * operating a server: build SPEC 18.3 step 2's structure, destroy every element of it as step 5
 * describes, replay the log, and check step 8's requirement that everything came back. That is
 * done here through the real logger and the real engine — no hand-written log rows, unlike
 * {@link RollbackEngineTest}, because the point is the seam between the two.
 *
 * <p>One element of step 2 is knowingly not covered, and it is recorded in OPEN_QUESTIONS.md
 * rather than skipped quietly: <b>a beehive's bees</b>. Paper's {@code EntityBlockStorage}
 * counts them and will not hand them over, so a hive rolls back empty. SPEC 18.3 step 8 as
 * literally written cannot pass for a hive without an NMS-backed codec.
 */
class Spec18ProtocolTest {

    @TempDir
    Path directory;

    private static final int WAR = 1;
    private static final String WORLD = "world";

    private ServerMock server;
    private WorldMock world;
    private ConfigManager configs;
    private DatabaseManager db;
    private DaoRegistry daos;
    private WarBlockLogger log;
    private WarBlockRecorder recorder;
    private RollbackEngine engine;

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

        await(daos.wars().insert(new WarRow(0, 1, 2, 0L, 0L, 0L, "ACTIVE", 0, 0, null,
                BigDecimal.ZERO, null, null)));

        BukkitTilePayloadCodec codec = new BukkitTilePayloadCodec(quiet());
        log = new WarBlockLogger(daos.warBlockLog(), codec, configs, quiet());

        // Everything is inside the war zone, which is the condition under test: SPEC 11.4 says
        // nothing outside one is ever touched, and SPEC 18.3 builds inside a defended city.
        recorder = new WarBlockRecorder(everywhere(), log);
        engine = new RollbackEngine(daos, configs, codec, new ChunkHasher(configs), quiet(),
                new Random(7));
    }

    @AfterEach
    void tearDown() {
        db.close();
        MockBukkit.unmock();
    }

    /** A zone covering the whole world, standing in for the defender's claims. */
    private static WarZones everywhere() {
        return new WarZones() {
            @Override
            public List<Integer> warsCovering(String world, int x, int y, int z) {
                return List.of(WAR);
            }

            @Override
            public boolean isAnyWarActive() {
                return true;
            }
        };
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("spec18-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Block block(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }

    /**
     * A block's tile state, or a skipped test.
     *
     * <h2>The limitation this exposes, which is worth stating plainly</h2>
     * MockBukkit builds a block's state class when the <em>material</em> is set and does not
     * rebuild it when {@code setBlockData} writes one. The rollback restores with
     * {@code setBlockData(data, false)} — it must, because SPEC 11.8.2 step 4 requires physics
     * to be suppressed — so under MockBukkit a restored chest is a chest with no chest state,
     * and there is nothing to read the contents back out of.
     *
     * <p>On a real server, setting a chest's block data creates the tile entity and the codec
     * writes into it. The capture-and-restore round trip itself is proved in
     * {@link TilePayloadCodecTest}, which never goes through the applier. What no test in this
     * suite can prove is the two halves working together.
     *
     * <p><b>So the contents of chests, the text on signs, banner patterns and spawner types
     * are verified by SPEC 18.3's manual protocol and by nothing else.</b> That is not a gap
     * being tolerated quietly: it is written down in {@code WAR_TEST_PROTOCOL.md} and in
     * OPEN_QUESTIONS.md, and it is why SPEC 18.3 says not to launch until the protocol passes
     * three times running.
     */
    private <T> T tileState(Block target, Class<T> type) {
        Object state = target.getState();
        Assumptions.assumeTrue(type.isInstance(state),
                "MockBukkit does not rebuild a " + type.getSimpleName() + " state after "
                        + "setBlockData, so this element is verified only by the manual "
                        + "SPEC 18.3 protocol");
        return type.cast(state);
    }

    /**
     * Step 5: destroy it, exactly as the protocol says to.
     *
     * <p>Through the recorder rather than by writing log rows, so the capture path M17 owns is
     * part of what is under test.
     */
    private void destroy(Block target) {
        assertTrue(recorder.recordRemoval(target, null),
                "the logger must accept the change, or the rollback has nothing to replay");
        target.setType(Material.AIR);
    }

    /** Steps 6 and 7: the war ends and the world is put back. */
    private RollbackJob rollBack() {
        log.freeze(WAR);
        log.flushBlocking();

        RollbackJob job = await(engine.begin(WAR));
        int guard = 0;
        while (job.status() == RollbackStatus.RUNNING && guard++ < 100_000) {
            if (!job.hasPending() && await(engine.fetchNextPage(job)) == 0) {
                break;
            }
            engine.applySlice(job);
        }
        await(engine.finish(job));
        return job;
    }

    // ==================================================================================
    // Step 2's structure, element by element
    // ==================================================================================

    @Test
    @DisplayName("step 8: a chest and everything in it comes back")
    void chestWithItems() {
        Block chest = block(10, 64, 0);
        chest.setType(Material.CHEST);
        org.bukkit.block.Container state = tileState(chest, org.bukkit.block.Container.class);
        state.getSnapshotInventory().setItem(0, new ItemStack(Material.DIAMOND, 17));
        state.getSnapshotInventory().setItem(4, new ItemStack(Material.GOLD_INGOT, 3));
        state.update();

        destroy(chest);
        rollBack();

        assertEquals(Material.CHEST, chest.getType());
        org.bukkit.block.Container restored =
                tileState(chest, org.bukkit.block.Container.class);
        ItemStack diamonds = restored.getSnapshotInventory().getItem(0);
        assertNotNull(diamonds, "the chest came back empty");
        assertEquals(Material.DIAMOND, diamonds.getType());
        assertEquals(17, diamonds.getAmount(), "and with the exact stack it held");
    }

    @Test
    @DisplayName("step 8: a sign's text comes back")
    void signWithText() {
        Block sign = block(11, 64, 0);
        sign.setType(Material.OAK_SIGN);
        Sign state = tileState(sign, Sign.class);
        state.getSide(org.bukkit.block.sign.Side.FRONT)
                .line(0, net.kyori.adventure.text.Component.text("Carthago"));
        state.update();

        destroy(sign);
        rollBack();

        assertEquals(Material.OAK_SIGN, sign.getType());
        Sign restored = tileState(sign, Sign.class);
        String line = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(
                        restored.getSide(org.bukkit.block.sign.Side.FRONT).line(0));
        assertEquals("Carthago", line);
    }

    @Test
    @DisplayName("step 8: a banner's pattern comes back")
    void bannerWithPattern() {
        Block banner = block(12, 64, 0);
        banner.setType(Material.WHITE_BANNER);
        org.bukkit.block.Banner state = tileState(banner, org.bukkit.block.Banner.class);
        state.setPatterns(List.of(new org.bukkit.block.banner.Pattern(DyeColor.RED,
                org.bukkit.block.banner.PatternType.STRIPE_BOTTOM)));
        state.update();

        destroy(banner);
        rollBack();

        assertEquals(Material.WHITE_BANNER, banner.getType());
        org.bukkit.block.Banner restored =
                tileState(banner, org.bukkit.block.Banner.class);
        assertEquals(1, restored.getPatterns().size(), "the pattern is part of the build");
        assertEquals(DyeColor.RED, restored.getPatterns().get(0).getColor());
    }

    @Test
    @DisplayName("step 8: a spawner keeps what it spawns")
    void spawner() {
        Block spawner = block(13, 64, 0);
        spawner.setType(Material.SPAWNER);
        org.bukkit.block.CreatureSpawner state =
                tileState(spawner, org.bukkit.block.CreatureSpawner.class);
        state.setSpawnedType(org.bukkit.entity.EntityType.SKELETON);
        state.update();

        destroy(spawner);
        rollBack();

        assertEquals(Material.SPAWNER, spawner.getType());
        org.bukkit.block.CreatureSpawner restored =
                tileState(spawner, org.bukkit.block.CreatureSpawner.class);
        assertEquals(org.bukkit.entity.EntityType.SKELETON, restored.getSpawnedType(),
                "a spawner restored as the wrong mob is a spawner nobody wanted");
    }

    @Test
    @DisplayName("step 8: plain blocks, water and lava all come back")
    void terrainAndFluids() {
        // SPEC 18.3 step 5 floods the structure with lava. What is being checked is that the
        // block the lava replaced is what returns, not the lava.
        Block stone = block(14, 64, 10);
        Block water = block(15, 64, 10);
        stone.setType(Material.STONE);
        water.setType(Material.WATER);

        destroy(stone);
        destroy(water);
        block(14, 64, 10).setType(Material.LAVA);

        rollBack();

        assertEquals(Material.STONE, stone.getType());
        assertEquals(Material.WATER, water.getType());
    }

    @Test
    @DisplayName("step 8: sand suspended on a torch does not fall on the way back")
    void suspendedSand() {
        // SPEC 11.8.2 step 4's whole reason for existing. Restoring with physics would drop
        // the sand the instant the torch under it reappeared, and SPEC 18.3 puts this exact
        // arrangement in the structure to catch it.
        Block torch = block(16, 63, 10);
        Block sand = block(16, 64, 10);
        torch.setType(Material.TORCH);
        sand.setType(Material.SAND);

        destroy(sand);
        destroy(torch);

        rollBack();

        assertEquals(Material.TORCH, torch.getType());
        assertEquals(Material.SAND, sand.getType(), "the sand is back where it was, not below");
    }

    @Test
    @DisplayName("step 8: redstone comes back in the state it was in")
    void redstone() {
        Block lever = block(17, 64, 10);
        Block dust = block(18, 64, 10);
        lever.setType(Material.LEVER);
        dust.setType(Material.REDSTONE_WIRE);

        destroy(lever);
        destroy(dust);
        rollBack();

        assertEquals(Material.LEVER, lever.getType());
        assertEquals(Material.REDSTONE_WIRE, dust.getType());
    }

    // ==================================================================================
    // The protocol as a whole
    // ==================================================================================

    @Test
    @DisplayName("the whole structure survives being destroyed and restored together")
    void theWholeStructure() {
        // Element by element above; all at once here, because SPEC 18.3 destroys a structure
        // rather than a list of blocks, and the ordering of a real replay is the thing that
        // has to hold.
        Block chest = block(20, 64, 20);
        Block sign = block(21, 64, 20);
        Block spawner = block(22, 64, 20);
        Block stone = block(23, 64, 20);
        Block torch = block(24, 63, 20);
        Block sand = block(24, 64, 20);

        chest.setType(Material.CHEST);
        if (chest.getState() instanceof org.bukkit.block.Container chestState) {
            chestState.getSnapshotInventory().setItem(0, new ItemStack(Material.EMERALD, 9));
            chestState.update();
        }
        sign.setType(Material.OAK_SIGN);
        spawner.setType(Material.SPAWNER);
        stone.setType(Material.STONE);
        torch.setType(Material.TORCH);
        sand.setType(Material.SAND);

        for (Block target : List.of(chest, sign, spawner, stone, sand, torch)) {
            destroy(target);
        }

        RollbackJob job = rollBack();

        assertEquals(RollbackStatus.COMPLETED, job.status());
        assertEquals(Material.CHEST, chest.getType());
        assertEquals(Material.OAK_SIGN, sign.getType());
        assertEquals(Material.SPAWNER, spawner.getType());
        assertEquals(Material.STONE, stone.getType());
        assertEquals(Material.TORCH, torch.getType());
        assertEquals(Material.SAND, sand.getType());

        if (chest.getState() instanceof org.bukkit.block.Container restored) {
            ItemStack kept = restored.getSnapshotInventory().getItem(0);
            assertNotNull(kept, "the chest came back but its contents did not");
            assertEquals(9, kept.getAmount());
        }
    }

    @Test
    @DisplayName("step 9: the verification pass reports no mismatches")
    void verificationIsClean() {
        // SPEC 18.3 step 9 runs /ca war verify and requires zero mismatches. The command is
        // M21's; the check behind it is the engine's verification sample, and a clean run is
        // what "zero mismatches" means.
        Block stone = block(30, 64, 30);
        stone.setType(Material.STONE);
        destroy(stone);

        RollbackJob job = rollBack();

        assertEquals(RollbackStatus.COMPLETED, job.status());
        assertEquals(0, await(daos.warRollbackIssues().findByWar(WAR, 100)).size(),
                "a mismatch here is the plugin admitting it did not put something back");
    }

    @Test
    @DisplayName("a block broken twice ends at the state before the war, not in between")
    void repeatedDamage() {
        // SPEC 17.4 case 42 through the real logger rather than a hand-written log, which is
        // the difference between trusting the replay and trusting the capture as well.
        Block target = block(31, 64, 30);
        target.setType(Material.STONE);

        destroy(target);
        target.setType(Material.DIRT);
        destroy(target);
        target.setType(Material.COBBLESTONE);
        destroy(target);

        rollBack();

        assertEquals(Material.STONE, target.getType());
    }
}
