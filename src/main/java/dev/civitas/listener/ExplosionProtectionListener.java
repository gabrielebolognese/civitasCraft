package dev.civitas.listener;

import java.util.Objects;

import dev.civitas.core.claim.ChunkKey;
import dev.civitas.core.protection.ProtectionService;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * TNT, creepers and end crystals, SPEC 5.5: "fully disabled outside war".
 *
 * <p>Blocks are filtered out of the explosion rather than the whole event cancelled. An
 * explosion straddling a border should still flatten the wilderness half; cancelling it
 * outright would let a city's edge act as a shield for the land outside it.
 *
 * <p>No player is involved in an explosion, so there is nobody to check a bypass permission
 * against and nobody to send a message to. The question is only whether the chunk is
 * protected.
 */
public final class ExplosionProtectionListener implements Listener {

    private final ProtectionService protection;

    public ExplosionProtectionListener(ProtectionService protection) {
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filter(event.blockList(), event.getLocation().getWorld().getName());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filter(event.blockList(), event.getBlock().getWorld().getName());
    }

    /**
     * Removes protected blocks from the list the explosion is about to destroy.
     *
     * <p>Iterating with an explicit iterator because the list is mutable and Bukkit reads it
     * back after the event; SPEC 17.4 case 45 has a single explosion reaching 40,000 blocks,
     * so this is one O(1) lookup per block and no allocation.
     */
    private void filter(java.util.List<Block> blocks, String world) {
        blocks.removeIf(block -> !protection.allowsExplosionAt(
                world,
                ChunkKey.toChunk(block.getX()),
                ChunkKey.toChunk(block.getZ())));
    }
}
