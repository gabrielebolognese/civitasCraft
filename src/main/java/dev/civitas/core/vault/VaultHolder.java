package dev.civitas.core.vault;

import java.util.Objects;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marks an inventory as a city vault page.
 *
 * <p>Deliberately <em>not</em> the GUI framework's {@code MenuHolder}. M7's listener cancels
 * every click in anything it owns, which is exactly right for a menu and exactly wrong for a
 * container: the whole point of a vault is that items move in and out of it. Giving the vault
 * its own holder means the framework never sees it, and none of the SPEC 17.5 hardening on
 * the other twelve screens has to be weakened to let this one work.
 */
public final class VaultHolder implements InventoryHolder {

    private final int cityId;
    private final int page;
    private Inventory inventory;

    VaultHolder(int cityId, int page) {
        this.cityId = cityId;
        this.page = page;
    }

    public int cityId() {
        return cityId;
    }

    public int page() {
        return page;
    }

    void attach(Inventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
