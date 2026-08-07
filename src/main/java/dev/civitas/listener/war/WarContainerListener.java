package dev.civitas.listener.war;

import java.util.List;
import java.util.Objects;

import dev.civitas.core.war.WarLootLog;
import dev.civitas.core.war.WarZones;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Watches containers inside a war zone, SPEC 11.7.
 *
 * <p>At {@code MONITOR}: this listener records what happened rather than deciding anything,
 * and by the time it runs, land protection has already had its say about whether the player
 * was allowed to open the container at all. SPEC 11.6 lets an enemy do exactly that during
 * ACTIVE, which is the case this exists to write down.
 *
 * <p>Only <b>block</b> containers count. A player's own inventory has no location and nothing
 * to steal from, and the city vault is a different holder entirely — SPEC 11.7 makes the vault
 * "completely immune to war looting", so a vault page is never even looked at here.
 */
public final class WarContainerListener implements Listener {

    private final WarLootLog loot;
    private final WarZones zones;

    public WarContainerListener(WarLootLog loot, WarZones zones) {
        this.loot = Objects.requireNonNull(loot, "loot");
        this.zones = Objects.requireNonNull(zones, "zones");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        Location at = locationOf(inventory);
        if (at == null || at.getWorld() == null) {
            return;
        }
        List<Integer> wars = zones.warsCovering(at.getWorld().getName(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ());
        loot.opened(player.getUniqueId(), wars, at, inventory);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        loot.closed(player.getUniqueId(), event.getInventory(), System.currentTimeMillis());
    }

    /**
     * A player who disconnects with a container open.
     *
     * <p>Bukkit fires a close for this, but not in every disconnect path, and a snapshot left
     * behind would be compared against the next container that player opens. Forgetting is the
     * conservative direction: an unrecorded theft costs a line in a report, a mis-attributed
     * one accuses somebody of taking things from a chest they never touched.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        loot.forget(event.getPlayer().getUniqueId());
    }

    /** Where a container physically is, or null if it is not a block. */
    private static Location locationOf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof org.bukkit.block.BlockState state) {
            return state.getLocation();
        }
        if (holder instanceof org.bukkit.block.DoubleChest doubleChest) {
            return doubleChest.getLocation();
        }
        return null;
    }
}
