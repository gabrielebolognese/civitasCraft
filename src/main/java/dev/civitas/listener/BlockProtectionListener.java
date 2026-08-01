package dev.civitas.listener;

import java.util.Objects;

import dev.civitas.core.protection.ProtectionAction;
import dev.civitas.core.protection.ProtectionGuard;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

/**
 * Block break, block place and bucket use, SPEC 5.5.
 *
 * <p>Registered at {@link EventPriority#LOW} so a plugin that wants the final word still has
 * it, and with {@code ignoreCancelled} so work is not repeated for an event something else
 * has already refused.
 */
public final class BlockProtectionListener implements Listener {

    private final ProtectionGuard guard;

    public BlockProtectionListener(ProtectionGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!guard.allows(event.getPlayer(), event.getBlock().getLocation(),
                ProtectionAction.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!guard.allows(event.getPlayer(), event.getBlock().getLocation(),
                ProtectionAction.BUILD)) {
            event.setCancelled(true);
        }
    }

    /**
     * A bed, a door or a tall flower places two blocks at once.
     *
     * <p>Checked separately because {@link BlockPlaceEvent} reports only one of them, and the
     * other half can land in the next chunk along, which may belong to someone else.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        for (var state : event.getReplacedBlockStates()) {
            if (!guard.allows(event.getPlayer(), state.getLocation(), ProtectionAction.BUILD)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Emptying a bucket.
     *
     * <p>The block that changes is the one the player clicked <em>against</em>, not the one
     * they clicked, so both are checked: pouring lava from outside a claim onto its edge
     * would otherwise be free.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block clicked = event.getBlockClicked();
        Block target = clicked.getRelative(event.getBlockFace());

        if (!guard.allows(event.getPlayer(), target.getLocation(), ProtectionAction.BUCKET)
                || !guard.allowsSilently(event.getPlayer(), clicked.getLocation(),
                        ProtectionAction.BUCKET)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!guard.allows(event.getPlayer(), event.getBlockClicked().getLocation(),
                ProtectionAction.BUCKET)) {
            event.setCancelled(true);
        }
    }
}
