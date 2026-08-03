package dev.civitas.listener;

import java.util.Objects;

import dev.civitas.core.vault.VaultHolder;
import dev.civitas.core.vault.VaultView;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Saving a vault page when the last viewer closes it, SPEC 5.7.
 *
 * <p>The only handler the vault needs, and the reason it is this short is that the vault is
 * deliberately outside the GUI framework: it is a real container, so clicks, drags and
 * shift-moves are all supposed to work and none of them needs intercepting. M7's listener
 * never sees these inventories because they carry a {@link VaultHolder} rather than a
 * {@code MenuHolder}.
 */
public final class VaultListener implements Listener {

    private final VaultView vaults;

    public VaultListener(VaultView vaults) {
        this.vaults = Objects.requireNonNull(vaults, "vaults");
    }

    /**
     * {@link EventPriority#MONITOR}, so the contents read here are the ones the player
     * actually left behind rather than a state another plugin is still adjusting.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof VaultHolder vault) {
            vaults.onClosed(vault, event.getInventory());
        }
    }
}
