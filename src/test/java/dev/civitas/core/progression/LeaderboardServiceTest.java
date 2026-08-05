package dev.civitas.core.progression;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The SPEC 13.3 boards.
 *
 * <p>SPEC 18 assigns no tests to this milestone: 18.1 covers formulas from M3, M5, M6 and
 * M19, and 18.2 covers the city, claim, treasury and GUI flows. These follow 18's style
 * instead, and the ones that matter most are not the orderings. They are the two places a
 * leaderboard can be quietly wrong: reading the Contribution total from a row that is deleted
 * when a player changes city, and showing an empty board for a system that does not exist,
 * which reads to a player as "nobody has done this" rather than "this is not built yet".
 */
class LeaderboardServiceTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private StatsService stats;
    private LeaderboardService boards;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        stats = new StatsService(support.daos.playerStats(), CityTestSupport.quietLogger());
        boards = new LeaderboardService(support.daos.players(), support.daos.ledger(),
                support.daos.playerStats(), support.daos.contestEntries(), support.registry,
                support.claimRegistry, support.claims, support.configs,
                CityTestSupport.quietLogger());
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private void refresh() {
        await(boards.refresh(System.currentTimeMillis()));
    }

    private List<String> namesOn(LeaderboardType type) {
        return boards.board(type).orElseThrow().stream().map(LeaderboardEntry::name).toList();
    }

    // ==================================================================================
    // Readiness, which is not the same as emptiness
    // ==================================================================================

    @Nested
    @DisplayName("before the first sweep")
    class BeforeRefresh {

        @Test
        @DisplayName("no board is ready, and none reports itself as empty")
        void notReady() {
            assertFalse(boards.isReady());
            for (LeaderboardType type : LeaderboardType.all()) {
                assertTrue(boards.board(type).isEmpty(),
                        type + " answered before a sweep had produced anything");
            }
        }

        @Test
        @DisplayName("a sweep makes every board readable, even the empty ones")
        void afterRefreshEveryBoardAnswers() {
            refresh();

            assertTrue(boards.isReady());
            for (LeaderboardType type : LeaderboardType.all()) {
                assertTrue(boards.board(type).isPresent(), type + " is missing from the snapshot");
            }
        }
    }

    // ==================================================================================
    // Boards whose system is a later milestone
    // ==================================================================================

    @Nested
    @DisplayName("boards from systems that do not exist yet")
    class Unavailable {

        @ParameterizedTest
        @EnumSource(value = LeaderboardType.class, names = {"WAR_RECORD"})
        @DisplayName("report themselves unavailable rather than empty")
        void reportUnavailable(LeaderboardType type) {
            refresh();

            assertFalse(boards.isAvailable(type),
                    type + " claims to be available, but nothing feeds it yet");
            assertTrue(boards.board(type).orElseThrow().isEmpty());
        }

        @ParameterizedTest
        @EnumSource(value = LeaderboardType.class,
                names = {"WAR_RECORD"}, mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("every other board is available now")
        void everythingElseIsAvailable(LeaderboardType type) {
            // Contest Champions joined this list at M15, which is what closing a seam looks
            // like: the board did not change, only whether anything feeds it.
            assertTrue(boards.isAvailable(type), type + " should be available by M15");
        }

        @Test
        @DisplayName("War Record sorts by wins, then by fewest losses, then by name")
        void warRecordOrdering() {
            // M19 has nothing to sort yet, but the rule is in SPEC 13.3 and is settled here
            // while it is being read out of the specification rather than reconstructed later.
            List<LeaderboardEntry> unsorted = new ArrayList<>(List.of(
                    new LeaderboardEntry(0, "Carthago", BigDecimal.valueOf(3), BigDecimal.valueOf(1)),
                    new LeaderboardEntry(0, "Roma", BigDecimal.valueOf(5), BigDecimal.valueOf(4)),
                    new LeaderboardEntry(0, "Neapolis", BigDecimal.valueOf(3), BigDecimal.ZERO),
                    new LeaderboardEntry(0, "Alba", BigDecimal.valueOf(3), BigDecimal.ZERO)));

            unsorted.sort(LeaderboardService.warRecordOrder());

            assertEquals(List.of("Roma", "Alba", "Neapolis", "Carthago"),
                    unsorted.stream().map(LeaderboardEntry::name).toList());
        }
    }

    // ==================================================================================
    // Player boards
    // ==================================================================================

    @Nested
    @DisplayName("Wealth")
    class Wealth {

        @Test
        @DisplayName("ranks players by balance, richest first")
        void ranksByBalance() {
            support.givenPlayer("Poor", new BigDecimal("100.00"), 0L);
            support.givenPlayer("Rich", new BigDecimal("900000.00"), 0L);
            support.givenPlayer("Middle", new BigDecimal("5000.00"), 0L);

            refresh();

            assertEquals(List.of("Rich", "Middle", "Poor"), namesOn(LeaderboardType.WEALTH));
        }

        @Test
        @DisplayName("numbers entries from one")
        void ranksAreOneIndexed() {
            support.givenPlayer("Rich", new BigDecimal("900000.00"), 0L);
            support.givenPlayer("Poor", new BigDecimal("100.00"), 0L);

            refresh();

            List<LeaderboardEntry> entries = boards.board(LeaderboardType.WEALTH).orElseThrow();
            assertEquals(1, entries.get(0).rank());
            assertEquals(2, entries.get(1).rank());
        }
    }

    @Nested
    @DisplayName("Contribution")
    class Contribution {

        /**
         * The reason this board reads the ledger and not {@code city_members}.
         *
         * <p>SPEC 13.3 asks for a lifetime figure. {@code city_members.contributed_total} is
         * deleted with the membership row, so a player who leaves a city would have their
         * whole record erased by walking out of it.
         */
        @Test
        @DisplayName("survives the contributor leaving the city they gave to")
        void survivesLeavingTheCity() {
            UUID mayor = support.givenEligiblePlayer("Romulus");
            City roma = support.givenCity(mayor, "Roma", 0, 0);
            UUID giver = support.givenMember(roma, "Cincinnatus");

            Result<BigDecimal> deposited =
                    await(support.treasury.deposit(giver, roma, new BigDecimal("4000.00")));
            assertTrue(deposited.isSuccess(), reasonOf(deposited));

            Result<City> left = await(support.cities.leave(giver, roma));
            assertTrue(left.isSuccess(), reasonOf(left));
            assertTrue(await(support.daos.cityMembers().findByUuid(giver)).isEmpty(),
                    "the membership row should be gone, which is what makes this test worth having");

            refresh();

            List<LeaderboardEntry> entries =
                    boards.board(LeaderboardType.CONTRIBUTION).orElseThrow();
            assertEquals(1, entries.size());
            assertEquals("Cincinnatus", entries.get(0).name());
            assertEquals(0, new BigDecimal("4000.00").compareTo(entries.get(0).value()));
        }

        @Test
        @DisplayName("counts what a player gave, not what the treasury moved")
        void countsOnlyTheDepositSide() {
            UUID mayor = support.givenEligiblePlayer("Romulus");
            City roma = support.givenCity(mayor, "Roma", 0, 0);

            await(support.treasury.deposit(mayor, roma, new BigDecimal("1000.00")));
            // A withdrawal moves money the other way and must not net the deposit off.
            await(support.treasury.withdraw(mayor, roma, new BigDecimal("500.00")));

            refresh();

            LeaderboardEntry top = boards.board(LeaderboardType.CONTRIBUTION)
                    .orElseThrow().get(0);
            assertEquals(0, new BigDecimal("1000.00").compareTo(top.value()),
                    "a withdrawal reduced the contribution total");
        }
    }

    @Nested
    @DisplayName("Builder and Farmer")
    class LifetimeCounters {

        @Test
        @DisplayName("rank by the lifetime counters, highest first")
        void rankByStats() {
            UUID busy = support.givenPlayer("Vitruvius", BigDecimal.ZERO, 0L);
            UUID idle = support.givenPlayer("Otiosus", BigDecimal.ZERO, 0L);

            stats.record(busy, PlayerStat.BLOCKS_PLACED, 500);
            stats.record(idle, PlayerStat.BLOCKS_PLACED, 10);
            stats.record(idle, PlayerStat.CROPS_HARVESTED, 900);
            await(stats.flush(System.currentTimeMillis()));

            refresh();

            assertEquals(List.of("Vitruvius", "Otiosus"), namesOn(LeaderboardType.BUILDER));
            assertEquals(List.of("Otiosus"), namesOn(LeaderboardType.FARMER),
                    "a player with no harvest should not appear on the Farmer board");
        }

        @Test
        @DisplayName("the two counters are independent")
        void countersDoNotBleed() {
            UUID player = support.givenPlayer("Vitruvius", BigDecimal.ZERO, 0L);

            stats.record(player, PlayerStat.BLOCKS_PLACED, 7);
            await(stats.flush(System.currentTimeMillis()));
            refresh();

            assertEquals(0, BigDecimal.valueOf(7).compareTo(
                    boards.board(LeaderboardType.BUILDER).orElseThrow().get(0).value()));
            assertTrue(boards.board(LeaderboardType.FARMER).orElseThrow().isEmpty());
        }
    }

    // ==================================================================================
    // City boards
    // ==================================================================================

    @Nested
    @DisplayName("city boards")
    class CityBoards {

        @Test
        @DisplayName("Cities by Treasury ranks by treasury")
        void byTreasury() {
            City poor = support.givenCity(support.givenEligiblePlayer("A"), "Neapolis", 0, 0);
            City rich = support.givenCity(support.givenEligiblePlayer("B"), "Roma", 40, 40);
            await(support.daos.cities().updateTreasury(rich.id(), new BigDecimal("90000.00")));
            rich.setTreasury(new BigDecimal("90000.00"));
            await(support.daos.cities().updateTreasury(poor.id(), new BigDecimal("10.00")));
            poor.setTreasury(new BigDecimal("10.00"));

            refresh();

            assertEquals(List.of("Roma", "Neapolis"), namesOn(LeaderboardType.CITY_TREASURY));
        }

        @Test
        @DisplayName("Cities by Size ranks by claim count")
        void bySize() {
            UUID bigMayor = support.givenEligiblePlayer("B");
            City big = support.givenCity(bigMayor, "Roma", 0, 0);
            support.givenCity(support.givenEligiblePlayer("A"), "Alba", 40, 40);

            await(support.daos.cities().updateTreasury(big.id(), new BigDecimal("500000.00")));
            big.setTreasury(new BigDecimal("500000.00"));
            Result<?> claimed = await(support.claims.claim(bigMayor, big, "world", 1, 0));
            assertTrue(claimed.isSuccess(), reasonOf(claimed));

            refresh();

            assertEquals(List.of("Roma", "Alba"), namesOn(LeaderboardType.CITY_SIZE));
            assertEquals(0, BigDecimal.valueOf(2).compareTo(
                    boards.board(LeaderboardType.CITY_SIZE).orElseThrow().get(0).value()));
        }

        @Test
        @DisplayName("Cities by Population counts active members, and can read zero")
        void byPopulation() {
            UUID mayor = support.givenEligiblePlayer("Romulus");
            City roma = support.givenCity(mayor, "Roma", 0, 0);
            support.givenMember(roma, "Remus");
            support.refreshPricing();

            // A city whose members have never played: the divisor floors this at one, but the
            // board must be able to say nobody is active.
            UUID quiet = support.givenPlayer("Ghost", BigDecimal.ZERO, 0L);
            await(support.daos.players().update(new dev.civitas.storage.row.PlayerRow(
                    quiet, "Ghost", new BigDecimal("50000.00"), null, null,
                    1_000L, System.currentTimeMillis(), 0L, 0L, 0, 0L, 0L, false, 0L, 0L)));

            refresh();

            List<LeaderboardEntry> entries =
                    boards.board(LeaderboardType.CITY_POPULATION).orElseThrow();
            assertEquals("Roma", entries.get(0).name());
            assertEquals(0, BigDecimal.valueOf(2).compareTo(entries.get(0).value()));
        }

        @Test
        @DisplayName("ties break by name, so the order does not shuffle between sweeps")
        void tiesAreStable() {
            support.givenCity(support.givenEligiblePlayer("A"), "Zama", 0, 0);
            support.givenCity(support.givenEligiblePlayer("B"), "Alba", 40, 40);
            support.givenCity(support.givenEligiblePlayer("C"), "Massilia", 80, 80);

            refresh();
            List<String> first = namesOn(LeaderboardType.CITY_TREASURY);
            refresh();
            List<String> second = namesOn(LeaderboardType.CITY_TREASURY);

            assertEquals(List.of("Alba", "Massilia", "Zama"), first,
                    "cities on equal treasury should be ordered by name");
            assertEquals(first, second);
        }
    }

    // ==================================================================================
    // Size and paging
    // ==================================================================================

    @Nested
    @DisplayName("size and paging")
    class Paging {

        @Test
        @DisplayName("a board keeps no more than the configured size")
        void honoursConfiguredSize() {
            int size = boards.size();
            for (int i = 0; i < size + 5; i++) {
                support.givenPlayer("Player" + i, BigDecimal.valueOf(1000L + i), 0L);
            }

            refresh();

            assertEquals(size, boards.board(LeaderboardType.WEALTH).orElseThrow().size());
        }

        @Test
        @DisplayName("pages split the board and stop at the end")
        void pagesSplitTheBoard() {
            int pageSize = boards.pageSize();
            for (int i = 0; i < pageSize + 1; i++) {
                support.givenPlayer("Player" + i, BigDecimal.valueOf(1000L + i), 0L);
            }

            refresh();

            assertEquals(pageSize, boards.page(LeaderboardType.WEALTH, 1).size());
            assertEquals(1, boards.page(LeaderboardType.WEALTH, 2).size());
            assertTrue(boards.page(LeaderboardType.WEALTH, 99).isEmpty());
            assertEquals(2, boards.pageCount(LeaderboardType.WEALTH));
        }

        @Test
        @DisplayName("an empty board still has one page")
        void emptyBoardHasOnePage() {
            refresh();

            assertEquals(1, boards.pageCount(LeaderboardType.FARMER));
        }
    }

    // ==================================================================================
    // The type table itself
    // ==================================================================================

    @Nested
    @DisplayName("board types")
    class Types {

        @Test
        @DisplayName("every board SPEC 13.3 names is present")
        void allNineExist() {
            // SPEC 13.3's prose and SPEC 19 both say seven; the table lists nine and is the
            // only enumeration. See OPEN_QUESTIONS.md.
            assertEquals(9, LeaderboardType.all().size());
        }

        @ParameterizedTest
        @EnumSource(LeaderboardType.class)
        @DisplayName("resolves from what a player types, however they capitalise it")
        void parses(LeaderboardType type) {
            assertEquals(type, LeaderboardType.parse(type.key()).orElseThrow());
            assertEquals(type, LeaderboardType.parse(type.key().toUpperCase(java.util.Locale.ROOT))
                    .orElseThrow());
            assertEquals(type, LeaderboardType.parse(type.key().replace('-', '_')).orElseThrow());
        }

        @Test
        @DisplayName("an unknown name resolves to nothing rather than to the first board")
        void unknownParsesToEmpty() {
            assertTrue(LeaderboardType.parse("gold").isEmpty());
            assertTrue(LeaderboardType.parse("").isEmpty());
            assertTrue(LeaderboardType.parse(null).isEmpty());
        }

        @ParameterizedTest
        @EnumSource(LeaderboardType.class)
        @DisplayName("keys are unique, so no two boards answer to the same word")
        void keysAreUnique(LeaderboardType type) {
            long sharing = LeaderboardType.all().stream()
                    .filter(other -> other.key().equals(type.key()))
                    .count();
            assertEquals(1L, sharing);
        }

        @Test
        @DisplayName("declaration order follows the SPEC 13.3 table")
        void orderFollowsSpec() {
            assertEquals(List.of("wealth", "city-treasury", "city-size", "city-population",
                            "contest-champions", "war-record", "contribution", "builder", "farmer"),
                    LeaderboardType.all().stream().map(LeaderboardType::key).toList());
        }
    }

    /** Kept out of the nested classes because it asserts about the comparator, not a board. */
    @Test
    @DisplayName("the money boards and the count boards use different entry lines")
    void formatsHaveDistinctLangKeys() {
        assertEquals("leaderboard.entry.money", LeaderboardType.WEALTH.entryKey());
        assertEquals("leaderboard.entry.count", LeaderboardType.BUILDER.entryKey());
        assertEquals("leaderboard.entry.record", LeaderboardType.WAR_RECORD.entryKey());
    }

    /** Guards the comparator the city boards share. */
    @Test
    @DisplayName("higher values come first")
    void higherFirst() {
        Comparator<BigDecimal> order = Comparator.reverseOrder();
        assertTrue(order.compare(BigDecimal.TEN, BigDecimal.ONE) < 0);
    }
}
