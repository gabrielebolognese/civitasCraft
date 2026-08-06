package dev.civitas.listener.war;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.civitas.core.war.WarBlockRecorder;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Everything that changes a block without a player breaking or placing it, SPEC 11.8.1.
 *
 * <p>Fire, fluid, pistons, explosions, growth, decay, and the entities that rearrange the
 * world: endermen, withers, ravagers, and falling sand. These are the sources a listener list
 * is most likely to miss, and each one missed is a hole in a rollback that nobody notices
 * until a war ends.
 */
public final class WarPhysicsListener implements Listener {

    private final WarBlockRecorder recorder;

    public WarPhysicsListener(WarBlockRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    // ==================================================================================
    // Explosions, SPEC 17.4 case 45
    // ==================================================================================

    /**
     * TNT and creepers.
     *
     * <p>The event carries its whole block list, and SPEC 17.4 case 45 says to log all of it
     * in one batch with nothing throttled. A 40,000-block chain is one call.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        if (!recorder.recordAll(new ArrayList<>(event.blockList()), null)) {
            event.setCancelled(true);
        }
    }

    /** A bed in the nether, a respawn anchor, an end crystal: an explosion with no entity. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        List<Block> blocks = new ArrayList<>(event.blockList());
        blocks.add(event.getBlock());
        if (!recorder.recordAll(blocks, null)) {
            event.setCancelled(true);
        }
    }

    // ==================================================================================
    // Fire and fluid
    // ==================================================================================

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        if (!recorder.recordRemoval(event.getBlock(), null)) {
            event.setCancelled(true);
        }
    }

    /** Fire spreading, and anything else that grows into a neighbouring block. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        if (!recorder.record(event.getBlock(), event.getNewState().getType(), null)) {
            event.setCancelled(true);
        }
    }

    /**
     * Water and lava moving.
     *
     * <p>The block that is about to be flooded is the one that needs recording, not the source.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        if (!recorder.record(event.getToBlock(), event.getBlock().getType(), null)) {
            event.setCancelled(true);
        }
    }

    // ==================================================================================
    // Pistons
    // ==================================================================================

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        // Both the blocks being pushed and the spaces they are pushed into change.
        List<Block> affected = new ArrayList<>();
        for (Block block : event.getBlocks()) {
            affected.add(block);
            affected.add(block.getRelative(event.getDirection()));
        }
        if (!recorder.recordAll(affected, null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        List<Block> affected = new ArrayList<>();
        for (Block block : event.getBlocks()) {
            affected.add(block);
            affected.add(block.getRelative(event.getDirection()));
        }
        if (!recorder.recordAll(affected, null)) {
            event.setCancelled(true);
        }
    }

    // ==================================================================================
    // Growth and decay
    // ==================================================================================

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        if (!recorder.record(event.getBlock(), event.getNewState().getType(), null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        if (!recorder.recordRemoval(event.getBlock(), null)) {
            event.setCancelled(true);
        }
    }

    /** Ice melting, snow thawing, coral dying: a block quietly becoming another. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        if (!recorder.record(event.getBlock(), event.getNewState().getType(), null)) {
            event.setCancelled(true);
        }
    }

    /**
     * Entities that change blocks, SPEC 11.8.1's two rows for it.
     *
     * <p>Falling sand and gravel landing, endermen lifting a block, a wither shattering one, a
     * ravager trampling crops. All of them arrive here.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        Material becomes = event.getTo();
        java.util.UUID actor = event.getEntity() instanceof org.bukkit.entity.Player player
                ? player.getUniqueId()
                : null;
        if (!recorder.record(event.getBlock(), becomes, actor)) {
            event.setCancelled(true);
        }
    }
}
