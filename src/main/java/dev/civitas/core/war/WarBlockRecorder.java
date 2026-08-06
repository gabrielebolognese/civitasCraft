package dev.civitas.core.war;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

/**
 * What the listeners call. One place where "should this be logged" and "capture it" meet.
 *
 * <h2>Capture happens before the change</h2>
 * Every method here reads the block as it is <em>now</em> and stores that as the state to
 * restore. So every listener must run before the change lands, which means normal priority
 * with {@code ignoreCancelled}, not {@code MONITOR}: at MONITOR the block has already changed
 * and the log would record the new state as the old one, which is the single most damaging
 * mistake this subsystem can make. It would produce a rollback that restores rubble.
 */
public final class WarBlockRecorder {

    /**
     * Marks a row as a hanging entity rather than a block, SPEC 11.8.1's item frame and armor
     * stand row. SPEC 3.8's table is block-shaped, so an entity is stored at the block it
     * occupies with this in {@code old_block_data} and its detail in the payload. Recorded in
     * OPEN_QUESTIONS.md.
     */
    public static final String HANGING_MARKER = "civitas:hanging";

    private final WarZones zones;
    private final WarBlockLogger log;

    public WarBlockRecorder(WarZones zones, WarBlockLogger log) {
        this.zones = Objects.requireNonNull(zones, "zones");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** Whether any war is logging at all. The first line of every listener. */
    public boolean isActive() {
        return zones.isAnyWarActive();
    }

    public WarBlockLogger log() {
        return log;
    }

    /**
     * Records one block about to become something else.
     *
     * @param newData what it is becoming, or null when it is being removed
     * @return false if the change cannot be logged and the event must therefore be cancelled
     */
    public boolean record(Block block, Material newType, UUID actor) {
        if (block == null) {
            return true;
        }
        List<Integer> wars = zones.warsCovering(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (wars.isEmpty()) {
            return true;
        }

        BlockState state = block.getState();
        String oldData = block.getBlockData().getAsString();
        String newDataString = newType == null ? null : newType.createBlockData().getAsString();
        byte[] payload = log.codec().capture(state);
        long now = System.currentTimeMillis();

        // SPEC 17.4 case 51: a chunk inside two war zones logs to both, or the war that missed
        // it would roll back to a state the other war's damage had already left.
        for (int warId : wars) {
            if (!log.record(warId, block.getWorld().getName(), block.getX(), block.getY(),
                    block.getZ(), oldData, newDataString, payload, actor, now)) {
                return false;
            }
        }
        return true;
    }

    /** Records a block being removed. */
    public boolean recordRemoval(Block block, UUID actor) {
        return record(block, Material.AIR, actor);
    }

    /**
     * Records a whole list in one go, for an explosion.
     *
     * <p>SPEC 17.4 case 45 gives the reasoning: an {@code EntityExplodeEvent} hands over its
     * full block list, and all of it is logged in one batch with nothing throttled.
     *
     * @return false if any block could not be logged, in which case the caller cancels
     */
    public boolean recordAll(List<Block> blocks, UUID actor) {
        List<WarBlockLogger.PendingChange> pending = new ArrayList<>(blocks.size());
        long now = System.currentTimeMillis();

        for (Block block : blocks) {
            List<Integer> wars = zones.warsCovering(block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ());
            if (wars.isEmpty()) {
                continue;
            }
            String oldData = block.getBlockData().getAsString();
            byte[] payload = log.codec().capture(block.getState());
            String air = Material.AIR.createBlockData().getAsString();
            for (int warId : wars) {
                pending.add(new WarBlockLogger.PendingChange(warId,
                        block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                        oldData, air, payload, actor, now));
            }
        }

        if (pending.isEmpty()) {
            return true;
        }
        return log.recordAll(pending) == pending.size();
    }

    /**
     * Records a hanging entity, SPEC 11.8.1's last listed source.
     *
     * @param payload the entity's detail, from the caller
     */
    public boolean recordHanging(org.bukkit.Location at, byte[] payload, UUID actor) {
        List<Integer> wars = zones.warsCovering(at.getWorld().getName(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ());
        if (wars.isEmpty()) {
            return true;
        }
        long now = System.currentTimeMillis();
        for (int warId : wars) {
            if (!log.record(warId, at.getWorld().getName(), at.getBlockX(), at.getBlockY(),
                    at.getBlockZ(), HANGING_MARKER, null, payload, actor, now)) {
                return false;
            }
        }
        return true;
    }
}
