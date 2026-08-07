package dev.civitas.storage;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.storage.row.CityRankRow;
import dev.civitas.storage.row.CityRow;
import dev.civitas.storage.row.PlayerRow;

/**
 * Shared fixtures for the storage tests.
 *
 * <p>Every test runs against a real SQLite file in a JUnit temporary directory, not a mock.
 * The point of these tests is that the SQL is correct, and a mock cannot tell you that a
 * unique index rejects a duplicate or that money survives a round trip.
 *
 * <p>MySQL is covered too, but only when a server is configured — see {@link #mySqlUrl()}.
 * Against a real server, never an emulator: exercising the MySQL path against a compatibility
 * layer would prove something about the layer rather than about MySQL.
 */
final class StorageTestSupport {

    /**
     * System property naming a MySQL or MariaDB server for the dialect tests.
     *
     * <p>Absent by default, and the MySQL tests skip rather than fail when it is: a developer
     * without a server must still be able to build, and a suite that goes red on a machine
     * where nothing is wrong teaches people to ignore it.
     *
     * <pre>
     * ./gradlew test -Dcivitas.test.mysql.url=jdbc:mysql://127.0.0.1:3306/civitas_test \
     *                -Dcivitas.test.mysql.user=root
     * </pre>
     *
     * <p>The named schema is <b>dropped and recreated</b> by each test, so it must be a
     * throwaway. Nothing here is safe to point at a database that matters.
     */
    static final String MYSQL_URL_PROPERTY = "civitas.test.mysql.url";
    static final String MYSQL_USER_PROPERTY = "civitas.test.mysql.user";
    static final String MYSQL_PASSWORD_PROPERTY = "civitas.test.mysql.password";

    private StorageTestSupport() {
    }

    /** The configured MySQL URL, or null when the dialect tests should skip. */
    static String mySqlUrl() {
        String url = System.getProperty(MYSQL_URL_PROPERTY);
        return url == null || url.isBlank() ? null : url;
    }

    static String mySqlUser() {
        return System.getProperty(MYSQL_USER_PROPERTY, "root");
    }

    static String mySqlPassword() {
        return System.getProperty(MYSQL_PASSWORD_PROPERTY, "");
    }

    /**
     * Opens the configured MySQL database and migrates it from empty.
     *
     * <p>Every table is dropped first, so each test starts from nothing and the migration
     * runner is exercised on a genuinely fresh schema rather than on one a previous test left
     * half-built. That is the point of the exercise: until M23 the fourteen MySQL migration
     * files had never been executed by anything.
     */
    static DatabaseManager openMySql(BooleanSupplier onMainThread) {
        dropEverything();
        DatabaseManager db = new DatabaseManager(quietLogger(), mySqlSettings(), onMainThread);
        db.open();
        return db;
    }

    static DatabaseManager openMySql() {
        return openMySql(() -> false);
    }

    static DatabaseSettings mySqlSettings() {
        return new DatabaseSettings(
                SqlDialect.MYSQL,
                mySqlUrl(),
                mySqlUser(),
                mySqlPassword(),
                2,
                5000,
                "WAL",            // ignored on MySQL
                Long.MAX_VALUE,
                false,
                6,
                28);
    }

    /**
     * Empties the schema.
     *
     * <p>Foreign key checks are suspended for the duration rather than the tables being sorted
     * into dependency order: the order changes every time a migration adds a table, and a drop
     * that fails because of a constraint would leave the next test running against a schema
     * that is neither empty nor migrated.
     */
    private static void dropEverything() {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                mySqlUrl(), mySqlUser(), mySqlPassword())) {
            java.util.List<String> tables = new java.util.ArrayList<>();
            try (java.sql.ResultSet rows = connection.getMetaData()
                    .getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
                while (rows.next()) {
                    tables.add(rows.getString("TABLE_NAME"));
                }
            }
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (String table : tables) {
                    statement.execute("DROP TABLE IF EXISTS `" + table + "`");
                }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not clear the MySQL test schema", e);
        }
    }

    /** Opens a fresh SQLite database in {@code directory} and migrates it. */
    static DatabaseManager openSqlite(Path directory) {
        return openSqlite(directory, "test.db", () -> false);
    }

    /**
     * @param onMainThread what the main-thread guard should report; pass {@code () -> true}
     *                     to prove that queries from the server thread are refused
     */
    static DatabaseManager openSqlite(Path directory, String fileName, BooleanSupplier onMainThread) {
        DatabaseManager db = new DatabaseManager(quietLogger(), settings(directory, fileName), onMainThread);
        // Migrations run straight off the DataSource, so they are unaffected by the guard.
        db.open();
        return db;
    }

    static DatabaseSettings settings(Path directory, String fileName) {
        return new DatabaseSettings(
                SqlDialect.SQLITE,
                "jdbc:sqlite:" + directory.resolve(fileName).toAbsolutePath(),
                "",
                "",
                2,
                5000,
                "WAL",
                Long.MAX_VALUE,   // never warn about slow queries in tests
                false,
                6,
                28);
    }

    static Logger quietLogger() {
        Logger logger = Logger.getLogger("civitas-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    // --- row builders -----------------------------------------------------------------

    static PlayerRow player(UUID uuid, String name, BigDecimal balance) {
        return new PlayerRow(uuid, name, balance, null, null,
                1_000L, 2_000L, 3_000L, 4_000L, 5, 6_000L, 7_000L, false, 0L, 0L);
    }

    static CityRow city(String name, UUID mayor, BigDecimal treasury) {
        return new CityRow(0, name, "<gold>" + name, name.substring(0, Math.min(4, name.length())),
                mayor, 10_000L, treasury, "world", 3, -7,
                48.5, 64.0, -112.5, 90.0f, 0.0f,
                false, "A test city", 20_000L, null, 0L, false, null);
    }

    static CityRankRow rank(int cityId, String name, int weight, long permissions, boolean isDefault) {
        return new CityRankRow(0, cityId, name, weight, permissions, isDefault);
    }
}
