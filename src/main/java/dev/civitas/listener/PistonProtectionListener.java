package dev.civitas.listener;

import java.util.List;
import java.util.Objects;

import dev.civitas.core.claim.ChunkKey;
import dev.civitas.core.protection.ProtectionService;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

/**
 * Pistons crossing claim boundaries, SPEC 5.5.
 *
 * <p>SPEC blocks this "entirely, prevents grief and dupe vectors", and the second half is
 * why the rule is stricter than the others: a piston that can move a block across a boundary
 * can also move it out of the world's tracked state, which is how most container duplication
 * exploits begin. So this does not ask who owns what or whether anyone is a member; it asks
 * only whether the piston, every block it moves, and every block's destination all answer to
 * the same owner.
 */
public final class PistonProtectionListener implements Listener {

    private final ProtectionService protection;

    public PistonProtectionListener(ProtectionService protection) {
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent event) {
        if (crossesABoundary(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent event) {
        if (crossesABoundary(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether any part of this piston action spans two differently-owned chunks.
     *
     * <p>Checks the piston against each moved block, and each moved block against where it
     * lands. The head itself matters: a piston sitting just outside a claim pushing into it
     * is exactly the case the rule exists for.
     */
    private boolean crossesABoundary(Block piston, List<Block> moved, BlockFace direction) {
        String world = piston.getWorld().getName();
        int pistonX = ChunkKey.toChunk(piston.getX());
        int pistonZ = ChunkKey.toChunk(piston.getZ());

        for (Block block : moved) {
            int fromX = ChunkKey.toChunk(block.getX());
            int fromZ = ChunkKey.toChunk(block.getZ());

            if (!protection.allowsPistonBetween(world, pistonX, pistonZ, fromX, fromZ)) {
                return true;
            }

            Block destination = block.getRelative(direction);
            int toX = ChunkKey.toChunk(destination.getX());
            int toZ = ChunkKey.toChunk(destination.getZ());

            if (!protection.allowsPistonBetween(world, fromX, fromZ, toX, toZ)) {
                return true;
            }
        }
        return false;
    }
}
