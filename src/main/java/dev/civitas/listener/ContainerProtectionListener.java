package dev.civitas.listener;

import java.util.Objects;

import dev.civitas.core.protection.BlockClassifier;
import dev.civitas.core.protection.ProtectionAction;
import dev.civitas.core.protection.ProtectionGuard;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;

/**
 * Container access, SPEC 5.5, including the part of SPEC 5.4 that most implementations skip.
 *
 * <p>{@code CONTAINER_READONLY} is defined as "open but not remove items". Allowing the open
 * and stopping there would make it identical to full access, so every click that would take
 * something out is checked separately against {@code CONTAINER}.
 */
public final class ContainerProtectionListener implements Listener {

    private final ProtectionGuard guard;
    private final BlockClassifier blocks;

    public ContainerProtectionListener(ProtectionGuard guard, BlockClassifier blocks) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
    }

    /** Opening a container. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onOpen(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !blocks.isContainer(block.getType())) {
            return;
        }
        if (!guard.allows(event.getPlayer(), block.getLocation(),
                ProtectionAction.CONTAINER_OPEN)) {
            // Deny the block, not the whole event: the player may still use whatever is in
            // their hand, which cancelling outright would also stop.
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    /**
     * Taking from a container the player may only look inside.
     *
     * <p>Fires for every click in an open container, so the cheap checks come first: a click
     * in the player's own inventory, or one that removes nothing, returns before any lookup.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!removesFromTopInventory(event)) {
            return;
        }
        Location location = containerLocation(event.getView().getTopInventory());
        if (location == null) {
            return;
        }
        if (!guard.allows(player, location, ProtectionAction.CONTAINER_TAKE)) {
            event.setCancelled(true);
        }
    }

    /**
     * Dragging across a container's slots.
     *
     * <p>A drag only ever deposits, so read-only access permits it; it is checked against the
     * open flag purely so that a player who has lost access mid-drag cannot finish it.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        boolean touchesTop = event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize());
        if (!touchesTop) {
            return;
        }
        Location location = containerLocation(top);
        if (location != null && !guard.allows(player, location, ProtectionAction.CONTAINER_OPEN)) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether this click would take something <em>out</em> of the container.
     *
     * <p>SPEC 5.4 says read-only means "open but not remove items", so depositing is left
     * alone. Every action below either pulls from the top inventory or, in the case of a
     * double-click collect, can sweep it.
     */
    private static boolean removesFromTopInventory(InventoryClickEvent event) {
        InventoryAction action = event.getAction();
        if (action == InventoryAction.NOTHING) {
            return false;
        }

        // A double-click gathers matching items from everywhere, including the container,
        // whichever slot was clicked.
        if (action == InventoryAction.COLLECT_TO_CURSOR) {
            return true;
        }

        Inventory clicked = event.getClickedInventory();
        Inventory top = event.getView().getTopInventory();

        if (clicked == null || !clicked.equals(top)) {
            // Clicked in their own inventory. Only a shift-click moving something *into* the
            // container is possible from there, which is a deposit.
            return false;
        }

        return switch (action) {
            // Every way of getting an item out of the clicked slot.
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE,
                 MOVE_TO_OTHER_INVENTORY,
                 HOTBAR_SWAP, HOTBAR_MOVE_AND_READD,
                 SWAP_WITH_CURSOR,
                 DROP_ONE_SLOT, DROP_ALL_SLOT,
                 CLONE_STACK -> true;
            // PLACE_* and NOTHING deposit or do nothing, which read-only allows.
            default -> false;
        };
    }

    /** Where a container inventory lives, or null if it has no block behind it. */
    private static Location containerLocation(Inventory inventory) {
        return inventory == null ? null : inventory.getLocation();
    }
}
