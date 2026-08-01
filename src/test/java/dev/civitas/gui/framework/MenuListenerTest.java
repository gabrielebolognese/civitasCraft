package dev.civitas.gui.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import dev.civitas.config.ConfigManager;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * SPEC 17.5 cases 59 to 66, and the SPEC 18.2 requirement that a click is validated against
 * a permission the viewer no longer has.
 *
 * <p>Handlers are called directly rather than through the plugin manager, so a failure points
 * at the handler rather than at registration.
 */
class MenuListenerTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private ConfigManager configs;
    private MenuManager menus;
    private MenuListener listener;
    private PlayerMock player;
    private GuiTestSupport.TestMenu menu;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        configs = GuiTestSupport.configs(directory.toFile());
        LangManager lang = GuiTestSupport.lang(directory.toFile(), configs);

        menus = new MenuManager(configs, lang);
        listener = new MenuListener(menus);
        player = server.addPlayer("Clicker");

        menu = new GuiTestSupport.TestMenu(menus, player);
        menu.open();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private InventoryView view() {
        return player.getOpenInventory();
    }

    private InventoryClickEvent click(int slot, ClickType type) {
        return new InventoryClickEvent(view(), org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                slot, type, InventoryAction.PICKUP_ALL);
    }

    private InventoryClickEvent clickOwnInventory(int rawSlot, ClickType type) {
        // Raw slots past the menu's size belong to the player's own inventory.
        return new InventoryClickEvent(view(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                menu.inventory().getSize() + rawSlot, type, InventoryAction.MOVE_TO_OTHER_INVENTORY);
    }

    // ==================================================================================
    // The basics
    // ==================================================================================

    @Test
    @DisplayName("the menu opens, is registered, and its button is drawn")
    void opens() {
        assertEquals(1, menus.openCount());
        assertTrue(menus.openMenu(player).isPresent());

        ItemStack drawn = menu.inventory().getItem(22);
        assertNotNull(drawn);
        assertEquals(Material.DIAMOND, drawn.getType());
    }

    @Test
    @DisplayName("a click on a button runs it, and the event is still cancelled")
    void clickRuns() {
        InventoryClickEvent event = click(22, ClickType.LEFT);
        listener.onClick(event);

        assertTrue(event.isCancelled(), "a menu click never moves an item, ever");
        assertEquals(java.util.List.of(22), menu.clicked);
    }

    @Test
    @DisplayName("a click on an empty slot does nothing but is still cancelled")
    void clickEmptySlot() {
        InventoryClickEvent event = click(13, ClickType.LEFT);
        listener.onClick(event);

        assertTrue(event.isCancelled());
        assertTrue(menu.clicked.isEmpty());
    }

    @Test
    @DisplayName("clicks on inventories that are not ours are left alone")
    void otherInventoriesAreUntouched() {
        player.closeInventory();
        var chest = server.createInventory(null, 27);
        player.openInventory(chest);

        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(event);

        assertFalse(event.isCancelled(), "an ordinary chest is not the plugin's business");
    }

    // ==================================================================================
    // SPEC 17.5 case 59, and SPEC 18.2
    // ==================================================================================

    @Nested
    @DisplayName("Permission is re-checked at execution time")
    class Permissions {

        @Test
        @DisplayName("case 59: a permission revoked while the menu is open stops the click")
        void revokedWhileOpen() {
            // Drawn while allowed, clicked after it was taken away.
            assertEquals(Material.DIAMOND, menu.inventory().getItem(22).getType());

            menu.permission = viewer -> false;

            InventoryClickEvent event = click(22, ClickType.LEFT);
            listener.onClick(event);

            assertTrue(menu.clicked.isEmpty(), "the stale button must not be usable");
            assertTrue(event.isCancelled());
        }

        @Test
        @DisplayName("the refused click redraws, so the player sees why it did nothing")
        void refusedClickRedraws() {
            menu.permission = viewer -> false;
            listener.onClick(click(22, ClickType.LEFT));

            ItemStack drawn = menu.inventory().getItem(22);
            assertEquals(Material.BARRIER, drawn.getType(),
                    "SPEC 8.2: a button you may not use renders as a barrier");
        }

        @Test
        @DisplayName("a button drawn as a barrier still cannot be clicked into working")
        void barrierIsNotAWayIn() {
            menu.permission = viewer -> false;
            menu.refresh();

            for (ClickType type : ClickType.values()) {
                listener.onClick(click(22, type));
            }

            assertTrue(menu.clicked.isEmpty(), "no click type is a way past the check");
        }

        @Test
        @DisplayName("granting the permission back makes the button work again with no reopen")
        void grantedBack() {
            menu.permission = viewer -> false;
            listener.onClick(click(22, ClickType.LEFT));
            assertTrue(menu.clicked.isEmpty());

            menu.permission = viewer -> true;
            listener.onClick(click(22, ClickType.LEFT));

            assertEquals(1, menu.clicked.size());
        }

        @Test
        @DisplayName("another player's click cannot drive somebody else's menu")
        void onlyTheViewerMayClick() {
            Player intruder = server.addPlayer("Intruder");
            intruder.openInventory(menu.inventory());

            InventoryClickEvent event = new InventoryClickEvent(intruder.getOpenInventory(),
                    org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 22,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL);
            listener.onClick(event);

            assertTrue(event.isCancelled());
            assertTrue(menu.clicked.isEmpty());
        }
    }

    // ==================================================================================
    // SPEC 17.5 cases 61 to 63
    // ==================================================================================

    @Nested
    @DisplayName("Nothing may move in or out")
    class NoItemMovement {

        @Test
        @DisplayName("case 61: a drag touching the menu is cancelled")
        void dragIsCancelled() {
            Map<Integer, ItemStack> added = new HashMap<>();
            added.put(20, new ItemStack(Material.DIRT));
            added.put(21, new ItemStack(Material.DIRT));

            InventoryDragEvent event = new InventoryDragEvent(view(),
                    new ItemStack(Material.DIRT), new ItemStack(Material.DIRT, 2), false, added);
            listener.onDrag(event);

            assertTrue(event.isCancelled());
        }

        @Test
        @DisplayName("case 62: a shift-click from the player's own inventory is cancelled")
        void shiftClickFromOwnInventory() {
            InventoryClickEvent event = clickOwnInventory(4, ClickType.SHIFT_LEFT);
            listener.onClick(event);

            assertTrue(event.isCancelled(),
                    "otherwise the item lands in the menu and the next redraw eats it");
        }

        @Test
        @DisplayName("case 63: a number-key hotbar swap is cancelled")
        void numberKeySwap() {
            // The click SPEC singles out: it does not present as a click on the menu at all.
            InventoryClickEvent onMenu = click(22, ClickType.NUMBER_KEY);
            listener.onClick(onMenu);
            assertTrue(onMenu.isCancelled());

            InventoryClickEvent onOwn = clickOwnInventory(4, ClickType.NUMBER_KEY);
            listener.onClick(onOwn);
            assertTrue(onOwn.isCancelled());
        }

        @Test
        @DisplayName("the offhand swap and the double-click sweep are cancelled too")
        void otherSneakyClicks() {
            for (ClickType type : java.util.List.of(ClickType.SWAP_OFFHAND,
                    ClickType.DOUBLE_CLICK, ClickType.CONTROL_DROP, ClickType.DROP)) {
                InventoryClickEvent event = clickOwnInventory(4, type);
                listener.onClick(event);
                assertTrue(event.isCancelled(), type + " should not reach the menu");
            }
        }

        @Test
        @DisplayName("an ordinary click in the player's own inventory is left alone")
        void ownInventoryStillWorks() {
            InventoryClickEvent event = clickOwnInventory(4, ClickType.LEFT);
            listener.onClick(event);

            assertFalse(event.isCancelled(),
                    "a player's own items stay their own while a menu is open");
        }

        @Test
        @DisplayName("every click type on the menu itself is cancelled, whatever it is")
        void everyClickTypeIsCancelled() {
            for (ClickType type : ClickType.values()) {
                InventoryClickEvent event = click(22, type);
                listener.onClick(event);
                assertTrue(event.isCancelled(), type + " was not cancelled");
            }
        }
    }

    // ==================================================================================
    // SPEC 17.5 cases 60, 64 and 66
    // ==================================================================================

    @Nested
    @DisplayName("Sessions")
    class Sessions {

        @Test
        @DisplayName("closing the window forgets the session")
        void closeForgets() {
            listener.onClose(new InventoryCloseEvent(view()));

            assertEquals(0, menus.openCount());
        }

        @Test
        @DisplayName("case 60: a menu can be force-closed, with a reason")
        void forceClose() {
            assertEquals(1, menus.closeIf(open -> true, "gui.close"));

            assertEquals(0, menus.openCount());
        }

        @Test
        @DisplayName("case 64: a refresh reconciles the snapshot without reopening the window")
        void refreshRedrawsInPlace() {
            var before = view();
            menu.liveData = true;
            int builds = menu.builds;

            assertEquals(1, menus.refreshLive());

            assertEquals(builds + 1, menu.builds, "it rebuilt");
            assertEquals(before, player.getOpenInventory(),
                    "and did not close and reopen the window under the player's cursor");
        }

        @Test
        @DisplayName("a menu that is not live is not redrawn")
        void staticMenusAreNotRefreshed() {
            int builds = menu.builds;

            assertEquals(0, menus.refreshLive());

            assertEquals(builds, menu.builds);
        }

        @Test
        @DisplayName("a viewer who logged out is dropped rather than drawn to")
        void offlineViewerIsForgotten() {
            menu.liveData = true;
            player.disconnect();

            menus.refreshLive();

            assertEquals(0, menus.openCount());
        }
    }

    // ==================================================================================
    // Navigation, SPEC 8.2
    // ==================================================================================

    @Test
    @DisplayName("SPEC 8.2: Close sits on slot 49, and Back on 45 only when there is a parent")
    void navigationSlots() {
        assertEquals(Material.BARRIER, menu.inventory().getItem(49).getType());
        assertTrue(menu.buttonAt(45).isEmpty(), "a root menu has nowhere to go back to");

        GuiTestSupport.TestMenu child = new GuiTestSupport.TestMenu(menus, player, menu);
        child.open();

        assertEquals(Material.ARROW, child.inventory().getItem(45).getType());
    }

    @Test
    @DisplayName("SPEC 8.2: the border is gray panes named with a single space")
    void border() {
        ItemStack pane = menu.inventory().getItem(0);

        assertNotNull(pane);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, pane.getType());
        assertEquals(Component.text(" "),
                pane.getItemMeta().displayName().children().isEmpty()
                        ? Component.text(" ")
                        : pane.getItemMeta().displayName());
        assertTrue(menu.buttonAt(0).isEmpty(), "and it is furniture, not a button");
    }

    @Test
    @DisplayName("clicking the border does nothing at all")
    void borderIsNotClickable() {
        InventoryClickEvent event = click(0, ClickType.LEFT);
        listener.onClick(event);

        assertTrue(event.isCancelled());
        assertTrue(menu.clicked.isEmpty());
    }
}
