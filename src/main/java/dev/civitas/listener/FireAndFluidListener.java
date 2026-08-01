package dev.civitas.listener;

import java.util.Objects;

import dev.civitas.core.claim.ChunkKey;
import dev.civitas.core.protection.ProtectionAction;
import dev.civitas.core.protection.ProtectionGuard;
import dev.civitas.core.protection.ProtectionService;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;

/**
 * Fire and fluid crossing claim borders, SPEC 5.5.
 *
 * <p>"Fire spread and lava flow across claim borders" and "Fluid flow across claim boundaries
 * into a foreign claim". The rule in both cases is that the source and the destination must
 * answer to the same owner: a city may set its own land on fire, and wilderness may burn
 * freely, but neither may reach across a border.
 */
public final class FireAndFluidListener implements Listener {

    private final ProtectionService protection;
    private final ProtectionGuard guard;

    public FireAndFluidListener(ProtectionService protection, ProtectionGuard guard) {
        this.protection = Objects.requireNonNull(protection, "protection");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /**
     * Water and lava moving from one block to the next.
     *
     * <p>The hottest event in this file by a wide margin: a single ocean edge fires it
     * continuously. The same-chunk case is answered by an integer comparison before any
     * lookup happens.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (!crossesChunks(event.getBlock(), event.getToBlock())) {
            return;
        }
        if (!allowsBetween(event.getBlock(), event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    /** Fire spreading to a neighbouring block. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (!crossesChunks(event.getSource(), event.getBlock())) {
            return;
        }
        if (!allowsBetween(event.getSource(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** A block being consumed by fire. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        Block burning = event.getBlock();
        Block source = event.getIgnitingBlock();
        if (source == null) {
            return;
        }
        if (!crossesChunks(source, burning)) {
            return;
        }
        if (!allowsBetween(source, burning)) {
            event.setCancelled(true);
        }
    }

    /**
     * A player setting fire to something.
     *
     * <p>Not a spread rule but a build one: striking flint and steel inside someone's claim
     * is placing a fire block there.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        if (!guard.allows(event.getPlayer(), event.getBlock().getLocation(),
                ProtectionAction.BUILD)) {
            event.setCancelled(true);
        }
    }

    private static boolean crossesChunks(Block from, Block to) {
        return ChunkKey.toChunk(from.getX()) != ChunkKey.toChunk(to.getX())
                || ChunkKey.toChunk(from.getZ()) != ChunkKey.toChunk(to.getZ());
    }

    private boolean allowsBetween(Block from, Block to) {
        return protection.allowsSpreadBetween(
                from.getWorld().getName(),
                ChunkKey.toChunk(from.getX()), ChunkKey.toChunk(from.getZ()),
                ChunkKey.toChunk(to.getX()), ChunkKey.toChunk(to.getZ()));
    }
}
