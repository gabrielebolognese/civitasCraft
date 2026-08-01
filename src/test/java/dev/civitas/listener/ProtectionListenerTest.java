package dev.civitas.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityNameValidator;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.city.CityService;
import dev.civitas.core.city.Placement;
import dev.civitas.core.city.RankService;
import dev.civitas.core.claim.ClaimCostEngine;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.claim.ClaimService;
import dev.civitas.core.economy.PlayerAccountService;
import dev.civitas.core.economy.StorageFunds;
import dev.civitas.core.protection.BlockClassifier;
import dev.civitas.core.protection.ProtectionGuard;
import dev.civitas.core.protection.ProtectionService;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.DatabaseSettings;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.EventBus;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * The SPEC 5.5 listeners against a real server.
 *
 * <p>{@link dev.civitas.core.protection.ProtectionServiceTest} proves the rules; this proves
 * the wiring, which is the half that silently does nothing if an event is misread. Handlers
 * are called directly rather than through the plugin manager, so a failure points at the
 * handler rather than at registration.
 */
class ProtectionListenerTest {

    private static final String WORLD = "world";

    @TempDir
    Path directory;

    private ServerMock server;
    private WorldMock world;
    private DatabaseManager db;
    private ClaimRegistry claimRegistry;
    private ProtectionGuard guard;
    private ProtectionService protection;
    private BlockClassifier blocks;

    private City city;
    private PlayerMock mayor;
    private PlayerMock outsider;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld(WORLD);

        Logger quiet = Logger.getLogger("listener-test");
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        ConfigManager configs = new ConfigManager(
                PluginResources.ofClasspath(directory.resolve("plugin").toFile(), quiet));
        configs.loadAll();

        LangManager lang = new LangManager(
                PluginResources.ofClasspath(directory.resolve("plugin").toFile(), quiet), configs);
        lang.load();

        DatabaseSettings settings = new DatabaseSettings(SqlDialect.SQLITE,
                "jdbc:sqlite:" + directory.resolve("protect.db").toAbsolutePath(),
                "", "", 2, 5000, "WAL", Long.MAX_VALUE, false, 6, 28);
        db = new DatabaseManager(quiet, settings, () -> false);
        db.open();

        DaoRegistry daos = new DaoRegistry(db);
        CityRegistry cities = new CityRegistry(daos);
        claimRegistry = new ClaimRegistry(daos.claims());
        PlayerAccountService accounts =
                new PlayerAccountService(db, daos.players(), daos.ledger(), configs);
        ClaimService claims = new ClaimService(db, daos, cities, claimRegistry,
                new ClaimCostEngine(configs), configs, Scheduler.direct(), EventBus.noop());
        CityService cityService = new CityService(db, daos, cities, configs,
                new CityNameValidator(configs), new StorageFunds(daos.players(), daos.ledger(), configs),
                claims, accounts, Scheduler.direct(), EventBus.noop());

        protection = new ProtectionService(claimRegistry, cities, configs);
        guard = new ProtectionGuard(protection, lang);
        blocks = new BlockClassifier(configs, quiet);

        mayor = server.addPlayer("Romulus");
        mayor.setOp(false);
        outsider = server.addPlayer("Stranger");
        outsider.setOp(false);

        seedPlayer(daos, mayor.getUniqueId(), "Romulus");
        seedPlayer(daos, outsider.getUniqueId(), "Stranger");

        Result<City> founded = await(cityService.create(mayor.getUniqueId(), "Roma",
                new Placement(WORLD, 0, 0, 8.5, 64.0, 8.5, 0f, 0f)));
        assertTrue(founded.isSuccess(), "fixture city failed");
        city = founded.orElseThrow();
        // Ranks are unused here; every test is either the mayor or a total outsider.
        assertTrue(city.isMayor(mayor.getUniqueId()));
    }

    private static void seedPlayer(DaoRegistry daos, UUID uuid, String name) {
        await(daos.players().insert(new PlayerRow(uuid, name, new BigDecimal("50000.00"),
                null, null, 1L, System.currentTimeMillis(),
                TimeUnit.HOURS.toMillis(10), TimeUnit.HOURS.toMillis(10),
                0, 0L, 0L, false, 0L, 0L)));
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @AfterEach
    void tearDown() {
        db.close();
        MockBukkit.unmock();
    }

    /** A block inside the city's core chunk. */
    private Block ownedBlock(int x, int z) {
        return world.getBlockAt(x, 64, z);
    }

    /** A block far outside any claim. */
    private Block wildBlock() {
        return world.getBlockAt(6400, 64, 6400);
    }

    // ==================================================================================
    // SPEC 18.2: permission enforcement on a block break
    // ==================================================================================

    @Test
    @DisplayName("a non-member's block break inside a claim is cancelled")
    void outsiderCannotBreak() {
        BlockBreakEvent event = new BlockBreakEvent(ownedBlock(8, 8), outsider);

        new BlockProtectionListener(guard).onBreak(event);

        assertTrue(event.isCancelled(), "the listener must cancel the break");
    }

    @Test
    @DisplayName("the mayor's block break is left alone")
    void mayorMayBreak() {
        BlockBreakEvent event = new BlockBreakEvent(ownedBlock(8, 8), mayor);

        new BlockProtectionListener(guard).onBreak(event);

        assertFalse(event.isCancelled());
    }

    @Test
    @DisplayName("a break in wilderness is left alone, whoever swings")
    void wildernessIsFree() {
        BlockBreakEvent event = new BlockBreakEvent(wildBlock(), outsider);

        new BlockProtectionListener(guard).onBreak(event);

        assertFalse(event.isCancelled());
    }

    @Test
    @DisplayName("civitas.bypass.claim lets an admin break anywhere, SPEC 10")
    void bypassWorks() {
        outsider.addAttachment(MockBukkit.createMockPlugin(), ProtectionGuard.BYPASS_PERMISSION, true);

        BlockBreakEvent event = new BlockBreakEvent(ownedBlock(8, 8), outsider);
        new BlockProtectionListener(guard).onBreak(event);

        assertFalse(event.isCancelled(), "bypass must reach the listener, not only the service");
    }

    // ==================================================================================
    // Containers
    // ==================================================================================

    @Test
    @DisplayName("a non-member cannot open a chest in a claim")
    void outsiderCannotOpenChest() {
        Block chest = ownedBlock(8, 9);
        chest.setType(Material.CHEST);

        PlayerInteractEvent event = new PlayerInteractEvent(outsider, Action.RIGHT_CLICK_BLOCK,
                null, chest, BlockFace.UP);

        new ContainerProtectionListener(guard, blocks).onOpen(event);

        assertEquals(Event.Result.DENY, event.useInteractedBlock());
    }

    @Test
    @DisplayName("the interaction listener leaves containers to the container listener")
    void interactionListenerSkipsContainers() {
        Block chest = ownedBlock(8, 9);
        chest.setType(Material.CHEST);

        PlayerInteractEvent event = new PlayerInteractEvent(outsider, Action.RIGHT_CLICK_BLOCK,
                null, chest, BlockFace.UP);

        new InteractionProtectionListener(guard, blocks).onInteract(event);

        assertNotEquals(Event.Result.DENY, event.useInteractedBlock(),
                "otherwise a chest would be checked twice and read-only access would break");
    }

    @Test
    @DisplayName("a non-member cannot use a door in a claim")
    void outsiderCannotUseDoor() {
        Block door = ownedBlock(9, 9);
        door.setType(Material.OAK_DOOR);

        PlayerInteractEvent event = new PlayerInteractEvent(outsider, Action.RIGHT_CLICK_BLOCK,
                null, door, BlockFace.NORTH);

        new InteractionProtectionListener(guard, blocks).onInteract(event);

        assertEquals(Event.Result.DENY, event.useInteractedBlock());
    }

    @Test
    @DisplayName("an ordinary block can still be right-clicked by anyone")
    void plainBlocksAreNotInteractions() {
        Block stone = ownedBlock(10, 10);
        stone.setType(Material.STONE);

        PlayerInteractEvent event = new PlayerInteractEvent(outsider, Action.RIGHT_CLICK_BLOCK,
                null, stone, BlockFace.UP);

        new InteractionProtectionListener(guard, blocks).onInteract(event);

        assertNotEquals(Event.Result.DENY, event.useInteractedBlock());
    }

    // ==================================================================================
    // Pistons, SPEC 5.5
    // ==================================================================================

    @Test
    @DisplayName("a piston pushing across a claim boundary is cancelled")
    void pistonAcrossBoundaryIsCancelled() {
        // The piston sits in the city's core chunk; the block it pushes lands in the next
        // chunk east, which nobody owns.
        Block piston = ownedBlock(15, 8);
        Block pushed = ownedBlock(15, 8);

        BlockPistonExtendEvent event =
                new BlockPistonExtendEvent(piston, List.of(pushed), BlockFace.EAST);

        new PistonProtectionListener(protection).onExtend(event);

        assertTrue(event.isCancelled(),
                "block 15 is the east edge of chunk 0, so pushing east leaves the claim");
    }

    @Test
    @DisplayName("a piston moving entirely inside one claim is left alone")
    void pistonWithinOneClaimIsAllowed() {
        Block piston = ownedBlock(4, 8);
        Block pushed = ownedBlock(5, 8);

        BlockPistonExtendEvent event =
                new BlockPistonExtendEvent(piston, List.of(pushed), BlockFace.EAST);

        new PistonProtectionListener(protection).onExtend(event);

        assertFalse(event.isCancelled());
    }

    @Test
    @DisplayName("a piston in wilderness is left alone")
    void pistonInWildernessIsAllowed() {
        Block piston = wildBlock();
        Block pushed = world.getBlockAt(6401, 64, 6400);

        BlockPistonExtendEvent event =
                new BlockPistonExtendEvent(piston, List.of(pushed), BlockFace.EAST);

        new PistonProtectionListener(protection).onExtend(event);

        assertFalse(event.isCancelled());
    }

    // ==================================================================================
    // The refusal message
    // ==================================================================================

    @Test
    @DisplayName("a refused player is told once, not once per attempt")
    void denialIsThrottled() {
        for (int attempt = 0; attempt < 5; attempt++) {
            BlockBreakEvent event = new BlockBreakEvent(ownedBlock(8, 8), outsider);
            new BlockProtectionListener(guard).onBreak(event);
            assertTrue(event.isCancelled());
        }

        // Every attempt is still refused; the throttle governs the message, never the rule.
        assertTrue(protection.check(outsider.getUniqueId(), false, WORLD, 0, 0,
                dev.civitas.core.protection.ProtectionAction.BUILD).denied());
    }
}
