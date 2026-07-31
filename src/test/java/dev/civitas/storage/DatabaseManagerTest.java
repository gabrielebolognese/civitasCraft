package dev.civitas.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SPEC 2.1 hard rule: zero database access on the main thread, ever. */
class DatabaseManagerTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("a query from the server thread is refused, not merely slow")
    void mainThreadAccessIsRefused() {
        try (DatabaseManager db = StorageTestSupport.openSqlite(directory, "guard.db", () -> true)) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> db.callSync(connection -> connection.createStatement().execute("SELECT 1")));

            assertTrue(thrown.getMessage().contains("main thread"),
                    "the guard must say why it refused: " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("the async path still refuses when the guard reports the server thread")
    void asyncPathHonoursTheGuard() {
        try (DatabaseManager db = StorageTestSupport.openSqlite(directory, "guard-async.db", () -> true)) {
            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> db.call(connection -> 1).get(5, TimeUnit.SECONDS));

            assertInstanceOf(IllegalStateException.class, thrown.getCause());
        }
    }

    @Test
    @DisplayName("work off the server thread runs and returns its result")
    void asyncWorkReturnsResult() throws Exception {
        try (DatabaseManager db = StorageTestSupport.openSqlite(directory)) {
            long tables = db.call(connection -> {
                try (var statement = connection.createStatement();
                     var rs = statement.executeQuery(
                             "SELECT COUNT(*) AS total FROM sqlite_master WHERE type = 'table'")) {
                    return rs.next() ? rs.getLong("total") : 0L;
                }
            }).get(10, TimeUnit.SECONDS);

            assertTrue(tables > 20, "expected the full schema, found " + tables + " tables");
        }
    }

    @Test
    @DisplayName("a transaction that throws rolls back and leaves no partial write")
    void transactionRollsBackOnFailure() throws Exception {
        try (DatabaseManager db = StorageTestSupport.openSqlite(directory)) {
            assertThrows(ExecutionException.class, () -> db.transaction(connection -> {
                connection.createStatement().executeUpdate(
                        "INSERT INTO market_stock (material, current_stock, target_stock, base_price) "
                                + "VALUES ('WHEAT', 0, 100, 300)");
                throw new IllegalStateException("deliberate failure");
            }).get(10, TimeUnit.SECONDS));

            long rows = db.call(connection -> {
                try (var statement = connection.createStatement();
                     var rs = statement.executeQuery("SELECT COUNT(*) AS total FROM market_stock")) {
                    return rs.next() ? rs.getLong("total") : -1L;
                }
            }).get(10, TimeUnit.SECONDS);

            assertEquals(0L, rows, "the failed transaction left a row behind");
        }
    }

    @Test
    @DisplayName("a transaction that returns commits every statement in it")
    void transactionCommitsOnSuccess() throws Exception {
        try (DatabaseManager db = StorageTestSupport.openSqlite(directory)) {
            db.transaction(connection -> {
                connection.createStatement().executeUpdate(
                        "INSERT INTO market_stock (material, current_stock, target_stock, base_price) "
                                + "VALUES ('WHEAT', 0, 100, 300)");
                connection.createStatement().executeUpdate(
                        "INSERT INTO market_stock (material, current_stock, target_stock, base_price) "
                                + "VALUES ('CARROT', 0, 100, 300)");
                return null;
            }).get(10, TimeUnit.SECONDS);

            long rows = db.call(connection -> {
                try (var statement = connection.createStatement();
                     var rs = statement.executeQuery("SELECT COUNT(*) AS total FROM market_stock")) {
                    return rs.next() ? rs.getLong("total") : -1L;
                }
            }).get(10, TimeUnit.SECONDS);

            assertEquals(2L, rows);
        }
    }

    @Test
    @DisplayName("close drains outstanding work and marks the manager closed")
    void closeDrainsWork() {
        DatabaseManager db = StorageTestSupport.openSqlite(directory);
        assertTrue(db.isOpen());

        db.call(connection -> connection.createStatement().execute("SELECT 1"));
        db.close();

        assertFalse(db.isOpen());
        assertThrows(IllegalStateException.class, () -> db.call(connection -> 1));
    }

    @Test
    @DisplayName("SQLite stores money as minor units and reads back exact cents")
    void moneySurvivesTheRoundTrip() throws Exception {
        try (DatabaseManager db = StorageTestSupport.openSqlite(directory)) {
            BigDecimal awkward = new BigDecimal("1234567890.07");

            db.call(connection -> {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO market_stock (material, current_stock, target_stock, base_price) "
                                + "VALUES ('DIAMOND', 0, 1500, ?)")) {
                    db.dialect().setMoney(statement, 1, awkward);
                    return statement.executeUpdate();
                }
            }).get(10, TimeUnit.SECONDS);

            BigDecimal readBack = db.call(connection -> {
                try (var statement = connection.prepareStatement(
                        "SELECT base_price FROM market_stock WHERE material = 'DIAMOND'");
                     var rs = statement.executeQuery()) {
                    return rs.next() ? db.dialect().getMoney(rs, "base_price") : null;
                }
            }).get(10, TimeUnit.SECONDS);

            assertEquals(0, awkward.compareTo(readBack),
                    "money drifted: wrote " + awkward + ", read " + readBack);
            assertEquals(SqlDialect.MONEY_SCALE, readBack.scale());
        }
    }

    @Test
    @DisplayName("a value that a double cannot hold exactly still round-trips")
    void moneyAvoidsFloatingPointDrift() throws Exception {
        try (DatabaseManager db = StorageTestSupport.openSqlite(directory)) {
            // 0.10 and 0.20 are both inexact as binary floats; their sum is the classic case.
            db.call(connection -> {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO market_stock (material, current_stock, target_stock, base_price) "
                                + "VALUES ('STONE', 0, 50000, ?)")) {
                    db.dialect().setMoney(statement, 1, new BigDecimal("0.10"));
                    return statement.executeUpdate();
                }
            }).get(10, TimeUnit.SECONDS);

            BigDecimal total = db.call(connection -> {
                try (var statement = connection.prepareStatement(
                        "UPDATE market_stock SET base_price = base_price + ? WHERE material = 'STONE'")) {
                    db.dialect().setMoney(statement, 1, new BigDecimal("0.20"));
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement(
                        "SELECT base_price FROM market_stock WHERE material = 'STONE'");
                     var rs = statement.executeQuery()) {
                    return rs.next() ? db.dialect().getMoney(rs, "base_price") : null;
                }
            }).get(10, TimeUnit.SECONDS);

            assertEquals(new BigDecimal("0.30"), total);
        }
    }
}
