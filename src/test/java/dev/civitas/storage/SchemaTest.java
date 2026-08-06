package dev.civitas.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Asserts that the migrated schema is the one SPEC Section 3 describes.
 *
 * <p>This is the test that makes "never modify Section 3 without a migration" enforceable:
 * if a column is renamed, dropped or added without the specification and this file moving
 * together, the build fails.
 */
class SchemaTest {

    @TempDir
    Path directory;

    private DatabaseManager db;

    @BeforeEach
    void openDatabase() {
        db = StorageTestSupport.openSqlite(directory);
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }

    /** SPEC 3.1 to 3.9, plus the two tables SPEC names outside Section 3. */
    static Map<String, List<String>> expectedSchema() {
        Map<String, List<String>> schema = new LinkedHashMap<>();
        schema.put("players", List.of("uuid", "last_known_name", "balance", "city_id", "rank_id",
                "first_join", "last_seen", "total_playtime_ms", "active_playtime_ms", "daily_streak",
                "last_daily_claim", "newcomer_until", "frozen",
                // Added by V2 for the SPEC 5.2 and 17.1 case 7 cooldowns.
                "last_city_leave", "last_city_disband"));
        schema.put("cities", List.of("id", "name", "display_name", "tag", "mayor_uuid", "founded_at",
                "treasury", "core_world", "core_chunk_x", "core_chunk_z", "spawn_x", "spawn_y",
                "spawn_z", "spawn_yaw", "spawn_pitch", "open_join", "motd", "upkeep_due",
                "delinquent_since", "war_protection_until", "frozen", "deleted_at"));
        schema.put("city_ranks", List.of("id", "city_id", "name", "weight", "permissions", "is_default"));
        schema.put("claims", List.of("id", "city_id", "world", "chunk_x", "chunk_z", "claimed_at",
                "claimed_by", "cost_paid", "type", "outpost_id"));
        schema.put("outposts", List.of("id", "city_id", "name", "tp_x", "tp_y", "tp_z", "tp_yaw",
                "tp_pitch", "created_at"));
        schema.put("ledger", List.of("id", "timestamp", "type", "actor_uuid", "target_uuid",
                "city_id", "amount", "balance_after", "metadata"));
        schema.put("wars", List.of("id", "attacker_city_id", "defender_city_id", "declared_at",
                "prep_ends_at", "war_ends_at", "state", "attacker_score", "defender_score",
                "winner_city_id", "wager", "rollback_completed_at", "rollback_checkpoint_sequence"));
        schema.put("war_block_log", List.of("id", "war_id", "sequence", "world", "x", "y", "z",
                "old_block_data", "new_block_data", "old_nbt", "actor_uuid", "timestamp"));
        schema.put("war_container_log", List.of("id", "war_id", "world", "x", "y", "z", "actor_uuid",
                "item", "quantity", "timestamp"));
        schema.put("city_members", List.of("uuid", "city_id", "rank_id", "joined_at", "contributed_total"));
        schema.put("city_invites", List.of("city_id", "invitee_uuid", "inviter_uuid", "expires_at"));
        // Added by V2: SPEC 5.2 and 8.6 need a ban list that SPEC 3 does not define.
        schema.put("city_bans", List.of("city_id", "banned_uuid", "banned_by", "reason", "banned_at"));
        schema.put("alliances", List.of("city_a_id", "city_b_id", "state", "formed_at",
                // V7, SPEC 14.2: the notice period and the re-ally cooldown are both
                // timed from state_changed_at, trusted is the reciprocal build grant,
                // and proposed_by decides who is allowed to accept.
                "state_changed_at", "trusted", "proposed_by"));
        schema.put("truces", List.of("city_a_id", "city_b_id", "expires_at"));
        schema.put("war_participants", List.of("war_id", "city_id", "side", "is_ally"));
        schema.put("war_kills", List.of("id", "war_id", "killer_uuid", "victim_uuid", "timestamp",
                "location"));
        schema.put("market_stock", List.of("material", "current_stock", "target_stock", "base_price"));
        // V4, SPEC 4.5: player chest shops, which SPEC 3 lists no table for.
        schema.put("player_shops", List.of("id", "owner_uuid", "world", "sign_x", "sign_y",
                "sign_z", "chest_x", "chest_y", "chest_z", "material", "quantity", "buy_price",
                "sell_price", "created_at"));
        // V5, SPEC 13.1: target and reward are stored with the assignment, because SPEC 13.1
        // scales both with playtime and recomputing them later would move the goalposts.
        // V5, SPEC 13.1: target and reward are stored with the assignment, because SPEC 13.1
        // scales both with playtime and recomputing them later would move the goalposts under
        // a player who is halfway through one.
        schema.put("player_quests", List.of("id", "uuid", "quest_id", "progress", "assigned_at",
                "completed_at", "target", "reward"));
        // V5, SPEC 13.2: weekly challenges, which SPEC 3 lists no table for. Keyed by city
        // and week because progress is pooled across every member.
        schema.put("city_challenges", List.of("id", "city_id", "challenge_id", "progress",
                "target", "reward", "week_start", "completed_at"));
        schema.put("contests", List.of("id", "theme", "starts_at", "ends_at", "state"));
        // V9, SPEC 13.4: disqualification is a mark rather than a delete so the decision and
        // the votes behind it stay auditable, and the placement is written once at scoring.
        schema.put("contest_entries", List.of("id", "contest_id", "city_id", "plot_region",
                "submitted_at", "score", "disqualified", "disqualified_reason", "placement"));
        // V9, SPEC 13.4 step 4: three axes, plus the anti-abuse weight the vote was given.
        schema.put("contest_votes", List.of("id", "contest_id", "voter_uuid", "entry_id", "score",
                "creativity", "technical_skill", "theme_fit", "weight"));
        schema.put("city_upgrades", List.of("city_id", "upgrade_key", "level"));
        // V6, SPEC 5.7 and 9.2: the shared city vault, which SPEC 3 lists no table for.
        schema.put("city_vault", List.of("city_id", "page", "contents", "updated_at"));
        schema.put("defense_units", List.of("id", "city_id", "type", "world", "spawn_x", "spawn_y",
                "spawn_z", "upkeep", "active"));
        schema.put("audit_log", List.of("id", "timestamp", "actor_uuid", "action", "target",
                "reason", "metadata"));
        // Added by V3: SPEC 4.8 needs circulation history that SPEC 3 does not define.
        schema.put("economy_snapshots", List.of("id", "timestamp", "player_total",
                "treasury_total"));
        // V8, SPEC 13.3: the lifetime counters the Builder and Farmer boards rank. SPEC 3
        // lists no table, and quest progress cannot serve because it resets every day.
        schema.put("player_stats", List.of("uuid", "stat", "value", "updated_at"));
        // V9, SPEC 13.4: a salted hash of the connection address, never the address. It
        // answers "same connection?" for the vote rule and nothing else.
        schema.put("player_logins", List.of("uuid", "login_hash", "updated_at"));
        // V10, SPEC 13.5: one row per run of a server event. SPEC 3 lists no table, and
        // without one a restart silently cancels whatever was running.
        schema.put("server_events", List.of("id", "event_key", "starts_at", "ends_at",
                "ended_at", "announced"));
        // V11, SPEC 11.8.4: the chunk checksums taken before a war and after its rollback.
        schema.put("war_chunk_hashes", List.of("war_id", "world", "chunk_x", "chunk_z",
                "hash_before", "hash_after"));
        // V11, SPEC 17.4 case 57: what a rollback could not put back, kept for the admin who
        // has to explain it. SPEC 3 lists neither table.
        schema.put("war_rollback_issues", List.of("id", "war_id", "kind", "world", "x", "y", "z",
                "detail", "detected_at"));
        return schema;
    }

    static List<String> expectedTables() {
        return List.copyOf(expectedSchema().keySet());
    }

    private Set<String> tableNames() {
        return db.callSync(connection -> {
            Set<String> tables = new TreeSet<>();
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                    if (!name.startsWith("sqlite_")) {
                        tables.add(name);
                    }
                }
            }
            return tables;
        });
    }

    private List<String> columnNames(String table) {
        return db.callSync(connection -> {
            List<String> columns = new java.util.ArrayList<>();
            try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, "%")) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            return columns;
        });
    }

    private Set<String> indexNames(String table) {
        return db.callSync(connection -> {
            Set<String> indexes = new TreeSet<>();
            try (ResultSet rs = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    if (name != null) {
                        indexes.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
            return indexes;
        });
    }

    @Test
    @DisplayName("every table in SPEC Section 3 exists, and no unexpected table does")
    void tablesMatchTheSpecification() {
        Set<String> actual = tableNames();
        Set<String> expected = new TreeSet<>(expectedTables());
        expected.add("schema_version");

        assertEquals(expected, actual,
                "the migrated schema and SPEC Section 3 have diverged");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expectedTables")
    @DisplayName("each table has exactly the columns SPEC Section 3 lists")
    void columnsMatchTheSpecification(String table) {
        assertEquals(expectedSchema().get(table), columnNames(table),
                table + " columns differ from SPEC Section 3");
    }

    @Test
    @DisplayName("the claim chunk index exists and is unique, SPEC 3.4")
    void claimChunkIndexIsUnique() {
        assertTrue(indexNames("claims").contains("uq_claims_chunk"),
                "claims is missing the unique (world, chunk_x, chunk_z) index");

        boolean unique = db.callSync(connection -> {
            try (ResultSet rs = connection.getMetaData()
                    .getIndexInfo(null, null, "claims", true, false)) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    if ("uq_claims_chunk".equalsIgnoreCase(name)) {
                        return true;
                    }
                }
            }
            return false;
        });
        assertTrue(unique, "uq_claims_chunk exists but is not a unique index");
    }

    @Test
    @DisplayName("the war block log replay index exists, SPEC 3.8")
    void warBlockLogReplayIndexExists() {
        assertTrue(indexNames("war_block_log").contains("idx_war_block_log_replay"),
                "war_block_log is missing the (war_id, sequence) replay index");
    }

    @Test
    @DisplayName("city names are unique case-insensitively, SPEC 5.1 precondition 5")
    void cityNamesAreUniqueCaseInsensitively() {
        assertTrue(indexNames("cities").contains("uq_cities_name"));

        db.callSync(connection -> connection.createStatement().executeUpdate(
                "INSERT INTO cities (name, display_name, tag, mayor_uuid, founded_at, treasury, "
                        + "core_world, core_chunk_x, core_chunk_z, spawn_x, spawn_y, spawn_z) "
                        + "VALUES ('Roma', 'Roma', 'ROM', '00000000-0000-0000-0000-000000000001', "
                        + "0, 0, 'world', 0, 0, 0, 64, 0)"));

        boolean rejected = false;
        try {
            db.callSync(connection -> connection.createStatement().executeUpdate(
                    "INSERT INTO cities (name, display_name, tag, mayor_uuid, founded_at, treasury, "
                            + "core_world, core_chunk_x, core_chunk_z, spawn_x, spawn_y, spawn_z) "
                            + "VALUES ('roma', 'roma', 'RM2', '00000000-0000-0000-0000-000000000002', "
                            + "0, 0, 'world', 0, 0, 0, 64, 0)"));
        } catch (StorageException e) {
            rejected = true;
        }
        assertTrue(rejected, "'roma' was accepted alongside 'Roma'; the name index is case-sensitive");
    }
}
