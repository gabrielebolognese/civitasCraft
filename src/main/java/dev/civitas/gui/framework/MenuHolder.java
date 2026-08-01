package dev.civitas.gui.framework;

import java.util.Objects;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marks an inventory as one of ours, and says which menu it belongs to.
 *
 * <p>Identity comes from the holder rather than the title, deliberately. A title is text the
 * plugin wrote and a resource pack or a client mod can render differently, and comparing
 * titles is how plugins end up treating a player-named chest as a menu. The holder is an
 * object reference the client never sees and cannot forge.
 */
public final class MenuHolder implements InventoryHolder {

    private final Menu menu;
    private Inventory inventory;

    MenuHolder(Menu menu) {
        this.menu = Objects.requireNonNull(menu, "menu");
    }

    public Menu menu() {
        return menu;
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
