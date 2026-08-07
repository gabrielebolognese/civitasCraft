package dev.civitas.listener.war;

import java.util.Objects;

import dev.civitas.core.war.WarEntitySnapshots;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * Notes when a snapshotted animal or villager dies, SPEC 11.8.3.
 *
 * <p>At {@code MONITOR}: this records what happened and decides nothing. The check is a hash
 * lookup on the entity's id and nothing else, because this fires for every mob death on the
 * server — every zombie burning at dawn, every cow a player butchers a thousand blocks from any
 * war — and a war zone must not make the rest of the world slower.
 */
public final class WarEntityListener implements Listener {

    private final WarEntitySnapshots snapshots;

    public WarEntityListener(WarEntitySnapshots snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        snapshots.died(event.getEntity().getUniqueId(), System.currentTimeMillis());
    }
}
