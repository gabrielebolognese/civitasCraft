package dev.civitas.storage.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.StorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** SPEC 0 rule 5 and CLAUDE.md: schema changes only ever happen through a migration. */
class MigrationRunnerTest {

    @TempDir
    Path directory;

    private static java.util.logging.Logger quietLogger() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("migration-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(java.util.logging.Level.OFF);
        return logger;
    }

    private HikariDataSource sqliteDataSource(String fileName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + directory.resolve(fileName).toAbsolutePath());
        config.setDriverClassName(SqlDialect.SQLITE.driverClassName());
        config.setMaximumPoolSize(1);
        return new HikariDataSource(config);
    }

    @Test
    @DisplayName("a first run applies V1 and records it")
    void firstRunAppliesV1() {
        try (HikariDataSource dataSource = sqliteDataSource("first.db")) {
            List<Integer> applied =
                    new MigrationRunner(quietLogger(), SqlDialect.SQLITE).run(dataSource);

            assertEquals(List.of(1), applied);
        }
    }

    @Test
    @DisplayName("a second run applies nothing, so restarts are cheap and safe")
    void secondRunIsIdempotent() {
        try (HikariDataSource dataSource = sqliteDataSource("idempotent.db")) {
            MigrationRunner runner =
                    new MigrationRunner(quietLogger(), SqlDialect.SQLITE);

            assertFalse(runner.run(dataSource).isEmpty());
            assertTrue(runner.run(dataSource).isEmpty(), "V1 was applied twice");
            assertTrue(runner.run(dataSource).isEmpty());
        }
    }

    @Test
    @DisplayName("applied versions are recorded in schema_version")
    void appliedVersionsAreRecorded() {
        try (HikariDataSource dataSource = sqliteDataSource("recorded.db")) {
            new MigrationRunner(quietLogger(), SqlDialect.SQLITE).run(dataSource);

            Set<Integer> versions = new TreeSet<>();
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement();
                 var rs = statement.executeQuery(
                         "SELECT version FROM " + MigrationRunner.VERSION_TABLE)) {
                while (rs.next()) {
                    versions.add(rs.getInt("version"));
                }
            } catch (java.sql.SQLException e) {
                throw new AssertionError(e);
            }

            assertEquals(Set.of(1), versions);
        }
    }

    @ParameterizedTest
    @EnumSource(SqlDialect.class)
    @DisplayName("each dialect's migrations are discoverable and uniquely versioned")
    void migrationsAreDiscoverable(SqlDialect dialect) {
        List<Migration> migrations =
                new MigrationRunner(quietLogger(), dialect).discover();

        assertFalse(migrations.isEmpty(), dialect + " has no migrations");
        assertEquals(migrations.size(),
                migrations.stream().map(Migration::version).distinct().count(),
                dialect + " has duplicate migration versions");
        assertEquals(1, migrations.get(0).version(), "migrations must start at V1");
    }

    @ParameterizedTest
    @EnumSource(SqlDialect.class)
    @DisplayName("index.txt lists exactly the migration files that exist on disk")
    void indexMatchesFilesOnDisk(SqlDialect dialect) {
        File folder = new File("src/main/resources/" + dialect.migrationFolder());
        assertTrue(folder.isDirectory(), "missing migration folder " + folder);

        Set<String> onDisk;
        try (Stream<Path> files = Files.list(folder.toPath())) {
            onDisk = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }

        Set<String> indexed = new TreeSet<>(MigrationRunner.readIndex(dialect.migrationFolder()));

        assertEquals(onDisk, indexed,
                dialect + "/index.txt and the .sql files on disk disagree");
    }

    @Test
    @DisplayName("a badly named migration file is rejected rather than silently skipped")
    void badFileNameIsRejected() {
        assertThrows(StorageException.class, () -> Migration.parse("migrations/sqlite", "init.sql"));
        assertThrows(StorageException.class, () -> Migration.parse("migrations/sqlite", "V1_init.sql"));
        assertThrows(StorageException.class, () -> Migration.parse("migrations/sqlite", "Vx__init.sql"));

        Migration parsed = Migration.parse("migrations/sqlite", "V12__add_bans.sql");
        assertEquals(12, parsed.version());
        assertEquals("add_bans", parsed.name());
        assertEquals("migrations/sqlite/V12__add_bans.sql", parsed.resourcePath());
    }

    @Test
    @DisplayName("statement splitting ignores semicolons inside comments and string literals")
    void statementSplitting() {
        String script = """
                -- a comment; with a semicolon
                CREATE TABLE a (x INT);
                CREATE TABLE b (
                    y VARCHAR(8) NOT NULL DEFAULT ''   -- trailing comment
                );
                """;

        List<String> statements = MigrationRunner.splitStatements(script);

        assertEquals(2, statements.size(), "expected two statements, got: " + statements);
        assertTrue(statements.get(0).startsWith("CREATE TABLE a"));
        assertTrue(statements.get(1).startsWith("CREATE TABLE b"));
        assertFalse(statements.get(1).contains("trailing comment"));
    }
}
