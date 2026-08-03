package dev.civitas.storage.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.DatabaseSettings;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.row.AuditLogRow;
import dev.civitas.storage.row.CityInviteRow;
import dev.civitas.storage.row.CityMemberRow;
import dev.civitas.storage.row.CityRankRow;
import dev.civitas.storage.row.CityRow;
import dev.civitas.storage.row.ClaimRow;
import dev.civitas.storage.row.ContestEntryRow;
import dev.civitas.storage.row.ContestRow;
import dev.civitas.storage.row.DefenseUnitRow;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.OutpostRow;
import dev.civitas.storage.row.PlayerQuestRow;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.storage.row.WarBlockLogRow;
import dev.civitas.storage.row.WarContainerLogRow;
import dev.civitas.storage.row.WarKillRow;
import dev.civitas.storage.row.WarParticipantRow;
import dev.civitas.storage.row.WarRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-trips every DAO against a real SQLite database.
 *
 * <p>Written before any service exists so that M2 onward can trust the storage layer instead
 * of debugging SQL through two layers of business logic.
 */
class DaoRoundTripTest {

    @TempDir
    Path directory;

    private DatabaseManager db;
    private DaoRegistry daos;

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void openDatabase() {
        DatabaseSettings settings = new DatabaseSettings(
                SqlDialect.SQLITE,
                "jdbc:sqlite:" + directory.resolve("dao.db").toAbsolutePath(),
                "", "", 2, 5000, "WAL", Long.MAX_VALUE, false, 6, 28);

        db = new DatabaseManager(java.util.logging.Logger.getLogger("dao-test"), settings, () -> false);
        db.open();
        daos = new DaoRegistry(db);
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    /** Inserts a city and returns its generated id. */
    private int givenCity(String name, UUID mayor, BigDecimal treasury) {
        CityRow row = new CityRow(0, name, "<gold>" + name, name.substring(0, 3), mayor,
                10_000L, treasury, "world", 0, 0, 0.5, 64.0, 0.5, 0f, 0f,
                false, "", 0L, null, 0L, false, null);
        return await(daos.cities().insert(row));
    }

    private void givenPlayer(UUID uuid, String name) {
        await(daos.players().insert(
                new PlayerRow(uuid, name, money("2000.00"), null, null,
                        1L, 2L, 0L, 0L, 0, 0L, 0L, false, 0L, 0L)));
    }

    // --- registry ---------------------------------------------------------------------

    @Test
    @DisplayName("the registry exposes one DAO per table and every table exists")
    void registryCoversEveryTable() {
        assertEquals(27, daos.all().size(), "a DAO is missing from the registry");

        for (Dao<?> dao : daos.all()) {
            assertEquals(0L, await(dao.count()), dao.table() + " should start empty");
        }
    }

    // --- players ----------------------------------------------------------------------

    @Nested
    @DisplayName("PlayerDao")
    class Players {

        @Test
        @DisplayName("inserts, reads back every field, and updates")
        void roundTrip() {
            PlayerRow original = new PlayerRow(ALICE, "Alice", money("1234.56"), null, null,
                    100L, 200L, 300L, 400L, 7, 500L, 600L, true, 0L, 0L);
            await(daos.players().insert(original));

            PlayerRow read = await(daos.players().findByUuid(ALICE)).orElseThrow();
            assertEquals(original.uuid(), read.uuid());
            assertEquals(original.lastKnownName(), read.lastKnownName());
            assertEquals(0, original.balance().compareTo(read.balance()));
            assertEquals(original.firstJoin(), read.firstJoin());
            assertEquals(original.dailyStreak(), read.dailyStreak());
            assertTrue(read.frozen());
            assertEquals(null, read.cityId());

            await(daos.players().updateBalance(ALICE, money("9999.99")));
            assertEquals(0, money("9999.99").compareTo(
                    await(daos.players().findByUuid(ALICE)).orElseThrow().balance()));
        }

        @Test
        @DisplayName("finds by name case-insensitively")
        void findsByNameIgnoringCase() {
            givenPlayer(ALICE, "Alice");

            assertTrue(await(daos.players().findByName("ALICE")).isPresent());
            assertTrue(await(daos.players().findByName("alice")).isPresent());
            assertTrue(await(daos.players().findByName("Bob")).isEmpty());
        }

        @Test
        @DisplayName("orders the wealth leaderboard by balance, highest first")
        void ordersByBalance() {
            await(daos.players().insert(
                    new PlayerRow(ALICE, "Alice", money("100.00"), null, null,
                            0L, 0L, 0L, 0L, 0, 0L, 0L, false, 0L, 0L)));
            await(daos.players().insert(
                    new PlayerRow(BOB, "Bob", money("5000.00"), null, null,
                            0L, 0L, 0L, 0L, 0, 0L, 0L, false, 0L, 0L)));

            List<PlayerRow> top = await(daos.players().findTopByBalance(10));

            assertEquals(List.of("Bob", "Alice"), top.stream().map(PlayerRow::lastKnownName).toList());
            assertEquals(0, money("5100.00").compareTo(await(daos.players().totalCirculation())));
        }
    }

    // --- cities, ranks, members -------------------------------------------------------

    @Nested
    @DisplayName("City tables")
    class Cities {

        @Test
        @DisplayName("insert returns a generated id and every field round-trips")
        void cityRoundTrip() {
            int id = givenCity("Roma", ALICE, money("50000.00"));
            assertTrue(id > 0);

            CityRow read = await(daos.cities().findById(id)).orElseThrow();
            assertEquals("Roma", read.name());
            assertEquals(ALICE, read.mayorUuid());
            assertEquals(0, money("50000.00").compareTo(read.treasury()));
            assertEquals(null, read.deletedAt());

            await(daos.cities().updateTreasury(id, money("125.75")));
            assertEquals(0, money("125.75").compareTo(
                    await(daos.cities().findById(id)).orElseThrow().treasury()));
        }

        @Test
        @DisplayName("soft delete hides a city from the active list but keeps the row")
        void softDeleteKeepsTheRow() {
            int id = givenCity("Roma", ALICE, money("0.00"));

            await(daos.cities().softDelete(id, 99_000L));

            assertTrue(await(daos.cities().findAllActive()).isEmpty());
            assertEquals(1L, await(daos.cities().count()));
            assertEquals(1, await(daos.cities().findDeletedSince(0L)).size());

            await(daos.cities().restore(id));
            assertEquals(1, await(daos.cities().findAllActive()).size());
        }

        @Test
        @DisplayName("ranks order by weight and expose the default joiner rank")
        void ranksRoundTrip() {
            int cityId = givenCity("Roma", ALICE, money("0.00"));

            int mayorRank = await(daos.cityRanks().insert(
                    new CityRankRow(0, cityId, "Mayor", 100, Long.MAX_VALUE, false)));
            await(daos.cityRanks().insert(
                    new CityRankRow(0, cityId, "Recruit", 20, 0b1010L, true)));

            List<CityRankRow> ranks = await(daos.cityRanks().findByCity(cityId));
            assertEquals(List.of("Mayor", "Recruit"), ranks.stream().map(CityRankRow::name).toList());

            assertEquals("Recruit", await(daos.cityRanks().findDefault(cityId)).orElseThrow().name());

            await(daos.cityRanks().updatePermissions(mayorRank, 0b111L));
            assertEquals(0b111L, await(daos.cityRanks().findById(mayorRank)).orElseThrow().permissions());
        }

        @Test
        @DisplayName("member contribution accumulates rather than being overwritten")
        void contributionAccumulates() {
            int cityId = givenCity("Roma", ALICE, money("0.00"));
            int rankId = await(daos.cityRanks().insert(
                    new CityRankRow(0, cityId, "Citizen", 40, 0L, true)));
            await(daos.cityMembers().insert(
                    new CityMemberRow(ALICE, cityId, rankId, 1_000L, money("0.00"))));

            await(daos.cityMembers().addContribution(ALICE, money("1500.25")));
            await(daos.cityMembers().addContribution(ALICE, money("2499.75")));

            CityMemberRow member = await(daos.cityMembers().findByUuid(ALICE)).orElseThrow();
            assertEquals(0, money("4000.00").compareTo(member.contributedTotal()));
            assertEquals(1, await(daos.cityMembers().countByCity(cityId)));
        }

        @Test
        @DisplayName("invites expire, so a stale row can never be accepted")
        void invitesExpire() {
            int cityId = givenCity("Roma", ALICE, money("0.00"));
            await(daos.cityInvites().upsert(new CityInviteRow(cityId, BOB, ALICE, 5_000L)));

            assertEquals(1, await(daos.cityInvites().findPendingFor(BOB, 4_999L)).size());
            assertTrue(await(daos.cityInvites().findPendingFor(BOB, 5_001L)).isEmpty());

            assertEquals(1, await(daos.cityInvites().deleteExpired(6_000L)));
            assertEquals(0L, await(daos.cityInvites().count()));
        }

        @Test
        @DisplayName("re-inviting refreshes the expiry instead of failing on the primary key")
        void reInviteRefreshesExpiry() {
            int cityId = givenCity("Roma", ALICE, money("0.00"));

            await(daos.cityInvites().upsert(new CityInviteRow(cityId, BOB, ALICE, 5_000L)));
            await(daos.cityInvites().upsert(new CityInviteRow(cityId, BOB, ALICE, 9_000L)));

            assertEquals(1L, await(daos.cityInvites().count()));
            assertEquals(9_000L,
                    await(daos.cityInvites().findPending(cityId, BOB, 0L)).orElseThrow().expiresAt());
        }
    }

    // --- claims and outposts ----------------------------------------------------------

    @Nested
    @DisplayName("ClaimDao")
    class Claims {

        @Test
        @DisplayName("a claim round-trips and is found by its chunk coordinates")
        void claimRoundTrip() {
            int cityId = givenCity("Roma", ALICE, money("0.00"));
            long id = await(daos.claims().insert(new ClaimRow(0, cityId, "world", 3, -7,
                    1_000L, ALICE, money("500.00"), "CORE", null)));
            assertTrue(id > 0);

            ClaimRow read = await(daos.claims().findAt("world", 3, -7)).orElseThrow();
            assertEquals(cityId, read.cityId());
            assertEquals("CORE", read.type());
            assertEquals(0, money("500.00").compareTo(read.costPaid()));
            assertEquals(null, read.outpostId());

            assertEquals(1, await(daos.claims().countByCity(cityId)));
            assertEquals(1, await(daos.claims().findAll()).size());
        }

        @Test
        @DisplayName("the unique chunk index rejects a second city claiming the same chunk")
        void duplicateChunkIsRejected() {
            int roma = givenCity("Roma", ALICE, money("0.00"));
            int ostia = givenCity("Ostia", BOB, money("0.00"));

            await(daos.claims().insert(new ClaimRow(0, roma, "world", 10, 10,
                    1_000L, ALICE, money("500.00"), "CORE", null)));

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> daos.claims().insert(new ClaimRow(0, ostia, "world", 10, 10,
                            1_000L, BOB, money("500.00"), "CORE", null)).get(15, TimeUnit.SECONDS));

            assertNotNull(thrown.getCause());
            assertEquals(1L, await(daos.claims().count()),
                    "SPEC 17.2 case 15: only one city may hold a chunk");
        }

        @Test
        @DisplayName("the same chunk coordinates in a different world are a different claim")
        void worldIsPartOfTheKey() {
            int cityId = givenCity("Roma", ALICE, money("0.00"));

            await(daos.claims().insert(new ClaimRow(0, cityId, "world", 0, 0,
                    1L, ALICE, money("0.00"), "CORE", null)));
            await(daos.claims().insert(new ClaimRow(0, cityId, "world_nether", 0, 0,
                    1L, ALICE, money("0.00"), "NORMAL", null)));

            assertEquals(2L, await(daos.claims().count()));
        }

        @Test
        @DisplayName("an outpost claim links to its outpost and converts back to a normal claim")
        void outpostConversion() {
            int cityId = givenCity("Roma", ALICE, money("0.00"));
            int outpostId = await(daos.outposts().insert(
                    new OutpostRow(0, cityId, "Mine", 100.5, 64.0, -30.5, 0f, 0f, 1_000L)));

            long claimId = await(daos.claims().insert(new ClaimRow(0, cityId, "world", 40, 40,
                    1_000L, ALICE, money("25000.00"), "OUTPOST", outpostId)));

            assertEquals(1, await(daos.claims().findByOutpost(outpostId)).size());

            // SPEC 7.4: an outpost swallowed by the city body becomes a normal claim.
            await(daos.claims().updateType(claimId, "NORMAL", null));

            ClaimRow read = await(daos.claims().findAt("world", 40, 40)).orElseThrow();
            assertEquals("NORMAL", read.type());
            assertEquals(null, read.outpostId());
        }

        @Test
        @DisplayName("outposts are found by name within their city, case-insensitively")
        void outpostsByName() {
            int cityId = givenCity("Roma", ALICE, money("0.00"));
            await(daos.outposts().insert(
                    new OutpostRow(0, cityId, "Quarry", 1.0, 64.0, 2.0, 0f, 0f, 1_000L)));

            assertTrue(await(daos.outposts().findByName(cityId, "quarry")).isPresent());
            assertTrue(await(daos.outposts().findByName(cityId, "Nowhere")).isEmpty());
        }
    }

    // --- ledger -----------------------------------------------------------------------

    @Nested
    @DisplayName("LedgerDao")
    class Ledger {

        @Test
        @DisplayName("entries round-trip with signed amounts and nullable actors")
        void ledgerRoundTrip() {
            long id = await(daos.ledger().insert(new LedgerRow(0, 1_000L, "PLAYER_PAY",
                    ALICE, BOB, null, money("-250.50"), money("749.50"), "{\"note\":\"test\"}")));

            LedgerRow read = await(daos.ledger().findById(id)).orElseThrow();
            assertEquals("PLAYER_PAY", read.type());
            assertEquals(ALICE, read.actorUuid());
            assertEquals(BOB, read.targetUuid());
            assertEquals(0, money("-250.50").compareTo(read.amount()));
            assertEquals("{\"note\":\"test\"}", read.metadata());
        }

        @Test
        @DisplayName("a system transaction has no actor")
        void systemTransactionHasNoActor() {
            long id = await(daos.ledger().insert(new LedgerRow(0, 1_000L, "UPKEEP_CHARGE",
                    null, null, 1, money("-4000.00"), money("0.00"), null)));

            LedgerRow read = await(daos.ledger().findById(id)).orElseThrow();
            assertEquals(null, read.actorUuid());
            assertEquals(null, read.targetUuid());
            assertEquals(1, read.cityId());
        }

        @Test
        @DisplayName("queries filter by player, city, type and time window")
        void ledgerQueries() {
            await(daos.ledger().insertAll(List.of(
                    new LedgerRow(0, 1_000L, "PLAYER_PAY", ALICE, BOB, null,
                            money("-10.00"), money("90.00"), null),
                    new LedgerRow(0, 2_000L, "MARKET_SELL", BOB, null, 7,
                            money("40.00"), money("140.00"), null),
                    new LedgerRow(0, 3_000L, "MARKET_SELL", ALICE, null, 7,
                            money("15.00"), money("105.00"), null))));

            assertEquals(2, await(daos.ledger().findByPlayer(ALICE, 0L, 50)).size());
            assertEquals(2, await(daos.ledger().findByCity(7, 0L, 50)).size());
            assertEquals(2, await(daos.ledger().findByType("MARKET_SELL", 0L, 50)).size());
            assertEquals(1, await(daos.ledger().findByType("MARKET_SELL", 2_500L, 50)).size());
        }

        @Test
        @DisplayName("results come back newest first")
        void newestFirst() {
            await(daos.ledger().insertAll(List.of(
                    new LedgerRow(0, 1_000L, "MARKET_SELL", ALICE, null, null,
                            money("1.00"), money("1.00"), null),
                    new LedgerRow(0, 5_000L, "MARKET_SELL", ALICE, null, null,
                            money("2.00"), money("3.00"), null))));

            List<LedgerRow> rows = await(daos.ledger().findByPlayer(ALICE, 0L, 50));
            assertEquals(5_000L, rows.get(0).timestamp());
        }
    }

    // --- war --------------------------------------------------------------------------

    @Nested
    @DisplayName("War tables")
    class Wars {

        private int givenWar() {
            int attacker = givenCity("Roma", ALICE, money("100000.00"));
            int defender = givenCity("Ostia", BOB, money("100000.00"));
            return await(daos.wars().insert(new WarRow(0, attacker, defender,
                    1_000L, 2_000L, 3_000L, "PREP", 0, 0, null, money("50000.00"), null, null)));
        }

        @Test
        @DisplayName("a war round-trips and its state and checkpoint update independently")
        void warRoundTrip() {
            int warId = givenWar();

            WarRow read = await(daos.wars().findById(warId)).orElseThrow();
            assertEquals("PREP", read.state());
            assertEquals(0, money("50000.00").compareTo(read.wager()));
            assertEquals(null, read.rollbackCheckpointSequence());

            await(daos.wars().updateState(warId, "ROLLING_BACK"));
            await(daos.wars().updateRollbackCheckpoint(warId, 42_000L));

            WarRow updated = await(daos.wars().findById(warId)).orElseThrow();
            assertEquals("ROLLING_BACK", updated.state());
            assertEquals(42_000L, updated.rollbackCheckpointSequence());
        }

        @Test
        @DisplayName("the crash-recovery sweep finds wars by state")
        void findsByState() {
            int warId = givenWar();
            await(daos.wars().updateState(warId, "ACTIVE"));

            assertEquals(1, await(daos.wars().findByStates(List.of("ACTIVE", "ROLLING_BACK"))).size());
            assertTrue(await(daos.wars().findByStates(List.of("RESOLVED"))).isEmpty());
            assertTrue(await(daos.wars().findByStates(List.of())).isEmpty());
        }

        @Test
        @DisplayName("block log entries batch-insert and replay in reverse sequence order")
        void blockLogReplayOrder() {
            int warId = givenWar();

            await(daos.warBlockLog().insertBatch(List.of(
                    blockLog(warId, 1, "minecraft:stone"),
                    blockLog(warId, 2, "minecraft:dirt"),
                    blockLog(warId, 3, "minecraft:oak_log"))));

            assertEquals(3L, await(daos.warBlockLog().countByWar(warId)));
            assertEquals(3L, await(daos.warBlockLog().maxSequence(warId)));

            List<WarBlockLogRow> page =
                    await(daos.warBlockLog().findForReplay(warId, Long.MAX_VALUE, 2));
            assertEquals(List.of(3L, 2L), page.stream().map(WarBlockLogRow::sequence).toList());

            List<WarBlockLogRow> next = await(daos.warBlockLog().findForReplay(warId, 2L, 2));
            assertEquals(List.of(1L), next.stream().map(WarBlockLogRow::sequence).toList());
        }

        @Test
        @DisplayName("tile-entity NBT survives as bytes, not as text")
        void nbtRoundTrip() {
            int warId = givenWar();
            byte[] nbt = {0, 10, (byte) 0xFF, 42, -1, 0};

            await(daos.warBlockLog().insertBatch(List.of(new WarBlockLogRow(0, warId, 1L,
                    "world", 1, 64, 2, "minecraft:chest[facing=north]", "minecraft:air",
                    nbt, ALICE, 1_000L))));

            WarBlockLogRow read =
                    await(daos.warBlockLog().findForReplay(warId, Long.MAX_VALUE, 1)).get(0);
            org.junit.jupiter.api.Assertions.assertArrayEquals(nbt, read.oldNbt());
            assertEquals("minecraft:chest[facing=north]", read.oldBlockData());
        }

        @Test
        @DisplayName("an unattributed block change logs with a null actor")
        void nullActorIsAllowed() {
            int warId = givenWar();
            await(daos.warBlockLog().insertBatch(List.of(new WarBlockLogRow(0, warId, 1L,
                    "world", 0, 0, 0, "minecraft:fire", "minecraft:air", null, null, 1_000L))));

            assertEquals(null,
                    await(daos.warBlockLog().findForReplay(warId, Long.MAX_VALUE, 1)).get(0).actorUuid());
        }

        @Test
        @DisplayName("participants, kills and container loot all round-trip")
        void warSideTables() {
            int warId = givenWar();
            WarRow war = await(daos.wars().findById(warId)).orElseThrow();

            await(daos.warParticipants().insert(
                    new WarParticipantRow(warId, war.attackerCityId(), "ATTACKER", false)));
            await(daos.warParticipants().insert(
                    new WarParticipantRow(warId, war.defenderCityId(), "DEFENDER", false)));
            assertEquals(2, await(daos.warParticipants().findByWar(warId)).size());

            await(daos.warKills().insert(new WarKillRow(0, warId, ALICE, BOB, 1_500L, "world:10,64,20")));
            await(daos.warKills().insert(new WarKillRow(0, warId, ALICE, BOB, 2_500L, "world:11,64,21")));
            assertEquals(2, await(daos.warKills().findByKiller(warId, ALICE)).size());
            assertEquals(2_500L, await(daos.warKills().findRecent(warId, 1)).get(0).timestamp());

            await(daos.warContainerLog().insertBatch(List.of(
                    new WarContainerLogRow(0, warId, "world", 5, 64, 5, ALICE, "DIAMOND", 12, 3_000L))));
            assertEquals(12, await(daos.warContainerLog().findByWar(warId)).get(0).quantity());
        }

        private WarBlockLogRow blockLog(int warId, long sequence, String oldData) {
            return new WarBlockLogRow(0, warId, sequence, "world", (int) sequence, 64, 0,
                    oldData, "minecraft:air", null, ALICE, 1_000L + sequence);
        }
    }

    // --- diplomacy --------------------------------------------------------------------

    @Nested
    @DisplayName("Diplomacy tables")
    class Diplomacy {

        @Test
        @DisplayName("an alliance is one row whichever order the cities are given in")
        void allianceIsSymmetric() {
            int roma = givenCity("Roma", ALICE, money("0.00"));
            int ostia = givenCity("Ostia", BOB, money("0.00"));

            await(daos.alliances().insert(ostia, roma, "ACTIVE", 1_000L));

            assertTrue(await(daos.alliances().find(roma, ostia)).isPresent());
            assertTrue(await(daos.alliances().find(ostia, roma)).isPresent());
            assertEquals(1L, await(daos.alliances().count()));
            assertEquals(1, await(daos.alliances().findByCity(roma)).size());

            await(daos.alliances().delete(roma, ostia));
            assertEquals(0L, await(daos.alliances().count()));
        }

        @Test
        @DisplayName("a truce can be extended but never shortened, SPEC 14.3")
        void truceCannotBeShortened() {
            int roma = givenCity("Roma", ALICE, money("0.00"));
            int ostia = givenCity("Ostia", BOB, money("0.00"));

            await(daos.truces().upsert(roma, ostia, 10_000L));
            await(daos.truces().upsert(ostia, roma, 20_000L));
            assertEquals(20_000L, await(daos.truces().findActive(roma, ostia, 0L)).orElseThrow().expiresAt());

            await(daos.truces().upsert(roma, ostia, 5_000L));
            assertEquals(20_000L,
                    await(daos.truces().findActive(roma, ostia, 0L)).orElseThrow().expiresAt(),
                    "a truce must not be cancellable early by re-offering a shorter one");

            assertTrue(await(daos.truces().findActive(roma, ostia, 25_000L)).isEmpty());
        }
    }

    // --- economy, progression, defense, audit -----------------------------------------

    @Nested
    @DisplayName("Remaining tables")
    class Remaining {

        @Test
        @DisplayName("market stock moves by delta and survives a definition refresh")
        void marketStock() {
            // A new item starts at its target stock, so its first price is exactly base_price.
            await(daos.marketStock().upsertDefinition("WHEAT", 20_000, money("3.00")));
            assertEquals(20_000, await(daos.marketStock().find("WHEAT")).orElseThrow().currentStock());

            await(daos.marketStock().addStock("WHEAT", 500));
            await(daos.marketStock().addStock("WHEAT", -200));
            assertEquals(20_300, await(daos.marketStock().find("WHEAT")).orElseThrow().currentStock());

            // A config reload must not hand players a price reset.
            await(daos.marketStock().upsertDefinition("WHEAT", 25_000, money("4.00")));
            var row = await(daos.marketStock().find("WHEAT")).orElseThrow();
            assertEquals(20_300, row.currentStock(), "current_stock must survive a definition refresh");
            assertEquals(25_000, row.targetStock());
            assertEquals(0, money("4.00").compareTo(row.basePrice()));
        }

        @Test
        @DisplayName("market stock may go negative, SPEC 17.3 case 28")
        void marketStockMayGoNegative() {
            await(daos.marketStock().upsertDefinition("DIAMOND", 1_500, money("400.00")));
            await(daos.marketStock().addStock("DIAMOND", -2_000));

            assertEquals(-500, await(daos.marketStock().find("DIAMOND")).orElseThrow().currentStock(),
                    "the market stays an infinite seller; the price clamp holds instead");
        }

        @Test
        @DisplayName("quest progress accumulates and completion is recorded once")
        void quests() {
            long questRow = await(daos.playerQuests().insert(
                    new PlayerQuestRow(0, ALICE, "harvest_wheat", 0, 1_000L, null,
                            256L, new BigDecimal("450.00"))));

            await(daos.playerQuests().addProgress(questRow, 100));
            await(daos.playerQuests().addProgress(questRow, 156));

            PlayerQuestRow read = await(daos.playerQuests().findForPlayer(ALICE, 0L)).get(0);
            assertEquals(256, read.progress());
            assertEquals(null, read.completedAt());

            assertEquals(1, await(daos.playerQuests().markCompleted(questRow, 9_000L)));
            assertEquals(0, await(daos.playerQuests().markCompleted(questRow, 9_999L)),
                    "a quest must not be completable twice");
            assertEquals(9_000L,
                    await(daos.playerQuests().findForPlayer(ALICE, 0L)).get(0).completedAt());
        }

        @Test
        @DisplayName("a city may enter a contest only once")
        void contestEntryIsUniquePerCity() {
            int cityId = givenCity("Roma", ALICE, money("0.00"));
            int contestId = await(daos.contests().insert(
                    new ContestRow(0, "Medieval Market", 1_000L, 20_000L, "BUILDING")));

            int entryId = await(daos.contestEntries().insert(
                    new ContestEntryRow(0, contestId, cityId, "world:0,0,0:16,16,16", null, 0)));

            assertThrows(ExecutionException.class, () -> daos.contestEntries().insert(
                    new ContestEntryRow(0, contestId, cityId, "world:1,1,1:2,2,2", null, 0))
                    .get(15, TimeUnit.SECONDS));

            assertTrue(await(daos.contestEntries().findSubmitted(contestId)).isEmpty());
            await(daos.contestEntries().markSubmitted(entryId, 11_000L));
            await(daos.contestEntries().updateScore(entryId, 8.5));

            ContestEntryRow read = await(daos.contestEntries().findSubmitted(contestId)).get(0);
            assertEquals(11_000L, read.submittedAt());
            assertEquals(8.5, read.score(), 1e-9);

            assertTrue(await(daos.contests().findCurrent(5_000L)).isPresent());
            assertTrue(await(daos.contests().findCurrent(50_000L)).isEmpty());
        }

        @Test
        @DisplayName("a vote replaces the voter's earlier vote on the same entry")
        void votesUpsert() {
            int cityId = givenCity("Roma", ALICE, money("0.00"));
            int contestId = await(daos.contests().insert(
                    new ContestRow(0, "Harbour Town", 0L, 100L, "VOTING")));
            int entryId = await(daos.contestEntries().insert(
                    new ContestEntryRow(0, contestId, cityId, "region", 1L, 0)));

            await(daos.contestVotes().upsert(contestId, BOB, entryId, 7.0));
            await(daos.contestVotes().upsert(contestId, BOB, entryId, 9.0));

            assertEquals(1L, await(daos.contestVotes().count()));
            assertEquals(9.0, await(daos.contestVotes().findByEntry(entryId)).get(0).score(), 1e-9);

            await(daos.contestVotes().deleteByVoter(contestId, BOB));
            assertEquals(0L, await(daos.contestVotes().count()));
        }

        @Test
        @DisplayName("upgrade levels insert on first purchase and update afterwards")
        void upgrades() {
            int cityId = givenCity("Roma", ALICE, money("0.00"));

            assertEquals(0, await(daos.cityUpgrades().findLevel(cityId, "vault")),
                    "an unpurchased upgrade reads as level 0 with no row");

            await(daos.cityUpgrades().setLevel(cityId, "vault", 1));
            await(daos.cityUpgrades().setLevel(cityId, "vault", 3));

            assertEquals(3, await(daos.cityUpgrades().findLevel(cityId, "vault")));
            assertEquals(1, await(daos.cityUpgrades().findByCity(cityId)).size());
        }

        @Test
        @DisplayName("defense units deactivate without being deleted, and upkeep sums correctly")
        void defenseUnits() {
            int cityId = givenCity("Roma", ALICE, money("0.00"));

            int guard = await(daos.defenseUnits().insert(new DefenseUnitRow(0, cityId, "city-guard",
                    "world", 10.5, 64.0, 20.5, money("900.00"), true)));
            await(daos.defenseUnits().insert(new DefenseUnitRow(0, cityId, "archer",
                    "world", 12.5, 64.0, 22.5, money("700.00"), true)));

            assertEquals(0, money("1600.00").compareTo(await(daos.defenseUnits().totalUpkeep(cityId))));

            // SPEC 12.3: unpaid upkeep despawns a unit but keeps its row.
            await(daos.defenseUnits().setActive(guard, false));
            assertEquals(1, await(daos.defenseUnits().findActiveByCity(cityId)).size());
            assertEquals(2, await(daos.defenseUnits().findByCity(cityId)).size());
            assertEquals(0, money("700.00").compareTo(await(daos.defenseUnits().totalUpkeep(cityId))));
        }

        @Test
        @DisplayName("audit entries round-trip and filter by actor and target")
        void auditLog() {
            await(daos.auditLog().insert(new AuditLogRow(0, 1_000L, ALICE,
                    "CITY_FREEZE", "Roma", "suspected duping", null)));
            await(daos.auditLog().insert(new AuditLogRow(0, 2_000L, null,
                    "SYSTEM_PURGE", "Ostia", null, "{\"reason\":\"inactive\"}")));

            assertEquals(2, await(daos.auditLog().findRecent(0L, 50)).size());
            assertEquals(1, await(daos.auditLog().findByActor(ALICE, 0L, 50)).size());
            assertEquals("suspected duping",
                    await(daos.auditLog().findByTarget("Roma", 0L, 50)).get(0).reason());
            assertEquals(null, await(daos.auditLog().findByTarget("Ostia", 0L, 50)).get(0).actorUuid());
        }
    }

    // --- transactions across DAOs -----------------------------------------------------

    @Test
    @DisplayName("a city, its ranks, its mayor and its core claim commit as one unit")
    void multiTableTransactionCommits() {
        givenPlayer(ALICE, "Alice");

        int cityId = await(daos.cities().transaction(connection -> {
            int id = daos.cities().insert(connection, new CityRow(0, "Roma", "Roma", "ROM", ALICE,
                    1_000L, money("0.00"), "world", 0, 0, 0.5, 64.0, 0.5, 0f, 0f,
                    false, "", 0L, null, 0L, false, null));
            int rankId = daos.cityRanks().insert(connection,
                    new CityRankRow(0, id, "Mayor", 100, Long.MAX_VALUE, false));
            daos.cityMembers().insert(connection,
                    new CityMemberRow(ALICE, id, rankId, 1_000L, money("0.00")));
            daos.claims().insert(connection, new ClaimRow(0, id, "world", 0, 0,
                    1_000L, ALICE, money("0.00"), "CORE", null));
            return id;
        }));

        assertEquals(1L, await(daos.cities().count()));
        assertEquals(1L, await(daos.cityRanks().count()));
        assertEquals(1L, await(daos.cityMembers().count()));
        assertEquals(1L, await(daos.claims().count()));
        assertEquals(cityId, await(daos.claims().findAt("world", 0, 0)).orElseThrow().cityId());
    }

    @Test
    @DisplayName("if any step of a city creation fails, none of it is written")
    void multiTableTransactionRollsBack() {
        givenPlayer(ALICE, "Alice");

        assertThrows(ExecutionException.class, () -> daos.cities().transaction(connection -> {
            int id = daos.cities().insert(connection, new CityRow(0, "Roma", "Roma", "ROM", ALICE,
                    1_000L, money("0.00"), "world", 0, 0, 0.5, 64.0, 0.5, 0f, 0f,
                    false, "", 0L, null, 0L, false, null));
            daos.cityRanks().insert(connection,
                    new CityRankRow(0, id, "Mayor", 100, Long.MAX_VALUE, false));
            throw new IllegalStateException("simulated failure part-way through");
        }).get(15, TimeUnit.SECONDS));

        assertEquals(0L, await(daos.cities().count()), "a half-created city was left behind");
        assertEquals(0L, await(daos.cityRanks().count()));
    }

    @Test
    @DisplayName("deleting a city cascades to the rows that belong to it")
    void deleteCascades() {
        int cityId = givenCity("Roma", ALICE, money("0.00"));
        int rankId = await(daos.cityRanks().insert(new CityRankRow(0, cityId, "Mayor", 100, 0L, true)));
        await(daos.cityMembers().insert(new CityMemberRow(ALICE, cityId, rankId, 1L, money("0.00"))));
        await(daos.claims().insert(new ClaimRow(0, cityId, "world", 0, 0,
                1L, ALICE, money("0.00"), "CORE", null)));

        await(daos.cities().hardDelete(cityId));

        assertEquals(0L, await(daos.cityRanks().count()));
        assertEquals(0L, await(daos.cityMembers().count()));
        assertEquals(0L, await(daos.claims().count()));
    }

    @Test
    @DisplayName("foreign keys are enforced, so an orphan row cannot be written")
    void foreignKeysAreEnforced() {
        assertThrows(ExecutionException.class, () -> daos.claims().insert(
                new ClaimRow(0, 9999, "world", 0, 0, 1L, ALICE, money("0.00"), "CORE", null))
                .get(15, TimeUnit.SECONDS));

        assertEquals(0L, await(daos.claims().count()));
    }

    @Test
    @DisplayName("an unbindable parameter type is rejected rather than silently mangled")
    void unsupportedParameterTypeIsRejected() {
        Optional<PlayerRow> ignored = Optional.empty();
        assertFalse(ignored.isPresent());

        assertThrows(ExecutionException.class, () -> db.call(connection -> {
            try (var statement = connection.prepareStatement("SELECT ? AS x")) {
                new PlayerDao(db).bind(statement, new Object());
                return statement.executeQuery();
            }
        }).get(15, TimeUnit.SECONDS));
    }
}
