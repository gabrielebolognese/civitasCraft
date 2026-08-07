package dev.civitas.core.war;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.CityTestSupport;
import dev.civitas.storage.row.WarContainerLogRow;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * SPEC 11.7's container log.
 *
 * <p>The behaviour being pinned down is SPEC 17.4 case 44's asymmetry, which is the one part of
 * the war system players are most likely to misunderstand: <b>destroying storage is pointless,
 * looting it is not.</b> A chest broken in war drops nothing and comes back full; a chest opened
 * and emptied by hand stays empty, and this log is the only record of who emptied it.
 */
class WarLootLogTest {

    @TempDir
    Path directory;

    private static final int WAR = 7;
    private static final long NOW = System.currentTimeMillis();

    private ServerMock server;
    private CityTestSupport support;
    private WarLootLog loot;
    private UUID raider;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        support = CityTestSupport.open(directory);
        loot = new WarLootLog(support.daos.warContainerLog(), quiet());
        raider = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        support.close();
        MockBukkit.unmock();
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("civitas-loot-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private Location somewhere() {
        return new Location(server.addSimpleWorld("world"), 100, 64, 200);
    }

    private Inventory chestWith(ItemStack... items) {
        Inventory inventory = server.createInventory(null, 27);
        inventory.setContents(items);
        return inventory;
    }

    private List<WarContainerLogRow> written() {
        loot.flushBlocking();
        return await(support.daos.warContainerLog().findByWar(WAR));
    }

    // ==================================================================================
    // What counts as theft
    // ==================================================================================

    @Test
    @DisplayName("what left the chest is recorded, with who took it and from where")
    void recordsWhatWasTaken() {
        Inventory chest = chestWith(new ItemStack(Material.DIAMOND, 12));
        Location at = somewhere();
        loot.opened(raider, List.of(WAR), at, chest);

        chest.setContents(new ItemStack[] {new ItemStack(Material.DIAMOND, 4)});
        loot.closed(raider, chest, NOW);

        List<WarContainerLogRow> rows = written();
        assertEquals(1, rows.size());
        assertEquals("DIAMOND", rows.get(0).item());
        assertEquals(8, rows.get(0).quantity());
        assertEquals(raider, rows.get(0).actorUuid());
        assertEquals(100, rows.get(0).x());
        assertEquals(64, rows.get(0).y());
        assertEquals(200, rows.get(0).z());
    }

    @Test
    @DisplayName("emptying a chest records the whole stack")
    void recordsAFullClearOut() {
        Inventory chest = chestWith(new ItemStack(Material.DIAMOND, 12),
                new ItemStack(Material.EMERALD, 5));
        loot.opened(raider, List.of(WAR), somewhere(), chest);

        chest.clear();
        loot.closed(raider, chest, NOW);

        List<WarContainerLogRow> rows = written();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(row -> row.item().equals("DIAMOND")
                && row.quantity() == 12));
        assertTrue(rows.stream().anyMatch(row -> row.item().equals("EMERALD")
                && row.quantity() == 5));
    }

    @Test
    @DisplayName("depositing records nothing")
    void depositsAreNotTheft() {
        Inventory chest = chestWith(new ItemStack(Material.DIAMOND, 4));
        loot.opened(raider, List.of(WAR), somewhere(), chest);

        chest.setContents(new ItemStack[] {new ItemStack(Material.DIAMOND, 20)});
        loot.closed(raider, chest, NOW);

        assertTrue(written().isEmpty());
    }

    @Test
    @DisplayName("putting something back and taking it again nets to nothing")
    void netRemovalOnly() {
        // The diff is across the whole open, so a player who rummages and changes their mind
        // has stolen nothing. Counting clicks would have recorded both directions.
        Inventory chest = chestWith(new ItemStack(Material.DIAMOND, 10));
        loot.opened(raider, List.of(WAR), somewhere(), chest);

        chest.setContents(new ItemStack[] {new ItemStack(Material.DIAMOND, 10)});
        loot.closed(raider, chest, NOW);

        assertTrue(written().isEmpty());
    }

    @Test
    @DisplayName("a swap records the item that left, not the one that arrived")
    void swapsRecordOneSide() {
        Inventory chest = chestWith(new ItemStack(Material.DIAMOND_SWORD, 1));
        loot.opened(raider, List.of(WAR), somewhere(), chest);

        chest.setContents(new ItemStack[] {new ItemStack(Material.STONE_SWORD, 1)});
        loot.closed(raider, chest, NOW);

        List<WarContainerLogRow> rows = written();
        assertEquals(1, rows.size());
        assertEquals("DIAMOND_SWORD", rows.get(0).item());
    }

    // ==================================================================================
    // Where the rule applies
    // ==================================================================================

    @Test
    @DisplayName("a container outside every war zone is not watched at all")
    void outsideAZoneIsIgnored() {
        Inventory chest = chestWith(new ItemStack(Material.DIAMOND, 12));
        loot.opened(raider, List.of(), somewhere(), chest);
        chest.clear();

        assertEquals(0, loot.closed(raider, chest, NOW));
        assertTrue(written().isEmpty());
        assertEquals(0, loot.watching());
    }

    @Test
    @DisplayName("a chunk inside two zones is recorded for both")
    void overlappingWarsBothSeeIt() {
        // SPEC 17.4 case 51: two wars can overlap geographically, and each one's post-war
        // report should show what happened inside it.
        Inventory chest = chestWith(new ItemStack(Material.DIAMOND, 6));
        loot.opened(raider, List.of(WAR, WAR + 1), somewhere(), chest);
        chest.clear();
        loot.closed(raider, chest, NOW);
        loot.flushBlocking();

        assertEquals(1, await(support.daos.warContainerLog().findByWar(WAR)).size());
        assertEquals(1, await(support.daos.warContainerLog().findByWar(WAR + 1)).size());
    }

    @Test
    @DisplayName("closing a container nobody opened records nothing")
    void unmatchedCloseIsSafe() {
        assertEquals(0, loot.closed(raider, chestWith(), NOW));
    }

    @Test
    @DisplayName("a player who disconnects mid-raid is forgotten rather than mis-attributed")
    void forgetDropsTheSnapshot() {
        // A snapshot left behind would be compared against the next container this player
        // opens, and accuse them of emptying a chest they never touched.
        Inventory chest = chestWith(new ItemStack(Material.DIAMOND, 12));
        loot.opened(raider, List.of(WAR), somewhere(), chest);

        loot.forget(raider);

        assertEquals(0, loot.closed(raider, chestWith(), NOW));
        assertFalse(loot.pendingCount() > 0);
    }

    @Test
    @DisplayName("the buffer empties when it is flushed")
    void flushDrains() {
        Inventory chest = chestWith(new ItemStack(Material.DIAMOND, 3));
        loot.opened(raider, List.of(WAR), somewhere(), chest);
        chest.clear();
        loot.closed(raider, chest, NOW);

        assertEquals(1, loot.pendingCount());
        assertEquals(1, loot.flushBlocking());
        assertEquals(0, loot.pendingCount());
        assertEquals(0, loot.flushBlocking(), "a second flush has nothing to write");
    }
}
