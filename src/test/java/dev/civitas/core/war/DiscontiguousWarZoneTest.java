package dev.civitas.core.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ChunkKey;
import dev.civitas.core.claim.ClaimType;
import dev.civitas.core.world.RegionFiles;
import dev.civitas.core.world.WorldBackupService;
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
 * SPEC 39.14 case 136: a war whose defender holds an outpost half a million blocks away.
 *
 * <h2>Why this needs its own fixture</h2>
 *
 * <p>PLAN names this the milestone "most likely to be skipped and most likely to cause a serious
 * bug", and the reason is that nothing else exercises it: every other war test in this project is
 * fought on a compact map where both cities are a few hundred blocks apart, because that is what a
 * test fixture naturally builds. SPEC 32.3 removed the world border, SPEC 39 turned outposts into
 * four-chunk holdings that are part of the war zone, and together those make a discontiguous zone
 * spanning six figures of blocks an ordinary case rather than an exotic one.
 *
 * <p>The code paths that differ are zone packing at large coordinates, the region-file arithmetic
 * behind the pre-war snapshot, the block log's own coordinate handling, the rollback replay, and —
 * the one that turned out to be wrong — where SPEC 11.6's capture points are placed.
 */
class DiscontiguousWarZoneTest {

    @TempDir
    Path directory;

    private static final String WORLD = "world";

    /** SPEC 39.14 case 136's own figure: "an outpost is over 500,000 blocks from the city". */
    private static final int REMOTE_BLOCKS = 640_000;
    private static final int REMOTE_CHUNK = REMOTE_BLOCKS >> 4;

    private static final String STONE = "minecraft:stone";
    private static final String AIR = "minecraft:air";

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

        engine = new RollbackEngine(daos, configs, new BukkitTilePayloadCodec(quiet()),
                new ChunkHasher(configs), quiet(), new Random(1234));
    }

    @AfterEach
    void tearDown() {
        db.close();
        MockBukkit.unmock();
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("discontiguous-war-" + System.nanoTime());
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

    // ==================================================================================
    // The fixture: a city at origin and a four-chunk outpost 640,000 blocks out
    // ==================================================================================

    private static Claim cityChunk(int chunkX, int chunkZ) {
        return new Claim(chunkX * 1000L + chunkZ, 1, WORLD, chunkX, chunkZ, 0L,
                UUID.randomUUID(), BigDecimal.ZERO,
                chunkX == 0 && chunkZ == 0 ? ClaimType.CORE : ClaimType.NORMAL, null);
    }

    private static Claim outpostChunk(int chunkX, int chunkZ) {
        return new Claim(900_000L + chunkX, 1, WORLD, chunkX, chunkZ, 0L,
                UUID.randomUUID(), BigDecimal.ZERO, ClaimType.OUTPOST, 7);
    }

    /** Ten chunks of city around origin, plus SPEC 39's four-chunk outpost, far out. */
    private static List<Claim> defenderClaims() {
        List<Claim> claims = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 2; z++) {
                claims.add(cityChunk(x, z));
            }
        }
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                claims.add(outpostChunk(REMOTE_CHUNK + x, REMOTE_CHUNK + z));
            }
        }
        return claims;
    }

    @Nested
    @DisplayName("zone computation at distance")
    class Zone {

        @Test
        @DisplayName("the zone holds both region groups and nothing between them")
        void bothGroups() {
            WarZone zone = WarZone.of(defenderClaims(), 1);

            assertTrue(zone.containsChunk(WORLD, 0, 0), "the city");
            assertTrue(zone.containsChunk(WORLD, REMOTE_CHUNK, REMOTE_CHUNK), "the outpost");
            assertFalse(zone.containsChunk(WORLD, REMOTE_CHUNK / 2, REMOTE_CHUNK / 2),
                    "and nothing at all in the 300,000 blocks between them");
        }

        @Test
        @DisplayName("the packing is exact at these coordinates, not merely close")
        void packingSurvives() {
            // 26 bits a coordinate reaches +/-33.5M chunks, so 40,000 is nowhere near the edge.
            // Asserted anyway, because a packing that silently wrapped would put an outpost's
            // chunks on top of the city's and the zone would still look the right size.
            assertTrue(ChunkKey.isInRange(REMOTE_CHUNK), "the claim cache must hold it too");

            WarZone zone = WarZone.of(defenderClaims(), 0);
            Set<String> seen = new java.util.HashSet<>();
            for (long[] chunk : zone.chunkList()) {
                seen.add(chunk[1] + ":" + chunk[2]);
            }

            assertEquals(zone.size(), seen.size(), "no two chunks packed to the same key");
            assertTrue(seen.contains(REMOTE_CHUNK + ":" + REMOTE_CHUNK));
        }

        @Test
        @DisplayName("the zone is small even though it spans 640,000 blocks")
        void staysSmall() {
            // The property that makes everything downstream affordable: a zone is a set of
            // chunks, not a bounding box. A bounding box here would be 1.6 billion chunks.
            WarZone zone = WarZone.of(defenderClaims(), 1);

            assertTrue(zone.size() < 100,
                    "a discontiguous zone must cost what its chunks cost, not what its span does");
        }
    }

    @Nested
    @DisplayName("SPEC 39.9's capture points")
    class Objectives {

        @Test
        @DisplayName("never placed on an outpost, however far out it is")
        void neverOnAnOutpost() {
            // The bug this milestone existed to find. SPEC 11.6 places the three points at the
            // north-most claim, the south-most, and the one furthest from the core — so with
            // outposts included, two of the three land on the remote holding by construction,
            // and a war's objectives end up a week's travel from the fighting.
            //
            // SPEC 39.9 is explicit: "Capture points are generated from the main city body only,
            // never from outposts, so a war is decided at the city rather than at a remote
            // holding."
            CapturePoints points = new CapturePoints(new WarScoring(configs));
            War war = new War(1, 2, 1, 0L, 0L, 1L, WarState.ACTIVE, BigDecimal.ZERO);

            List<CapturePoints.Point> placed =
                    points.generate(war, defenderClaims(), 3, 0, 0);

            assertEquals(3, placed.size());
            for (CapturePoints.Point point : placed) {
                assertTrue(Math.abs(point.chunkX()) < 100 && Math.abs(point.chunkZ()) < 100,
                        "capture point at (" + point.chunkX() + ", " + point.chunkZ()
                                + ") is on the outpost, not the city");
            }
        }

        @Test
        @DisplayName("a defender holding only outposts gets none rather than unreachable ones")
        void onlyOutposts() {
            CapturePoints points = new CapturePoints(new WarScoring(configs));
            War war = new War(2, 2, 1, 0L, 0L, 1L, WarState.ACTIVE, BigDecimal.ZERO);

            assertTrue(points.generate(war,
                    List.of(outpostChunk(REMOTE_CHUNK, REMOTE_CHUNK)), 3, 0, 0).isEmpty());
        }
    }

    @Nested
    @DisplayName("SPEC 32.8's pre-war snapshot over a discontiguous zone")
    class Snapshot {

        @Test
        @DisplayName("covers both region groups and nothing in between")
        void coversBothGroups() {
            // A region file is 32x32 chunks, so the city and the outpost are in different files
            // that are 1,250 regions apart. The snapshot must take both and neither of the
            // 1,248 files between them, which do not exist and never will.
            List<int[]> chunks = new ArrayList<>();
            for (long[] chunk : WarZone.of(defenderClaims(), 1).chunkList()) {
                chunks.add(new int[] {(int) chunk[1], (int) chunk[2]});
            }

            Set<String> files = RegionFiles.covering(chunks);

            assertTrue(files.contains("r.0.0.mca"), "the city's region");
            assertTrue(files.contains(RegionFiles.nameOf(
                            RegionFiles.regionOfChunk(REMOTE_CHUNK),
                            RegionFiles.regionOfChunk(REMOTE_CHUNK))),
                    "the outpost's region");
            assertTrue(files.size() <= 8,
                    "a handful of files, not a range: " + files.size());
        }

        @Test
        @DisplayName("and the snapshot actually copies both, 640,000 blocks apart")
        void copiesBoth() throws java.io.IOException {
            Path worldFolder = directory.resolve("live");
            Path regions = java.nio.file.Files.createDirectories(
                    worldFolder.resolve("region"));
            int remoteRegion = RegionFiles.regionOfChunk(REMOTE_CHUNK);
            java.nio.file.Files.writeString(regions.resolve("r.0.0.mca"), "city");
            java.nio.file.Files.writeString(
                    regions.resolve(RegionFiles.nameOf(remoteRegion, remoteRegion)), "outpost");
            java.nio.file.Files.writeString(regions.resolve("r.500.500.mca"), "unrelated");

            WorldBackupService backups = new WorldBackupService(quiet(),
                    directory.resolve("backups"),
                    name -> java.util.Optional.of(worldFolder.toFile()),
                    new WorldBackupService.Settings(true, 2, 14, true, 7, 0));

            List<int[]> chunks = List.of(
                    new int[] {0, 0}, new int[] {REMOTE_CHUNK, REMOTE_CHUNK});

            assertEquals(2, backups.snapshotWarZone(11, Map.of(WORLD, chunks)),
                    "both groups, and not the unrelated region between them");
        }
    }

    @Nested
    @DisplayName("logging and rollback at extreme coordinates")
    class Replay {

        @Test
        @DisplayName("a block broken at 640,000 blocks is restored exactly")
        void restoresRemoteDamage() {
            // MockBukkit generates no terrain, so the chunk at 640,000 is as real as the one at
            // origin. What is being tested is the coordinate handling in the log, the paging
            // query and the replay — none of which has ever seen a six-figure coordinate.
            int remoteX = REMOTE_BLOCKS;
            int remoteZ = REMOTE_BLOCKS;

            block(0, 64, 0).setType(Material.STONE);
            block(remoteX, 64, remoteZ).setType(Material.STONE);
            log(1, WORLD, 0, 64, 0, STONE, AIR);
            log(1, WORLD, remoteX, 64, remoteZ, STONE, AIR);
            block(0, 64, 0).setType(Material.AIR);
            block(remoteX, 64, remoteZ).setType(Material.AIR);

            givenWar(1);
            RollbackJob job = rollBack(1);

            assertEquals(2, job.applied());
            assertEquals(Material.STONE, block(0, 64, 0).getType());
            assertEquals(Material.STONE, block(remoteX, 64, remoteZ).getType(),
                    "the remote half of the zone must be restored like any other");
        }

        @Test
        @DisplayName("negative remote coordinates too, which is where a sign bug would show")
        void negativeRemote() {
            int remoteX = -REMOTE_BLOCKS;
            int remoteZ = -REMOTE_BLOCKS;

            block(remoteX, 64, remoteZ).setType(Material.STONE);
            log(2, WORLD, remoteX, 64, remoteZ, STONE, AIR);
            block(remoteX, 64, remoteZ).setType(Material.AIR);

            givenWar(2);

            assertEquals(1, rollBack(2).applied());
            assertEquals(Material.STONE, block(remoteX, 64, remoteZ).getType());
        }

        @Test
        @DisplayName("the zone still refuses ground outside it at that distance")
        void boundaryHolds() {
            // The rule that keeps a war from spilling: it has to hold at the far group's edge
            // too, where the arithmetic is under most strain.
            WarZone zone = WarZone.of(defenderClaims(), 1);

            assertTrue(zone.containsChunk(WORLD, REMOTE_CHUNK - 1, REMOTE_CHUNK),
                    "the one-chunk perimeter SPEC 11.4 puts around every claim");
            assertFalse(zone.containsChunk(WORLD, REMOTE_CHUNK - 2, REMOTE_CHUNK),
                    "and one chunk further out is wilderness");
        }
    }

    @Nested
    @DisplayName("SPEC 17.4 case 51 at distance")
    class Overlap {

        @Test
        @DisplayName("two wars sharing a remote outpost still seed each other correctly")
        void seedsAcrossTheGap() {
            // M20 called overlapping wars "the most likely source of a corrupt restore" and fixed
            // it with OverlapSeeder. Its bounding box is computed over the shared chunks, so two
            // wars overlapping at BOTH the city and a remote outpost produce a box 640,000 blocks
            // wide. That is only a wider query, not a wrong one — but nothing had checked.
            WarRegistry registry = new WarRegistry(daos.wars());
            War earlier = warAt(1_000L, 8_000L);
            War later = warAt(3_000L, 10_000L);
            registry.remember(earlier);
            registry.remember(later);

            int remoteX = REMOTE_BLOCKS;
            log(earlier.id(), WORLD, remoteX, 64, remoteX, STONE, AIR);

            OverlapSeeder seeder = new OverlapSeeder(daos.warBlockLog(), registry, quiet());
            int seeded = await(seeder.seed(later));

            assertEquals(1, seeded,
                    "the later war must inherit the earlier one's history at the outpost too");
        }

        /** A war whose zone is the city AND the remote outpost, so the two overlap twice. */
        private War warAt(long startsAt, long endsAt) {
            int id = await(daos.wars().insert(new WarRow(0, 1, 2, startsAt, startsAt, endsAt,
                    WarState.ACTIVE.key(), 0, 0, null, BigDecimal.ZERO, null, null, 0)));
            War war = new War(id, 1, 2, startsAt, startsAt, endsAt, WarState.ACTIVE,
                    BigDecimal.ZERO);
            war.zone(WarZone.of(defenderClaims(), 0));
            return war;
        }
    }

    // ==================================================================================

    private Block block(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }

    private void log(int warId, String worldName, int x, int y, int z, String oldData,
                     String newData) {
        await(daos.warBlockLog().insertBatch(List.of(new WarBlockLogRow(0, warId, ++sequence,
                worldName, x, y, z, oldData, newData, null, null,
                System.currentTimeMillis()))));
    }

    /** Runs the engine to completion, alternating the two halves as the plugin's driver does. */
    private RollbackJob rollBack(int warId) {
        RollbackJob job = await(engine.begin(warId));
        int guard = 0;
        while (job.status() == RollbackStatus.RUNNING && guard++ < 10_000) {
            if (!job.hasPending() && await(engine.fetchNextPage(job)) == 0) {
                break;
            }
            engine.applySlice(job);
        }
        await(engine.finish(job));
        return job;
    }

    private void givenWar(int id) {
        await(daos.wars().insert(new WarRow(0, 1, 2, 0L, 0L, 0L, WarState.ROLLING_BACK.key(),
                0, 0, null, BigDecimal.ZERO, null, null, 0)));
    }
}
