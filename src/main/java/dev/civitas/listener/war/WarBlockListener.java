package dev.civitas.listener.war;

import java.util.Objects;

import dev.civitas.core.war.WarBlockRecorder;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * The player-driven sources of SPEC 11.8.1: breaks, places, buckets, signs, trampling.
 *
 * <h2>Priority</h2>
 * {@link EventPriority#NORMAL} with {@code ignoreCancelled}, deliberately not MONITOR. The log
 * records the state a block is being changed <em>from</em>, so it has to read the block before
 * the change lands. At MONITOR it would read the new state and store it as the old one, and
 * the rollback would faithfully restore the rubble.
 *
 * <p>Every handler cancels its event when the change cannot be logged. SPEC 17.4 case 58 is
 * explicit about that trade: "Correctness over gameplay."
 */
public final class WarBlockListener implements Listener {

    private final WarBlockRecorder recorder;

    public WarBlockListener(WarBlockRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        if (!recorder.recordRemoval(event.getBlock(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        // The block replaced, not the block placed: rollback needs what was there before.
        if (!recorder.record(event.getBlockReplacedState().getBlock(),
                event.getBlockPlaced().getType(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** A bed, a door, a tall flower: one action, several blocks. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        for (BlockState replaced : event.getReplacedBlockStates()) {
            if (!recorder.record(replaced.getBlock(), event.getBlockPlaced().getType(),
                    event.getPlayer().getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        if (!recorder.record(target, event.getBucket(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        if (!recorder.recordRemoval(event.getBlock(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * A sign's text changing.
     *
     * <p>The block itself does not change, so this records the sign as it reads now with its
     * own type as the new state: what rollback has to put back is the text, which lives in the
     * payload.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!recorder.isActive()) {
            return;
        }
        Block block = event.getBlock();
        if (!recorder.record(block, block.getType(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Farmland trampled into dirt, SPEC 11.8.1's "farmland trample" row.
     *
     * <p>Only the physical interaction on farmland, which is the one that destroys a crop
     * field by walking over it.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent event) {
        if (!recorder.isActive()
                || event.getAction() != org.bukkit.event.block.Action.PHYSICAL
                || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.FARMLAND) {
            return;
        }
        if (!recorder.record(event.getClickedBlock(), Material.DIRT,
                event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
