package dev.civitas.core.world;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * World backups, SPEC 32.8.
 *
 * <h2>Why the shape changed</h2>
 *
 * <p>SPEC 32.8 opens by retiring its own earlier design: "An unbounded world means region files
 * accumulate wherever anyone has ever travelled. Part IV's original 'daily full world backup, keep
 * 7' stops being viable within months once players scatter across hundreds of thousands of
 * blocks." So there are three tiers rather than one — a weekly full copy kept twice, a daily
 * incremental of only what changed kept for a fortnight, and a per-war snapshot of just the
 * regions the fighting can reach.
 *
 * <h2>The pre-war snapshot is the safety net beneath the safety net</h2>
 *
 * <p>SPEC 32.8: "The diff-based rollback in Part I 11.8 is the primary mechanism and handles every
 * normal case. The snapshot exists for the case where it does not, and it turns 'the plugin ate my
 * castle' from a catastrophe into a fifteen-minute admin fix."
 *
 * <p>It is bounded by definition — a war zone is a finite chunk set, and {@link RegionFiles} turns
 * that into a handful of files — which is what makes taking one before <em>every</em> war
 * affordable where a full world copy would not be.
 *
 * <h2>Everything here is off the server thread</h2>
 *
 * <p>Copying region files is heavy I/O. Every public method that touches disk returns nothing the
 * caller needs synchronously, and the plugin schedules them asynchronously. The one exception is
 * {@link #freeGigabytes()}, which is a single stat call and is read by the war declaration path.
 */
public final class WorldBackupService {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    static final String FULL_PREFIX = "full-";
    static final String INCREMENTAL_PREFIX = "incr-";
    static final String WAR_PREFIX = "war-";

    private final Logger logger;
    private final Path backupRoot;
    private final Worlds worlds;
    private final Settings settings;

    /**
     * Where the worlds are, so this class never touches Bukkit and can be tested on a temp dir.
     *
     * @param folderOf the world's folder, or empty when the world is not loaded
     */
    @FunctionalInterface
    public interface Worlds {

        Optional<File> folderOf(String worldName);
    }

    /** SPEC 32.8's numbers, read live so {@code /ca reload} takes effect. */
    public record Settings(
            boolean enabled,
            int fullKeepCount,
            int incrementalKeepDays,
            boolean warZoneSnapshot,
            int warSnapshotRetentionDays,
            int minFreeGb) {
    }

    public WorldBackupService(Logger logger, Path backupRoot, Worlds worlds, Settings settings) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.backupRoot = Objects.requireNonNull(backupRoot, "backupRoot");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public Path root() {
        return backupRoot;
    }

    public Settings settings() {
        return settings;
    }

    // ==================================================================================
    // The disk guard, SPEC 32.8
    // ==================================================================================

    /**
     * Free space where the backups go, in gigabytes.
     *
     * <p>Measured on the backup root rather than on the world folder, because that is where the
     * copy has to fit. Returns {@link Long#MAX_VALUE} if the filesystem will not answer: a war
     * refused because a stat call failed would be worse than one allowed on a disk that turns out
     * to be full, since the rollback in SPEC 11.8 is still the primary mechanism.
     */
    public long freeGigabytes() {
        try {
            Files.createDirectories(backupRoot);
            long free = Files.getFileStore(backupRoot).getUsableSpace();
            return free / (1024L * 1024L * 1024L);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.FINE, "Could not read free disk space at " + backupRoot, e);
            return Long.MAX_VALUE;
        }
    }

    /** SPEC 32.8: "Refuse to start a war if free disk is under world.backup.min-free-gb." */
    public boolean hasHeadroomForWar() {
        if (!settings.enabled() || !settings.warZoneSnapshot()) {
            // No snapshot will be taken, so there is nothing for the guard to protect.
            return true;
        }
        return freeGigabytes() >= settings.minFreeGb();
    }

    // ==================================================================================
    // The three tiers
    // ==================================================================================

    /** SPEC 32.8: weekly, keep 2. @return how many region files were copied */
    public int fullBackup(List<String> worldNames, Instant when) {
        if (!settings.enabled()) {
            return 0;
        }
        Path target = backupRoot.resolve(FULL_PREFIX + TIMESTAMP.format(when));
        int copied = copyWorlds(worldNames, target, region -> true);
        prune(FULL_PREFIX, settings.fullKeepCount());
        logger.info(() -> "Full world backup: " + copied + " region file(s) into " + target);
        return copied;
    }

    /**
     * SPEC 32.8: daily, "region files modified since the last run only", keep 14 days.
     *
     * <p>Modification time rather than a content hash. A region file is rewritten when its chunks
     * are saved, so mtime over-reports slightly — a chunk visited and not changed still saves —
     * and never under-reports, which is the direction a backup must fail in.
     */
    public int incrementalBackup(List<String> worldNames, Instant when) {
        if (!settings.enabled()) {
            return 0;
        }
        Instant since = lastRun().orElse(Instant.EPOCH);
        Path target = backupRoot.resolve(INCREMENTAL_PREFIX + TIMESTAMP.format(when));
        int copied = copyWorlds(worldNames, target, region -> isNewerThan(region, since));

        if (copied == 0) {
            // Nothing changed. An empty dated folder would still count against the retention
            // window and would push a real backup out of it.
            deleteRecursively(target);
        }
        pruneOlderThan(INCREMENTAL_PREFIX, when.minusSeconds(
                (long) settings.incrementalKeepDays() * 24 * 3600));
        logger.info(() -> "Incremental world backup: " + copied + " region file(s) changed since "
                + since);
        return copied;
    }

    /**
     * SPEC 32.8's pre-war snapshot, taken immediately before a war enters ACTIVE.
     *
     * @param chunksByWorld the war zone: world name to {@code {chunkX, chunkZ}} pairs
     * @return how many region files were captured
     */
    public int snapshotWarZone(int warId, Map<String, List<int[]>> chunksByWorld) {
        if (!settings.enabled() || !settings.warZoneSnapshot()) {
            return 0;
        }
        Path target = backupRoot.resolve(WAR_PREFIX + warId);
        deleteRecursively(target);

        int copied = 0;
        for (Map.Entry<String, List<int[]>> entry : chunksByWorld.entrySet()) {
            Set<String> wanted = RegionFiles.covering(entry.getValue());
            copied += copyWorld(entry.getKey(), target,
                    region -> wanted.contains(region.getFileName().toString()));
        }
        int total = copied;
        logger.info(() -> "Pre-war snapshot for war " + warId + ": " + total
                + " region file(s). The zone is " + chunksByWorld.values().stream()
                .mapToInt(List::size).sum() + " chunk(s).");
        return copied;
    }

    /**
     * Puts a war's snapshot back.
     *
     * <p>This is not a surgical undo. A region file is 32x32 chunks, so restoring one rewinds
     * every chunk it holds, including ground outside the war zone that happened to share a file.
     * That is why SPEC 32.8 guards the command by requiring the war id typed twice.
     *
     * @return how many region files were written back, or empty if there is no snapshot
     */
    public Optional<Integer> restoreWarSnapshot(int warId) {
        Path source = backupRoot.resolve(WAR_PREFIX + warId);
        if (!Files.isDirectory(source)) {
            return Optional.empty();
        }

        int restored = 0;
        try (Stream<Path> worldFolders = Files.list(source)) {
            for (Path worldFolder : worldFolders.toList()) {
                Optional<File> live = worlds.folderOf(worldFolder.getFileName().toString())
                        .flatMap(RegionFiles::regionFolderOf);
                if (live.isEmpty()) {
                    logger.warning("Snapshot for war " + warId + " names world "
                            + worldFolder.getFileName() + ", which is not loaded. Skipped.");
                    continue;
                }
                restored += copyInto(worldFolder, live.get().toPath(), region -> true);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not restore the snapshot for war " + warId, e);
            return Optional.empty();
        }
        int total = restored;
        logger.warning(() -> "Restored " + total + " region file(s) from the pre-war snapshot of "
                + "war " + warId + ". The affected chunks must be reloaded, which usually means "
                + "a restart.");
        return Optional.of(restored);
    }

    /** SPEC 32.8: a snapshot is kept "until the war reaches RESOLVED plus 7 days". */
    public int pruneWarSnapshots(Map<Integer, Long> resolvedAt, long now) {
        long window = (long) settings.warSnapshotRetentionDays() * 24 * 3600_000L;
        int removed = 0;
        for (Map.Entry<Integer, Long> entry : resolvedAt.entrySet()) {
            if (entry.getValue() == null || now - entry.getValue() < window) {
                continue;
            }
            Path snapshot = backupRoot.resolve(WAR_PREFIX + entry.getKey());
            if (Files.isDirectory(snapshot)) {
                deleteRecursively(snapshot);
                removed++;
            }
        }
        return removed;
    }

    // ==================================================================================
    // SPEC 32.8's reporting
    // ==================================================================================

    /** What {@code /ca backup status} prints. */
    public record Status(
            long worldBytes,
            int regionFileCount,
            Optional<Instant> lastFull,
            Optional<Instant> lastIncremental,
            int warSnapshots,
            long freeGb,
            long projectedYearlyBytes) {
    }

    /**
     * The world's current size and how fast it is growing.
     *
     * <p>Growth is projected from the newest incremental backup rather than measured, because
     * nothing records yesterday's size: an incremental holds exactly the regions that changed in
     * a day, so its size annualised is a usable first-order estimate and is honest about being
     * one. With no incremental yet it reports zero rather than guessing.
     */
    public Status status(List<String> worldNames) {
        long bytes = 0;
        int files = 0;
        for (String world : worldNames) {
            Optional<Path> regions = worlds.folderOf(world)
                    .flatMap(RegionFiles::regionFolderOf)
                    .map(File::toPath);
            if (regions.isEmpty()) {
                continue;
            }
            try {
                bytes += RegionFiles.sizeOf(regions.get());
                files += RegionFiles.listRegions(regions.get()).size();
            } catch (IOException e) {
                logger.log(Level.FINE, "Could not size " + regions.get(), e);
            }
        }

        Optional<Path> newestIncremental = newest(INCREMENTAL_PREFIX);
        long dailyDelta = newestIncremental.map(this::sizeOfTree).orElse(0L);

        return new Status(bytes, files,
                newest(FULL_PREFIX).flatMap(WorldBackupService::timestampOf),
                newestIncremental.flatMap(WorldBackupService::timestampOf),
                countWithPrefix(WAR_PREFIX),
                freeGigabytes(),
                dailyDelta * 365);
    }

    // ==================================================================================
    // Copying
    // ==================================================================================

    private int copyWorlds(List<String> worldNames, Path target,
                           java.util.function.Predicate<Path> filter) {
        int copied = 0;
        for (String world : worldNames) {
            copied += copyWorld(world, target, filter);
        }
        return copied;
    }

    private int copyWorld(String worldName, Path target,
                          java.util.function.Predicate<Path> filter) {
        Optional<File> regions = worlds.folderOf(worldName).flatMap(RegionFiles::regionFolderOf);
        if (regions.isEmpty()) {
            return 0;
        }
        try {
            return copyInto(regions.get().toPath(), target.resolve(worldName), filter);
        } catch (IOException | UncheckedIOException e) {
            logger.log(Level.SEVERE, "Could not back up world " + worldName, e);
            return 0;
        }
    }

    private int copyInto(Path from, Path to, java.util.function.Predicate<Path> filter)
            throws IOException {
        List<Path> regions = RegionFiles.listRegions(from);
        if (regions.isEmpty()) {
            return 0;
        }
        Files.createDirectories(to);

        int copied = 0;
        for (Path region : regions) {
            if (!filter.test(region)) {
                continue;
            }
            Files.copy(region, to.resolve(region.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            copied++;
        }
        return copied;
    }

    private static boolean isNewerThan(Path region, Instant since) {
        try {
            return Files.getLastModifiedTime(region).toInstant().isAfter(since);
        } catch (IOException e) {
            // Unreadable timestamp: copy it. Over-copying costs disk, under-copying costs a
            // player's build, and only one of those is recoverable.
            return true;
        }
    }

    // ==================================================================================
    // Housekeeping
    // ==================================================================================

    /** When the last backup of any tier ran, which is what an incremental measures against. */
    public Optional<Instant> lastRun() {
        return Stream.of(newest(FULL_PREFIX), newest(INCREMENTAL_PREFIX))
                .flatMap(Optional::stream)
                .flatMap(path -> timestampOf(path).stream())
                .max(Comparator.naturalOrder());
    }

    private Optional<Path> newest(String prefix) {
        return listWithPrefix(prefix).stream().max(Comparator.comparing(Path::getFileName));
    }

    private int countWithPrefix(String prefix) {
        return listWithPrefix(prefix).size();
    }

    private List<Path> listWithPrefix(String prefix) {
        if (!Files.isDirectory(backupRoot)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(backupRoot)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            logger.log(Level.FINE, "Could not list " + backupRoot, e);
            return List.of();
        }
    }

    static Optional<Instant> timestampOf(Path folder) {
        String name = folder.getFileName().toString();
        int dash = name.indexOf('-');
        if (dash < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.from(TIMESTAMP.parse(name.substring(dash + 1))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void prune(String prefix, int keep) {
        List<Path> existing = new ArrayList<>(listWithPrefix(prefix));
        for (int i = 0; i < existing.size() - Math.max(0, keep); i++) {
            deleteRecursively(existing.get(i));
        }
    }

    private void pruneOlderThan(String prefix, Instant cutoff) {
        for (Path folder : listWithPrefix(prefix)) {
            if (timestampOf(folder).map(cutoff::isAfter).orElse(false)) {
                deleteRecursively(folder);
            }
        }
    }

    private long sizeOfTree(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private void deleteRecursively(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not remove old backup " + folder, e);
        }
    }

    /** Which snapshots exist, for the status report and for the prune sweep. */
    public Map<Integer, Path> warSnapshots() {
        Map<Integer, Path> found = new LinkedHashMap<>();
        for (Path folder : listWithPrefix(WAR_PREFIX)) {
            String suffix = folder.getFileName().toString().substring(WAR_PREFIX.length());
            try {
                found.put(Integer.parseInt(suffix), folder);
            } catch (NumberFormatException ignored) {
                // Not one of ours. Left alone rather than deleted.
            }
        }
        return found;
    }
}
