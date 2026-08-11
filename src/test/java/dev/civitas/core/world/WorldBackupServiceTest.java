package dev.civitas.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 32.8's three tiers, exercised against a temp dir full of fake region files.
 *
 * <p>Real region files are not needed and would not help: every rule here is about <em>which</em>
 * files are copied and which are pruned, and none of it reads a byte of Minecraft's format.
 *
 * <p>{@link Snapshot#coversOnlyTheZone} is the milestone's central claim — that a pre-war snapshot
 * is cheap because a war zone is bounded where the world is not.
 */
class WorldBackupServiceTest {

    @TempDir
    Path directory;

    private Path backups;
    private Path worldRegions;
    private Path netherRegions;
    private WorldBackupService service;

    private static final WorldBackupService.Settings DEFAULTS =
            new WorldBackupService.Settings(true, 2, 14, true, 7, 10);

    @BeforeEach
    void setUp() throws IOException {
        Logger quiet = Logger.getLogger("world-backup-" + System.nanoTime());
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        backups = directory.resolve("world-backups");
        worldRegions = Files.createDirectories(directory.resolve("world").resolve("region"));
        netherRegions = Files.createDirectories(
                directory.resolve("world_nether").resolve("DIM-1").resolve("region"));

        service = new WorldBackupService(quiet, backups, worlds(), DEFAULTS);
    }

    private WorldBackupService.Worlds worlds() {
        return name -> {
            File folder = directory.resolve(name).toFile();
            return folder.isDirectory() ? Optional.of(folder) : Optional.empty();
        };
    }

    private void region(Path folder, int x, int z, String content) throws IOException {
        Files.writeString(folder.resolve(RegionFiles.nameOf(x, z)), content);
    }

    private static List<Path> subfolders(Path root, String prefix) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .sorted()
                    .toList();
        }
    }

    @Nested
    @DisplayName("the full copy, SPEC 32.8")
    class Full {

        @Test
        @DisplayName("copies every region of every configured world")
        void copiesEverything() throws IOException {
            region(worldRegions, 0, 0, "a");
            region(worldRegions, 1, 0, "b");
            region(netherRegions, 0, 0, "c");

            int copied = service.fullBackup(List.of("world", "world_nether"), Instant.now());

            assertEquals(3, copied);
            List<Path> made = subfolders(backups, WorldBackupService.FULL_PREFIX);
            assertEquals(1, made.size());
            assertTrue(Files.exists(made.get(0).resolve("world").resolve("r.0.0.mca")));
            assertTrue(Files.exists(made.get(0).resolve("world_nether").resolve("r.0.0.mca")),
                    "a nether keeps its regions under DIM-1 and must still be found");
        }

        @Test
        @DisplayName("keeps only the configured number, oldest out first")
        void prunes() throws IOException {
            region(worldRegions, 0, 0, "a");
            Instant base = Instant.parse("2026-01-01T00:00:00Z");

            for (int i = 0; i < 4; i++) {
                service.fullBackup(List.of("world"), base.plus(i, ChronoUnit.DAYS));
            }

            assertEquals(2, subfolders(backups, WorldBackupService.FULL_PREFIX).size());
        }

        @Test
        @DisplayName("a world that is not loaded is skipped rather than failing the run")
        void missingWorld() {
            assertEquals(0, service.fullBackup(List.of("nonexistent"), Instant.now()));
        }
    }

    @Nested
    @DisplayName("the incremental, SPEC 32.8")
    class Incremental {

        @Test
        @DisplayName("copies only what changed since the last run")
        void onlyWhatChanged() throws IOException {
            region(worldRegions, 0, 0, "a");
            region(worldRegions, 1, 0, "b");
            service.fullBackup(List.of("world"), Instant.parse("2026-01-01T00:00:00Z"));

            // One file touched after the full copy; the other left alone.
            Files.setLastModifiedTime(worldRegions.resolve("r.1.0.mca"),
                    java.nio.file.attribute.FileTime.from(Instant.parse("2026-06-01T00:00:00Z")));
            Files.setLastModifiedTime(worldRegions.resolve("r.0.0.mca"),
                    java.nio.file.attribute.FileTime.from(Instant.parse("2025-06-01T00:00:00Z")));

            int copied = service.incrementalBackup(List.of("world"),
                    Instant.parse("2026-06-02T00:00:00Z"));

            assertEquals(1, copied);
            Path made = subfolders(backups, WorldBackupService.INCREMENTAL_PREFIX).get(0);
            assertTrue(Files.exists(made.resolve("world").resolve("r.1.0.mca")));
            assertFalse(Files.exists(made.resolve("world").resolve("r.0.0.mca")));
        }

        @Test
        @DisplayName("a run with nothing to copy leaves no folder behind")
        void noEmptyFolders() throws IOException {
            // An empty dated folder would still count against the retention window and would
            // push a real backup out of it.
            region(worldRegions, 0, 0, "a");
            service.fullBackup(List.of("world"), Instant.parse("2026-01-01T00:00:00Z"));
            Files.setLastModifiedTime(worldRegions.resolve("r.0.0.mca"),
                    java.nio.file.attribute.FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));

            service.incrementalBackup(List.of("world"), Instant.parse("2026-01-02T00:00:00Z"));

            assertTrue(subfolders(backups, WorldBackupService.INCREMENTAL_PREFIX).isEmpty());
        }

        @Test
        @DisplayName("older than the retention window is removed")
        void prunesByAge() throws IOException {
            region(worldRegions, 0, 0, "a");
            service.incrementalBackup(List.of("world"), Instant.parse("2026-01-01T00:00:00Z"));
            assertEquals(1, subfolders(backups, WorldBackupService.INCREMENTAL_PREFIX).size());

            // A run 30 days later, with the window at 14.
            region(worldRegions, 1, 0, "b");
            service.incrementalBackup(List.of("world"), Instant.parse("2026-01-31T00:00:00Z"));

            List<Path> kept = subfolders(backups, WorldBackupService.INCREMENTAL_PREFIX);
            assertEquals(1, kept.size());
            assertTrue(kept.get(0).getFileName().toString().contains("202601"));
        }
    }

    @Nested
    @DisplayName("the pre-war snapshot, SPEC 32.8")
    class Snapshot {

        @Test
        @DisplayName("takes only the regions the war zone reaches")
        void coversOnlyTheZone() throws IOException {
            // The milestone's central claim: a war zone is bounded where the world is not, so
            // this is affordable before every war where a full copy would not be.
            region(worldRegions, 0, 0, "in the zone");
            region(worldRegions, 5, 5, "far away");
            region(worldRegions, -1, -1, "also far");

            int copied = service.snapshotWarZone(42,
                    Map.of("world", List.of(new int[] {3, 4}, new int[] {10, 11})));

            assertEquals(1, copied);
            Path snapshot = backups.resolve(WorldBackupService.WAR_PREFIX + 42);
            assertTrue(Files.exists(snapshot.resolve("world").resolve("r.0.0.mca")));
            assertFalse(Files.exists(snapshot.resolve("world").resolve("r.5.5.mca")));
        }

        @Test
        @DisplayName("restores it, and says how many files went back")
        void restores() throws IOException {
            region(worldRegions, 0, 0, "before the war");
            service.snapshotWarZone(7, Map.of("world", List.of(new int[] {0, 0})));

            Files.writeString(worldRegions.resolve("r.0.0.mca"), "griefed");

            assertEquals(1, service.restoreWarSnapshot(7).orElseThrow());
            assertEquals("before the war",
                    Files.readString(worldRegions.resolve("r.0.0.mca")));
        }

        @Test
        @DisplayName("restoring a war with no snapshot answers empty rather than pretending")
        void noSnapshot() {
            assertTrue(service.restoreWarSnapshot(999).isEmpty());
        }

        @Test
        @DisplayName("a second snapshot for the same war replaces the first")
        void replaces() throws IOException {
            region(worldRegions, 0, 0, "first");
            service.snapshotWarZone(3, Map.of("world", List.of(new int[] {0, 0})));
            region(worldRegions, 0, 0, "second");
            region(worldRegions, 1, 0, "extra");

            service.snapshotWarZone(3,
                    Map.of("world", List.of(new int[] {0, 0}, new int[] {32, 0})));

            Path snapshot = backups.resolve(WorldBackupService.WAR_PREFIX + 3);
            assertEquals("second",
                    Files.readString(snapshot.resolve("world").resolve("r.0.0.mca")));
            assertTrue(Files.exists(snapshot.resolve("world").resolve("r.1.0.mca")));
        }

        @Test
        @DisplayName("kept until the war resolves plus the retention window")
        void pruned() throws IOException {
            region(worldRegions, 0, 0, "a");
            service.snapshotWarZone(1, Map.of("world", List.of(new int[] {0, 0})));
            service.snapshotWarZone(2, Map.of("world", List.of(new int[] {0, 0})));

            long now = 1_000_000_000_000L;
            long eightDays = 8L * 24 * 3600_000L;
            long twoDays = 2L * 24 * 3600_000L;

            int removed = service.pruneWarSnapshots(
                    Map.of(1, now - eightDays, 2, now - twoDays), now);

            assertEquals(1, removed);
            assertFalse(Files.exists(backups.resolve(WorldBackupService.WAR_PREFIX + 1)));
            assertTrue(Files.exists(backups.resolve(WorldBackupService.WAR_PREFIX + 2)),
                    "a war two days resolved is still inside the seven-day window");
        }

        @Test
        @DisplayName("an unresolved war's snapshot is never pruned")
        void unresolvedSurvives() throws IOException {
            region(worldRegions, 0, 0, "a");
            service.snapshotWarZone(5, Map.of("world", List.of(new int[] {0, 0})));

            java.util.Map<Integer, Long> stillFighting = new java.util.HashMap<>();
            stillFighting.put(5, null);

            assertEquals(0, service.pruneWarSnapshots(stillFighting, 1_000_000_000_000L));
            assertTrue(Files.exists(backups.resolve(WorldBackupService.WAR_PREFIX + 5)));
        }
    }

    @Nested
    @DisplayName("the disk guard, SPEC 32.8")
    class DiskGuard {

        @Test
        @DisplayName("a sane requirement passes on any machine that can run the tests")
        void hasHeadroom() {
            assertTrue(service.hasHeadroomForWar());
        }

        @Test
        @DisplayName("an absurd requirement refuses")
        void refuses() {
            WorldBackupService greedy = new WorldBackupService(Logger.getLogger("greedy"), backups,
                    worlds(), new WorldBackupService.Settings(true, 2, 14, true, 7, 100_000_000));

            assertFalse(greedy.hasHeadroomForWar());
        }

        @Test
        @DisplayName("with no snapshot to protect, the guard never blocks a war")
        void nothingToProtect() {
            // The guard exists to protect a copy that would be taken. Refusing wars on a server
            // that takes none would be a rule with no purpose behind it.
            WorldBackupService noSnapshots = new WorldBackupService(Logger.getLogger("none"),
                    backups, worlds(),
                    new WorldBackupService.Settings(true, 2, 14, false, 7, 100_000_000));

            assertTrue(noSnapshots.hasHeadroomForWar());
        }
    }

    @Nested
    @DisplayName("disabled")
    class Disabled {

        @Test
        @DisplayName("nothing is written at all")
        void nothingHappens() throws IOException {
            WorldBackupService off = new WorldBackupService(Logger.getLogger("off"), backups,
                    worlds(), new WorldBackupService.Settings(false, 2, 14, true, 7, 10));
            region(worldRegions, 0, 0, "a");

            assertEquals(0, off.fullBackup(List.of("world"), Instant.now()));
            assertEquals(0, off.incrementalBackup(List.of("world"), Instant.now()));
            assertEquals(0, off.snapshotWarZone(1, Map.of("world", List.of(new int[] {0, 0}))));
            assertFalse(Files.exists(backups.resolve(WorldBackupService.WAR_PREFIX + 1)));
        }
    }

    @Nested
    @DisplayName("SPEC 32.8's reporting")
    class Reporting {

        @Test
        @DisplayName("sizes the world and counts its region files")
        void sizes() throws IOException {
            region(worldRegions, 0, 0, "12345");
            region(worldRegions, 1, 0, "678");
            region(netherRegions, 0, 0, "9");

            var status = service.status(List.of("world", "world_nether"));

            assertEquals(9, status.worldBytes());
            assertEquals(3, status.regionFileCount());
        }

        @Test
        @DisplayName("reports never rather than a guess before anything has run")
        void nothingYet() {
            var status = service.status(List.of("world"));

            assertTrue(status.lastFull().isEmpty());
            assertTrue(status.lastIncremental().isEmpty());
            assertEquals(0, status.warSnapshots());
            assertEquals(0, status.projectedYearlyBytes(),
                    "with no incremental there is no growth rate, and zero is honest");
        }

        @Test
        @DisplayName("counts the snapshots it holds")
        void countsSnapshots() throws IOException {
            region(worldRegions, 0, 0, "a");
            service.snapshotWarZone(1, Map.of("world", List.of(new int[] {0, 0})));
            service.snapshotWarZone(2, Map.of("world", List.of(new int[] {0, 0})));

            assertEquals(2, service.status(List.of("world")).warSnapshots());
            assertEquals(java.util.Set.of(1, 2), service.warSnapshots().keySet());
        }
    }
}
