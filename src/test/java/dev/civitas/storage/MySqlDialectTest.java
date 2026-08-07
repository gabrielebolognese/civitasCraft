package dev.civitas.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The MySQL path, against a real server.
 *
 * <p>OPEN_QUESTIONS has said "MySQL is not covered locally" since M1, and the gap was larger
 * than it sounded: the fourteen files under {@code migrations/mysql/} had <b>never been
 * executed by anything</b>. They were written alongside their SQLite counterparts, reviewed by
 * eye, and checked by {@code MigrationIndexTest} only for being listed in {@code index.txt}. A
 * syntax error in any of them would have surfaced the first time an operator set
 * {@code storage.type: MYSQL}, at which point the plugin fails to start.
 *
 * <h2>Skipped unless a server is named</h2>
 *
 * <p>Every test here is gated on {@code civitas.test.mysql.url}. A developer without a server
 * must still be able to build, and a suite that goes red where nothing is wrong teaches people
 * to ignore it. The trade is that a green run does not by itself mean MySQL was exercised —
 * which is why {@link #theseTestsRanOrSaidWhyNot} exists and why the property is forwarded
 * explicitly in {@code build.gradle.kts}.
 *
 * <p><b>The named schema is dropped and recreated by every test.</b> Point it at a throwaway.
 *
 * <h2>What is worth testing here and what is not</h2>
 *
 * <p>Not the business logic: a {@code CityService} rule behaves the same whichever database is
 * underneath, and re-running the whole suite against MySQL would take a long time to prove
 * nothing new. What differs by dialect is narrow and this covers it — the DDL, the money
 * representation (SPEC 3 says {@code DECIMAL(20,2)}, which SQLite cannot do and where M1's
 * minor-units workaround does not apply), the unique index SPEC 3.4 calls a physical
 * guarantee, and transaction rollback.
 */
@EnabledIfSystemProperty(named = StorageTestSupport.MYSQL_URL_PROPERTY, matches = ".+")
class MySqlDialectTest {

    private DatabaseManager db;

    @BeforeEach
    void openDatabase() {
        db = StorageTestSupport.openMySql();
    }

    @AfterEach
    void closeDatabase() {
        if (db != null) {
            db.close();
        }
    }

    private static Set<String> tableNames(Connection connection) throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (ResultSet rows = connection.getMetaData()
                .getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (rows.next()) {
                tables.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    // ==================================================================================
    // The migrations, which had never run
    // ==================================================================================

    @Nested
    @DisplayName("migrations")
    class Migrations {

        @Test
        @DisplayName("every MySQL migration applies to an empty schema")
        void allApply() throws Exception {
            // The whole reason this class exists. openMySql drops every table and runs the
            // migration runner from nothing, so reaching this line at all means all fourteen
            // files parsed and executed on a real server.
            try (Connection connection = db.dataSource().getConnection()) {
                Set<String> tables = tableNames(connection);

                assertTrue(tables.contains(
                                dev.civitas.storage.migration.MigrationRunner.VERSION_TABLE),
                        "the migration runner's own bookkeeping table is missing");
                assertTrue(tables.size() > 25,
                        "only " + tables.size() + " tables exist, so the run stopped early");
            }
        }

        @Test
        @DisplayName("every migration in index.txt is recorded as applied")
        void allRecorded() throws Exception {
            List<String> indexed = migrationIndex();

            try (Connection connection = db.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT version FROM "
                                 + dev.civitas.storage.migration.MigrationRunner.VERSION_TABLE);
                 ResultSet rows = statement.executeQuery()) {
                Set<String> applied = new TreeSet<>();
                while (rows.next()) {
                    applied.add(rows.getString(1));
                }
                assertEquals(indexed.size(), applied.size(),
                        "index.txt lists " + indexed.size() + " migrations but "
                                + applied.size() + " were applied: " + applied);
            }
        }

        @Test
        @DisplayName("running the migrator twice applies nothing the second time")
        void idempotent() throws Exception {
            // An applied migration is never re-run. Without this a restart would try to
            // CREATE TABLE over a live schema and fail the server's second start, which is
            // the worst possible time to find out.
            int before = appliedCount();
            db.close();
            db = new DatabaseManager(StorageTestSupport.quietLogger(),
                    StorageTestSupport.mySqlSettings(), () -> false);
            db.open();

            assertEquals(before, appliedCount(), "the second open re-applied migrations");
        }

        private int appliedCount() throws SQLException {
            try (Connection connection = db.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT COUNT(*) FROM "
                                 + dev.civitas.storage.migration.MigrationRunner.VERSION_TABLE);
                 ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }

        private List<String> migrationIndex() throws Exception {
            try (var stream = getClass().getClassLoader()
                    .getResourceAsStream("migrations/mysql/index.txt")) {
                assertTrue(stream != null, "migrations/mysql/index.txt is missing");
                return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
            }
        }
    }

    // ==================================================================================
    // Schema parity with SQLite
    // ==================================================================================

    @Nested
    @DisplayName("schema")
    class Schema {

        @Test
        @DisplayName("MySQL builds the same tables SPEC Section 3 describes")
        void tablesMatchSpec() throws Exception {
            // Asserted against SchemaTest's declaration rather than a second copy of the
            // table list. Two lists would drift, and the failure would look like a MySQL bug
            // rather than a stale test.
            Set<String> expected = new TreeSet<>(SchemaTest.expectedSchema().keySet());

            try (Connection connection = db.dataSource().getConnection()) {
                Set<String> actual = tableNames(connection);
                // The runner's own bookkeeping, which is not part of SPEC Section 3.
                actual.remove(dev.civitas.storage.migration.MigrationRunner.VERSION_TABLE);

                assertEquals(expected, actual,
                        "the MySQL schema and SPEC Section 3 disagree");
            }
        }

        @Test
        @DisplayName("each table has exactly the columns SPEC Section 3 lists")
        void columnsMatchSpec() throws Exception {
            Map<String, List<String>> mismatches = new LinkedHashMap<>();

            try (Connection connection = db.dataSource().getConnection()) {
                DatabaseMetaData metadata = connection.getMetaData();
                for (Map.Entry<String, List<String>> table
                        : SchemaTest.expectedSchema().entrySet()) {
                    Set<String> actual = new TreeSet<>();
                    try (ResultSet rows = metadata.getColumns(connection.getCatalog(), null,
                            table.getKey(), "%")) {
                        while (rows.next()) {
                            actual.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                        }
                    }
                    Set<String> expected = new TreeSet<>(table.getValue());
                    if (!expected.equals(actual)) {
                        mismatches.put(table.getKey(), List.of(
                                "expected " + expected, "actual " + actual));
                    }
                }
            }

            assertTrue(mismatches.isEmpty(),
                    "these tables differ from SPEC Section 3 on MySQL: " + mismatches);
        }

        @Test
        @DisplayName("money columns really are DECIMAL, not a float")
        void moneyIsDecimal() throws Exception {
            // SPEC 3 says DECIMAL(20,2). M1 recorded that SQLite cannot honour that and stores
            // minor units in an INTEGER instead; on MySQL there is no excuse, and a column
            // that came out as DOUBLE would drift cents in a ledger SPEC 1.5 makes the
            // authority for every dispute.
            try (Connection connection = db.dataSource().getConnection()) {
                assertColumnType(connection, "players", "balance");
                assertColumnType(connection, "cities", "treasury");
                assertColumnType(connection, "ledger", "amount");
                assertColumnType(connection, "ledger", "balance_after");
                assertColumnType(connection, "claims", "cost_paid");
                assertColumnType(connection, "wars", "wager");
            }
        }

        private void assertColumnType(Connection connection, String table, String column)
                throws SQLException {
            try (ResultSet rows = connection.getMetaData()
                    .getColumns(connection.getCatalog(), null, table, column)) {
                assertTrue(rows.next(), table + "." + column + " does not exist");
                String type = rows.getString("TYPE_NAME").toUpperCase(Locale.ROOT);
                assertTrue(type.contains("DECIMAL") || type.contains("NUMERIC"),
                        table + "." + column + " is " + type + ", not DECIMAL");
                assertEquals(2, rows.getInt("DECIMAL_DIGITS"),
                        table + "." + column + " does not keep two decimal places");
            }
        }

        @Test
        @DisplayName("the claim chunk index exists and is unique, SPEC 3.4")
        void claimIndexIsUnique() throws Exception {
            // SPEC 3.4 calls this "the physical guarantee that two cities can never own the
            // same chunk". It is the one index whose absence is not a performance problem.
            try (Connection connection = db.dataSource().getConnection();
                 ResultSet rows = connection.getMetaData()
                         .getIndexInfo(connection.getCatalog(), null, "claims", true, false)) {
                Set<String> columns = new TreeSet<>();
                while (rows.next()) {
                    String column = rows.getString("COLUMN_NAME");
                    if (column != null) {
                        columns.add(column.toLowerCase(Locale.ROOT));
                    }
                }
                assertTrue(columns.containsAll(Set.of("world", "chunk_x", "chunk_z")),
                        "no unique index covers (world, chunk_x, chunk_z); found " + columns);
            }
        }
    }

    // ==================================================================================
    // Behaviour that differs by dialect
    // ==================================================================================

    @Nested
    @DisplayName("behaviour")
    class Behaviour {

        @Test
        @DisplayName("money survives a round trip with its cents")
        void moneyRoundTrips() throws Exception {
            // The reason SqlDialect.setMoney and getMoney exist. On SQLite they convert to
            // minor units; on MySQL they must pass a real BigDecimal through untouched, and
            // binding it any other way loses cents.
            List<BigDecimal> amounts = List.of(
                    new BigDecimal("0.01"),
                    new BigDecimal("1234.56"),
                    new BigDecimal("999999999999.99"),
                    new BigDecimal("-4321.09"),
                    BigDecimal.ZERO);

            UUID uuid = UUID.randomUUID();
            for (BigDecimal amount : amounts) {
                try (Connection connection = db.dataSource().getConnection()) {
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM players WHERE uuid = ?")) {
                        delete.setString(1, uuid.toString());
                        delete.executeUpdate();
                    }
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO players (uuid, last_known_name, balance, first_join, "
                                    + "last_seen, total_playtime_ms, active_playtime_ms, "
                                    + "daily_streak, last_daily_claim, newcomer_until, frozen) "
                                    + "VALUES (?, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0)")) {
                        insert.setString(1, uuid.toString());
                        insert.setString(2, "Tester");
                        db.dialect().setMoney(insert, 3, amount);
                        insert.executeUpdate();
                    }
                    try (PreparedStatement select = connection.prepareStatement(
                            "SELECT balance FROM players WHERE uuid = ?")) {
                        select.setString(1, uuid.toString());
                        try (ResultSet rows = select.executeQuery()) {
                            assertTrue(rows.next());
                            assertEquals(0, amount.compareTo(
                                            db.dialect().getMoney(rows, "balance")),
                                    amount + " did not survive the round trip");
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("the unique chunk index rejects a second claim on the same chunk")
        void duplicateChunkRejected() throws Exception {
            // SPEC 17.2 case 15: two cities claiming the same chunk in the same tick. The
            // index is what makes the race safe, and an index that exists but is not UNIQUE
            // would pass the metadata test above while failing here.
            try (Connection connection = db.dataSource().getConnection()) {
                insertCity(connection, 1, "Roma", "ROM");
                insertCity(connection, 2, "Ostia", "OST");
                insertClaim(connection, 1, 1);

                assertThrows(SQLException.class, () -> insertClaim(connection, 2, 2),
                        "the second city was allowed to claim the same chunk");
            }
        }

        @Test
        @DisplayName("a transaction returning Failure is rolled back")
        void failureRollsBack() throws Exception {
            // SPEC 2.3's rule, and the reason a service that writes half a change and then
            // refuses the rest cannot commit the half it wrote. Worth asserting per dialect
            // because it depends on the driver honouring setAutoCommit and rollback.
            UUID uuid = UUID.randomUUID();

            Result<Void> result = db.transaction(connection -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO players (uuid, last_known_name, balance, first_join, "
                                + "last_seen, total_playtime_ms, active_playtime_ms, "
                                + "daily_streak, last_daily_claim, newcomer_until, frozen) "
                                + "VALUES (?, 'Doomed', 0, 0, 0, 0, 0, 0, 0, 0, 0)")) {
                    insert.setString(1, uuid.toString());
                    insert.executeUpdate();
                }
                return Result.<Void>failure("CHANGED_MY_MIND", "test.key");
            }).join();

            assertFalse(result.isSuccess());
            assertFalse(playerExists(uuid), "the row survived a rolled-back transaction");
        }

        @Test
        @DisplayName("a transaction that throws is rolled back too")
        void throwRollsBack() throws Exception {
            UUID uuid = UUID.randomUUID();

            assertThrows(CompletionException.class, () -> db.transaction(connection -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO players (uuid, last_known_name, balance, first_join, "
                                + "last_seen, total_playtime_ms, active_playtime_ms, "
                                + "daily_streak, last_daily_claim, newcomer_until, frozen) "
                                + "VALUES (?, 'Doomed', 0, 0, 0, 0, 0, 0, 0, 0, 0)")) {
                    insert.setString(1, uuid.toString());
                    insert.executeUpdate();
                }
                throw new IllegalStateException("boom");
            }).join());

            assertFalse(playerExists(uuid));
        }

        @Test
        @DisplayName("a query from the server thread is refused on MySQL as well")
        void mainThreadGuardHolds() {
            // SPEC 2.1's hard rule is enforced rather than trusted, and the guard lives in
            // DatabaseManager rather than in the dialect — but a dialect-specific code path
            // that bypassed callSync would be invisible without asserting it here.
            DatabaseManager guarded = StorageTestSupport.openMySql(() -> true);
            try {
                assertThrows(IllegalStateException.class,
                        () -> guarded.callSync(connection -> 1));
            } finally {
                guarded.close();
            }
        }

        private boolean playerExists(UUID uuid) throws SQLException {
            try (Connection connection = db.dataSource().getConnection();
                 PreparedStatement select = connection.prepareStatement(
                         "SELECT 1 FROM players WHERE uuid = ?")) {
                select.setString(1, uuid.toString());
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next();
                }
            }
        }

        private void insertCity(Connection connection, int id, String name, String tag)
                throws SQLException {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cities (id, name, display_name, tag, mayor_uuid, founded_at, "
                            + "treasury, core_world, core_chunk_x, core_chunk_z, spawn_x, "
                            + "spawn_y, spawn_z, spawn_yaw, spawn_pitch, open_join, motd, "
                            + "upkeep_due, war_protection_until, frozen) "
                            + "VALUES (?, ?, ?, ?, ?, 0, 0, 'world', 0, 0, 0, 0, 0, 0, 0, 0, "
                            + "'', 0, 0, 0)")) {
                insert.setInt(1, id);
                insert.setString(2, name);
                insert.setString(3, name);
                insert.setString(4, tag);
                insert.setString(5, UUID.randomUUID().toString());
                insert.executeUpdate();
            }
        }

        private void insertClaim(Connection connection, long id, int cityId) throws SQLException {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO claims (id, city_id, world, chunk_x, chunk_z, claimed_at, "
                            + "claimed_by, cost_paid, type) "
                            + "VALUES (?, ?, 'world', 5, 5, 0, ?, 0, 'NORMAL')")) {
                insert.setLong(1, id);
                insert.setInt(2, cityId);
                insert.setString(3, UUID.randomUUID().toString());
                insert.executeUpdate();
            }
        }
    }

    // ==================================================================================
    // The gate itself
    // ==================================================================================

    @Test
    @DisplayName("this class ran against a real server, and says which")
    void theseTestsRanOrSaidWhyNot() {
        // Printed so a build log distinguishes "MySQL passed" from "MySQL was skipped". The
        // two look identical in a green run otherwise, which is how a dialect goes untested
        // for twenty-two milestones.
        System.out.println("MySQL dialect tests ran against " + StorageTestSupport.mySqlUrl());
        assertTrue(StorageTestSupport.mySqlUrl() != null);
    }
}
