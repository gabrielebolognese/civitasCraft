package dev.civitas.storage.migration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.StorageException;

/**
 * Applies pending schema migrations, in order, each in its own transaction.
 *
 * <p>Discovery is driven by an {@code index.txt} beside the scripts rather than by listing
 * a directory, because a classpath folder cannot be listed portably once the plugin is
 * packaged in a jar. {@code MigrationIndexTest} asserts the index and the files on disk
 * agree, so the indirection cannot silently drift.
 *
 * <p>A migration that has already been applied is never re-run and never re-checked, so
 * editing a released migration has no effect. Schema changes always go in a new file, which
 * is the rule CLAUDE.md states for SPEC Section 3.
 */
public final class MigrationRunner {

    /** Where applied versions are recorded. Owned by this class, not by any migration. */
    public static final String VERSION_TABLE = "schema_version";

    private static final String INDEX_FILE = "index.txt";

    private final Logger logger;
    private final SqlDialect dialect;

    public MigrationRunner(Logger logger, SqlDialect dialect) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    /**
     * Brings the schema up to date.
     *
     * @return the versions applied by this call, empty if the schema was already current
     * @throws StorageException if a migration fails; the failing migration is rolled back
     *                          and no later migration is attempted
     */
    public List<Integer> run(DataSource dataSource) {
        List<Migration> available = discover();
        List<Integer> applied = new ArrayList<>();

        try (Connection connection = dataSource.getConnection()) {
            createVersionTable(connection);
            TreeSet<Integer> alreadyApplied = readAppliedVersions(connection);

            for (Migration migration : available) {
                if (alreadyApplied.contains(migration.version())) {
                    continue;
                }
                apply(connection, migration);
                applied.add(migration.version());
                logger.info(() -> "Applied migration V" + migration.version()
                        + " (" + migration.name() + ") to the " + dialect + " database.");
            }
        } catch (SQLException e) {
            throw new StorageException("Schema migration failed", e);
        }

        if (applied.isEmpty()) {
            logger.fine(() -> "Schema is up to date at version " + highestVersion(available) + ".");
        }
        return Collections.unmodifiableList(applied);
    }

    /** The migrations packaged for this dialect, ascending by version. */
    public List<Migration> discover() {
        String folder = dialect.migrationFolder();
        List<String> fileNames = readIndex(folder);

        List<Migration> migrations = fileNames.stream()
                .map(fileName -> Migration.parse(folder, fileName))
                .sorted()
                .collect(Collectors.toList());

        TreeSet<Integer> seen = new TreeSet<>();
        for (Migration migration : migrations) {
            if (!seen.add(migration.version())) {
                throw new StorageException("Duplicate migration version V" + migration.version()
                        + " in " + folder);
            }
        }
        return Collections.unmodifiableList(migrations);
    }

    /** Reads the file names listed in a migration folder's {@code index.txt}. */
    public static List<String> readIndex(String folder) {
        String path = folder + "/" + INDEX_FILE;
        try (InputStream in = open(path)) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new StorageException("Failed to read migration index " + path, e);
        }
    }

    private void createVersionTable(Connection connection) throws SQLException {
        String autoIncrementFreeInt = dialect == SqlDialect.SQLITE ? "INTEGER" : "INT";
        String suffix = dialect == SqlDialect.SQLITE ? "" : " ENGINE = InnoDB DEFAULT CHARSET = utf8mb4";
        String ddl = "CREATE TABLE IF NOT EXISTS " + VERSION_TABLE + " ("
                + "version " + autoIncrementFreeInt + " NOT NULL PRIMARY KEY, "
                + "name VARCHAR(64) NOT NULL, "
                + "applied_at BIGINT NOT NULL)" + suffix;

        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    private TreeSet<Integer> readAppliedVersions(Connection connection) throws SQLException {
        TreeSet<Integer> versions = new TreeSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT version FROM " + VERSION_TABLE)) {
            while (resultSet.next()) {
                versions.add(resultSet.getInt("version"));
            }
        }
        return versions;
    }

    private void apply(Connection connection, Migration migration) throws SQLException {
        List<String> statements = splitStatements(readScript(migration.resourcePath()));
        if (statements.isEmpty()) {
            throw new StorageException("Migration " + migration.resourcePath() + " contains no statements");
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + VERSION_TABLE + " (version, name, applied_at) VALUES (?, ?, ?)")) {
                insert.setInt(1, migration.version());
                insert.setString(2, migration.name());
                insert.setLong(3, System.currentTimeMillis());
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw new StorageException("Migration V" + migration.version()
                    + " (" + migration.name() + ") failed and was rolled back", e);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static String readScript(String resourcePath) {
        try (InputStream in = open(resourcePath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("Failed to read migration " + resourcePath, e);
        }
    }

    private static InputStream open(String resourcePath) throws IOException {
        InputStream in = MigrationRunner.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IOException("Resource not found on the classpath: " + resourcePath);
        }
        return in;
    }

    /**
     * Splits a script into individual statements.
     *
     * <p>Done here rather than by the driver because MySQL rejects multi-statement queries
     * unless {@code allowMultiQueries} is on, and turning that on widens the SQL-injection
     * surface for every other query in the plugin.
     */
    static List<String> splitStatements(String script) {
        StringBuilder current = new StringBuilder();
        List<String> statements = new ArrayList<>();

        for (String line : script.split("\\R")) {
            String withoutComment = stripLineComment(line);
            if (withoutComment.isBlank()) {
                continue;
            }
            current.append(withoutComment).append('\n');

            if (withoutComment.stripTrailing().endsWith(";")) {
                String sql = current.toString().strip();
                statements.add(sql.substring(0, sql.length() - 1).strip());
                current.setLength(0);
            }
        }

        String trailing = current.toString().strip();
        if (!trailing.isEmpty()) {
            statements.add(trailing);
        }
        return statements;
    }

    /** Removes a trailing {@code --} comment, ignoring any inside a quoted string. */
    private static String stripLineComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && c == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int highestVersion(List<Migration> migrations) {
        return migrations.isEmpty() ? 0 : migrations.get(migrations.size() - 1).version();
    }
}
