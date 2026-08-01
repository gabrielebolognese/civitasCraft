package dev.civitas.gui.framework;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * The one place a click on a menu is allowed in, SPEC 8.2 and SPEC 17.5 cases 59 to 66.
 *
 * <h2>Cancel first, ask questions later</h2>
 * Every click and every drag in one of our inventories is cancelled <em>before</em> anything
 * looks at what it was, and the cancel is unconditional: not "if the slot is a button", not
 * "if the click is a left click". A menu is a display, and no item may ever enter or leave
 * one by any route. Only after the event is dead is the slot handed to the menu, which
 * re-checks the button's permission itself.
 *
 * <p>That ordering is what makes the exploit list uninteresting. A number-key swap (case 63),
 * a shift-click from the player's own inventory (case 62), a drag across both inventories
 * (case 61), a double-click sweep, an offhand swap, a drop with an item on the cursor: all of
 * them are already cancelled by the time this class works out which one it was looking at.
 */
public final class MenuListener implements Listener {

    private final MenuManager menus;

    public MenuListener(MenuManager menus) {
        this.menus = Objects.requireNonNull(menus, "menus");
    }

    // ==================================================================================
    // Clicks
    // ==================================================================================

    /**
     * {@link EventPriority#LOWEST} without {@code ignoreCancelled}: this must run before any
     * other plugin's handler and must still run for an event something else already
     * cancelled, because "already cancelled" is not the same as "cancelled by us".
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        // The window's top inventory. Null means this is not one of our windows at all, and
        // no click inside it is our business.
        Menu menu = menuOf(event.getInventory());
        if (menu == null) {
            return;
        }

        Inventory clicked = event.getClickedInventory();

        // Clicked outside both inventories: harmless on its own, but it drops whatever is on
        // the cursor, and with a menu open the cursor should always be empty anyway.
        if (clicked == null) {
            event.setCancelled(true);
            return;
        }

        // The player's own inventory, underneath one of our menus. Their items are their
        // own, so an ordinary click is left alone; anything that could move an item *into*
        // the menu is refused.
        if (!(clicked.getHolder() instanceof MenuHolder)) {
            if (movesIntoTopInventory(event)) {
                event.setCancelled(true);
            }
            return;
        }

        // A click on the menu itself. Cancel unconditionally, before reading anything else
        // about it; cancelling also restores the cursor, so nothing is put back by hand.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getSlot();
        if (slot < 0) {
            return;
        }
        menu.click(player, slot, event.getClick());
    }

    /**
     * Whether a click in the player's own inventory would put something in the menu.
     *
     * <p>Number keys and the offhand swap are listed explicitly rather than inferred: SPEC
     * 17.5 case 63 singles the number key out as a common exploit vector precisely because
     * it is the one people forget, and it does not present as a click on the menu at all.
     */
    private static boolean movesIntoTopInventory(InventoryClickEvent event) {
        return switch (event.getClick()) {
            case SHIFT_LEFT, SHIFT_RIGHT, NUMBER_KEY, SWAP_OFFHAND, DOUBLE_CLICK,
                 CONTROL_DROP, DROP -> true;
            default -> false;
        };
    }

    // ==================================================================================
    // Drags
    // ==================================================================================

    /** SPEC 17.5 case 61: a drag touching a menu is cancelled outright, never partly. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (menuOf(event.getInventory()) == null) {
            return;
        }
        // Cancelled whether or not any dragged slot is in the top inventory. A drag that
        // only touches the player's own slots is harmless, but distinguishing the two by
        // raw slot number is exactly the sort of arithmetic that turns into a dupe.
        event.setCancelled(true);
    }

    // ==================================================================================
    // Closing
    // ==================================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Menu menu = menuOf(event.getInventory());
        if (menu == null || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        // Only forget it if this player is the one it belonged to; a spectator closing a
        // window they were shown must not clear somebody else's session.
        if (!player.getUniqueId().equals(menu.viewer().getUniqueId())) {
            return;
        }
        menus.forget(player);
        menu.closed();
    }

    /** A player who logs out has no open menu, whatever the map still says. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Menu menu = menus.openMenu(event.getPlayer()).orElse(null);
        if (menu == null) {
            return;
        }
        menus.forget(event.getPlayer());
        menu.closed();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    /** @return the menu this inventory is, or null if it is not one of ours */
    private static Menu menuOf(Inventory inventory) {
        if (inventory == null || inventory.getType() == InventoryType.PLAYER) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof MenuHolder menuHolder ? menuHolder.menu() : null;
    }
}
