package dev.civitas.gui.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Confirmation dialogs, SPEC 8.2, and SPEC 17.5 case 66: closing one is a cancel.
 *
 * <p>Every destructive action in SPEC Section 8 goes through this class, so "confirmed
 * exactly once, or not at all" is the property that matters most here.
 */
class ConfirmationMenuTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private ConfigManager configs;
    private MenuManager menus;
    private MenuListener listener;
    private PlayerMock player;

    private final AtomicInteger confirmed = new AtomicInteger();
    private final AtomicInteger cancelled = new AtomicInteger();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        configs = GuiTestSupport.configs(directory.toFile());
        LangManager lang = GuiTestSupport.lang(directory.toFile(), configs);

        menus = new MenuManager(configs, lang);
        listener = new MenuListener(menus);
        player = server.addPlayer("Decider");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ConfirmationMenu dialog() {
        return ConfirmationMenu.builder(menus, player)
                .title(Component.text("Are you sure?"))
                .question(Component.text("This cannot be undone"))
                .detail(Component.text("Refund: 50%"))
                .onConfirm(confirmed::incrementAndGet)
                .onCancel(cancelled::incrementAndGet)
                .build();
    }

    // ==================================================================================
    // SPEC 8.2 layout
    // ==================================================================================

    @Test
    @DisplayName("SPEC 8.2: Lime Concrete Confirm on 29, Red Concrete Cancel on 33")
    void layout() {
        ConfirmationMenu menu = dialog();
        menu.open();

        assertEquals(Material.LIME_CONCRETE, menu.inventory().getItem(29).getType());
        assertEquals(Material.RED_CONCRETE, menu.inventory().getItem(33).getType());
    }

    @Test
    @DisplayName("the question and its details are on the Confirm button, where they are read")
    void questionIsOnConfirm() {
        ConfirmationMenu menu = dialog();
        menu.open();

        var lore = menu.inventory().getItem(29).getItemMeta().lore();
        assertEquals(2, lore.size(), "the question and the one detail");
    }

    // ==================================================================================
    // Deciding
    // ==================================================================================

    @Test
    @DisplayName("Confirm runs the action, once")
    void confirm() {
        ConfirmationMenu menu = dialog();
        menu.open();

        menu.click(player, 29, ClickType.LEFT);

        assertEquals(1, confirmed.get());
        assertEquals(0, cancelled.get());
        assertTrue(menu.decided());
    }

    @Test
    @DisplayName("Cancel runs the cancel action and never the confirm one")
    void cancel() {
        ConfirmationMenu menu = dialog();
        menu.open();

        menu.click(player, 33, ClickType.LEFT);

        assertEquals(0, confirmed.get());
        assertEquals(1, cancelled.get());
    }

    @Test
    @DisplayName("a second click after a decision does nothing")
    void decidedOnlyOnce() {
        ConfirmationMenu menu = dialog();
        menu.open();

        menu.click(player, 29, ClickType.LEFT);
        menu.click(player, 29, ClickType.LEFT);
        menu.click(player, 33, ClickType.LEFT);

        assertEquals(1, confirmed.get(), "a double-click must not disband a city twice");
        assertEquals(0, cancelled.get());
    }

    // ==================================================================================
    // SPEC 17.5 case 66
    // ==================================================================================

    @Test
    @DisplayName("case 66: closing the window without answering is a cancel")
    void closingIsCancelling() {
        ConfirmationMenu menu = dialog();
        menu.open();

        listener.onClose(new InventoryCloseEvent(player.getOpenInventory()));

        assertEquals(0, confirmed.get(), "walking away must never mean yes");
        assertEquals(1, cancelled.get());
        assertTrue(menu.decided());
    }

    @Test
    @DisplayName("case 66: closing after confirming does not also cancel")
    void closeAfterConfirm() {
        ConfirmationMenu menu = dialog();
        menu.open();

        menu.click(player, 29, ClickType.LEFT);
        listener.onClose(new InventoryCloseEvent(player.getOpenInventory()));

        assertEquals(1, confirmed.get());
        assertEquals(0, cancelled.get(), "the action already ran; it was not then undone");
    }

    @Test
    @DisplayName("a player who logs out mid-dialog has cancelled")
    void quitIsCancel() {
        ConfirmationMenu menu = dialog();
        menu.open();

        listener.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player, Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertEquals(0, confirmed.get());
        assertEquals(1, cancelled.get());
    }

    @Test
    @DisplayName("close-is-cancel can be turned off, and then closing decides nothing")
    void closeIsCancelIsConfigurable() {
        configs.get(ConfigFile.GUI).set("confirmation.close-is-cancel", false);

        ConfirmationMenu menu = dialog();
        menu.open();
        listener.onClose(new InventoryCloseEvent(player.getOpenInventory()));

        assertEquals(0, confirmed.get());
        assertEquals(0, cancelled.get());
        assertFalse(menu.decided());
    }

    @Test
    @DisplayName("a dialog with no cancel handler still refuses to confirm on close")
    void noCancelHandler() {
        ConfirmationMenu menu = ConfirmationMenu.builder(menus, player)
                .title(Component.text("Sure?"))
                .question(Component.text("Yes?"))
                .onConfirm(confirmed::incrementAndGet)
                .build();
        menu.open();

        listener.onClose(new InventoryCloseEvent(player.getOpenInventory()));

        assertEquals(0, confirmed.get());
        assertTrue(menu.decided());
    }
}
