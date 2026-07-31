package dev.civitas.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Scheduled database backups, SPEC 16.1 {@code storage.backup}.
 *
 * <p>SQLite is backed up with {@code VACUUM INTO}, which produces a consistent copy while
 * the server keeps running; copying the file by hand would risk capturing a half-written
 * page. Old backups are pruned to {@code keep-count} so the folder cannot grow without
 * bound on a long-lived server.
 *
 * <p>MySQL is deliberately <strong>not</strong> backed up here. A correct MySQL backup means
 * {@code mysqldump} or a snapshot at the storage layer, and a plugin shelling out to an
 * external binary it cannot verify would give operators a false sense of safety. When MySQL
 * is configured this service says so plainly once, at startup, and does nothing else.
 */
public final class BackupService {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private static final String PREFIX = "civitas-";
    private static final String SUFFIX = ".db";

    private final Logger logger;
    private final DatabaseManager db;
    private final File backupFolder;

    public BackupService(Logger logger, DatabaseManager db, File backupFolder) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.db = Objects.requireNonNull(db, "db");
        this.backupFolder = Objects.requireNonNull(backupFolder, "backupFolder");
    }

    /** Whether this backend can be backed up in-process. */
    public boolean isSupported() {
        return db.dialect() == SqlDialect.SQLITE;
    }

    /**
     * Logs, once, why nothing will be backed up on an unsupported backend. Called at startup
     * so an operator learns this before they need a backup rather than after.
     */
    public void warnIfUnsupported() {
        if (!isSupported()) {
            logger.warning("storage.backup is enabled but the backend is "
                    + db.dialect() + ", which this plugin cannot back up in-process.");
            logger.warning("Configure mysqldump or a storage-level snapshot yourself. "
                    + "No backups will be written by CivitasCraft.");
        }
    }

    /**
     * Writes one backup and prunes old ones.
     *
     * @param keepCount how many backup files to retain, oldest deleted first
     * @return the file written, or empty if the backend is unsupported
     */
    public CompletableFuture<Optional<File>> backupNow(int keepCount) {
        if (!isSupported()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return db.call(connection -> {
            File target = newBackupFile();
            try (Statement statement = connection.createStatement()) {
                // The path is a literal in the SQL, so a quote in it would break out of the
                // string. SQLite escapes a quote by doubling it.
                statement.execute("VACUUM INTO '"
                        + target.getAbsolutePath().replace("'", "''") + "'");
            }
            return target;
        }).thenApply(file -> {
            logger.info(() -> "Wrote database backup " + file.getName()
                    + " (" + (file.length() / 1024L) + " KiB).");
            prune(keepCount);
            return Optional.of(file);
        }).exceptionally(error -> {
            logger.log(Level.SEVERE, "Database backup failed", error);
            return Optional.empty();
        });
    }

    private File newBackupFile() {
        if (!backupFolder.isDirectory() && !backupFolder.mkdirs()) {
            throw new StorageException("Could not create backup folder " + backupFolder);
        }
        return new File(backupFolder, PREFIX + TIMESTAMP.format(Instant.now()) + SUFFIX);
    }

    /** Deletes the oldest backups until at most {@code keepCount} remain. */
    void prune(int keepCount) {
        List<Path> backups = listBackups();
        if (backups.size() <= keepCount) {
            return;
        }

        for (Path stale : backups.subList(0, backups.size() - keepCount)) {
            try {
                Files.deleteIfExists(stale);
                logger.fine(() -> "Pruned old backup " + stale.getFileName());
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not delete old backup " + stale, e);
            }
        }
    }

    /** Existing backup files, oldest first. */
    List<Path> listBackups() {
        if (!backupFolder.isDirectory()) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(backupFolder.toPath())) {
            List<Path> backups = new ArrayList<>(files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
                    })
                    .toList());
            // By name, which sorts chronologically because the timestamp is fixed-width and
            // most-significant-first. Safer than file modification time, which a restore or
            // a file copy can rewrite.
            backups.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return backups;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not list the backup folder", e);
            return List.of();
        }
    }

    /** Exposed so a caller can report where backups land. */
    public File folder() {
        return backupFolder;
    }
}
