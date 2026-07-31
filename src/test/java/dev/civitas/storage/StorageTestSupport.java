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
 * <p>MySQL is not covered: there is no server to test against here, and exercising the MySQL
 * path against a compatibility emulator would prove something about the emulator rather than
 * about MySQL.
 */
final class StorageTestSupport {

    private StorageTestSupport() {
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
