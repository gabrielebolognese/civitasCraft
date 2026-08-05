package dev.civitas.core.progression;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import dev.civitas.core.city.CityTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lifetime counters behind SPEC 13.3's Builder and Farmer boards.
 *
 * <p>What is worth testing here is not that a number goes up. It is that the buffering does
 * not lose anything: increments arrive on the event path and are written later, so every one
 * of these tests is really about the gap between those two moments.
 */
class StatsServiceTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private StatsService stats;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        stats = new StatsService(support.daos.playerStats(), CityTestSupport.quietLogger());
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private UUID givenPlayer(String name) {
        return support.givenPlayer(name, BigDecimal.ZERO, 0L);
    }

    private long stored(UUID player, PlayerStat stat) {
        return await(support.daos.playerStats().find(player, stat.key()))
                .map(row -> row.value())
                .orElse(0L);
    }

    @Test
    @DisplayName("increments accumulate in memory and land in one write")
    void accumulatesThenWrites() {
        UUID player = givenPlayer("Vitruvius");

        for (int i = 0; i < 250; i++) {
            stats.record(player, PlayerStat.BLOCKS_PLACED, 1);
        }
        assertEquals(1, stats.pendingPlayers(), "everything should still be buffered");
        assertEquals(0L, stored(player, PlayerStat.BLOCKS_PLACED),
                "nothing should reach the database before a flush");

        await(stats.flush(System.currentTimeMillis()));

        assertEquals(250L, stored(player, PlayerStat.BLOCKS_PLACED));
        assertEquals(0, stats.pendingPlayers());
    }

    @Test
    @DisplayName("a second flush adds to the first rather than replacing it")
    void flushesAccumulate() {
        UUID player = givenPlayer("Vitruvius");

        stats.record(player, PlayerStat.CROPS_HARVESTED, 40);
        await(stats.flush(System.currentTimeMillis()));
        stats.record(player, PlayerStat.CROPS_HARVESTED, 2);
        await(stats.flush(System.currentTimeMillis()));

        assertEquals(42L, stored(player, PlayerStat.CROPS_HARVESTED));
    }

    @Test
    @DisplayName("counters are kept apart, per player and per stat")
    void countersAreIndependent() {
        UUID one = givenPlayer("Vitruvius");
        UUID two = givenPlayer("Cincinnatus");

        stats.record(one, PlayerStat.BLOCKS_PLACED, 5);
        stats.record(one, PlayerStat.CROPS_HARVESTED, 3);
        stats.record(two, PlayerStat.BLOCKS_PLACED, 11);
        await(stats.flush(System.currentTimeMillis()));

        assertEquals(5L, stored(one, PlayerStat.BLOCKS_PLACED));
        assertEquals(3L, stored(one, PlayerStat.CROPS_HARVESTED));
        assertEquals(11L, stored(two, PlayerStat.BLOCKS_PLACED));
        assertEquals(0L, stored(two, PlayerStat.CROPS_HARVESTED));
    }

    @Test
    @DisplayName("flushing nothing writes nothing and does not fail")
    void emptyFlushIsFree() {
        assertEquals(0, await(stats.flush(System.currentTimeMillis())));
    }

    @Test
    @DisplayName("counters only go up: a zero or negative amount is ignored")
    void refusesNonPositive() {
        UUID player = givenPlayer("Vitruvius");

        stats.record(player, PlayerStat.BLOCKS_PLACED, 0);
        stats.record(player, PlayerStat.BLOCKS_PLACED, -50);

        assertEquals(0, stats.pendingPlayers());
        await(stats.flush(System.currentTimeMillis()));
        assertEquals(0L, stored(player, PlayerStat.BLOCKS_PLACED));
    }

    @Test
    @DisplayName("the disable flush writes what is buffered")
    void disableFlushWrites() {
        UUID player = givenPlayer("Vitruvius");
        stats.record(player, PlayerStat.BLOCKS_PLACED, 9);

        stats.flushBlocking(System.currentTimeMillis());

        assertEquals(9L, stored(player, PlayerStat.BLOCKS_PLACED));
    }

    @Test
    @DisplayName("a failed flush keeps its batch instead of dropping it")
    void failedFlushIsRetained() {
        UUID player = givenPlayer("Vitruvius");
        stats.record(player, PlayerStat.BLOCKS_PLACED, 12);

        // Closing the database makes the write fail the way a lost connection would. It also
        // fails synchronously rather than as a failed future, which is exactly the path that
        // dropped the batch before this test existed.
        support.db.close();
        assertEquals(0, await(stats.flush(System.currentTimeMillis())));

        assertEquals(1, stats.pendingPlayers(),
                "the batch should have been put back for the next sweep");
    }

    @Test
    @DisplayName("a placement outside a war zone counts, which is every placement until M19")
    void placementsCountForNow() {
        UUID player = givenPlayer("Vitruvius");

        // SPEC 13.3 excludes war zones; nothing is in one until M19 computes them, so the
        // seam must currently let every placement through.
        stats.recordPlacement(player, null);
        await(stats.flush(System.currentTimeMillis()));

        assertEquals(1L, stored(player, PlayerStat.BLOCKS_PLACED));
    }

    @Test
    @DisplayName("a stat name this build does not know is ignored, not fatal")
    void unknownStatNamesAreIgnored() {
        assertTrue(PlayerStat.parse("SOMETHING_A_LATER_VERSION_ADDED").isEmpty());
        assertTrue(PlayerStat.parse(null).isEmpty());
        assertEquals(PlayerStat.BLOCKS_PLACED, PlayerStat.parse("blocks_placed").orElseThrow());
    }
}
