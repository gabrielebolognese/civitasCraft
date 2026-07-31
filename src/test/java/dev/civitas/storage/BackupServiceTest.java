package dev.civitas.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SPEC 16.1 {@code storage.backup}. */
class BackupServiceTest {

    @TempDir
    Path directory;

    private DatabaseManager db;
    private BackupService backups;
    private File backupFolder;

    @BeforeEach
    void openDatabase() {
        db = StorageTestSupport.openSqlite(directory);
        backupFolder = directory.resolve("backups").toFile();
        backups = new BackupService(StorageTestSupport.quietLogger(), db, backupFolder);
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }

    private Optional<File> backup(int keepCount) throws Exception {
        return backups.backupNow(keepCount).get(30, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a backup is written and is itself a readable database")
    void backupIsAUsableDatabase() throws Exception {
        Optional<File> written = backup(10);

        assertTrue(written.isPresent(), "no backup file was produced");
        File file = written.orElseThrow();
        assertTrue(file.isFile());
        assertTrue(file.length() > 0, "the backup is empty");

        // Reopening it proves VACUUM INTO produced a real database, not a truncated copy.
        DatabaseSettings restored = new DatabaseSettings(SqlDialect.SQLITE,
                "jdbc:sqlite:" + file.getAbsolutePath(), "", "", 1, 5000, "WAL",
                Long.MAX_VALUE, false, 6, 28);
        try (DatabaseManager reopened =
                     new DatabaseManager(StorageTestSupport.quietLogger(), restored, () -> false)) {
            reopened.open();
            long tables = reopened.callSync(connection -> {
                try (var statement = connection.createStatement();
                     var rs = statement.executeQuery(
                             "SELECT COUNT(*) AS total FROM sqlite_master WHERE type = 'table'")) {
                    return rs.next() ? rs.getLong("total") : 0L;
                }
            });
            assertTrue(tables > 20, "the restored backup has only " + tables + " tables");
        }
    }

    @Test
    @DisplayName("old backups are pruned down to keep-count, oldest first")
    void pruneKeepsTheNewest() throws IOException {
        assertTrue(backupFolder.mkdirs());
        // Names are the sort key, and the timestamp format sorts chronologically.
        for (String name : List.of(
                "civitas-20260101-000000.db",
                "civitas-20260102-000000.db",
                "civitas-20260103-000000.db",
                "civitas-20260104-000000.db")) {
            Files.createFile(backupFolder.toPath().resolve(name));
        }
        Files.createFile(backupFolder.toPath().resolve("unrelated.txt"));

        backups.prune(2);

        List<String> remaining = backups.listBackups().stream()
                .map(path -> path.getFileName().toString())
                .toList();

        assertEquals(List.of("civitas-20260103-000000.db", "civitas-20260104-000000.db"), remaining);
        assertTrue(Files.exists(backupFolder.toPath().resolve("unrelated.txt")),
                "pruning must not touch files it did not write");
    }

    @Test
    @DisplayName("pruning does nothing when there are fewer backups than keep-count")
    void pruneIsANoOpBelowTheLimit() throws Exception {
        backup(10);
        assertEquals(1, backups.listBackups().size());

        backups.prune(10);
        assertEquals(1, backups.listBackups().size());
    }

    @Test
    @DisplayName("SQLite is supported in-process; other backends are reported, not faked")
    void supportIsHonest() {
        assertTrue(backups.isSupported());

        DatabaseSettings mysql = new DatabaseSettings(SqlDialect.MYSQL,
                "jdbc:mysql://localhost:3306/civitas", "root", "", 10, 0, "", 250L, true, 6, 28);
        DatabaseManager notOpened =
                new DatabaseManager(StorageTestSupport.quietLogger(), mysql, () -> false);
        BackupService mysqlBackups =
                new BackupService(StorageTestSupport.quietLogger(), notOpened, backupFolder);

        org.junit.jupiter.api.Assertions.assertFalse(mysqlBackups.isSupported());
        // Must not pretend to have written anything, and must not need an open connection.
        assertTrue(mysqlBackups.backupNow(5).join().isEmpty());
    }
}
