package dev.civitas.listener;

import java.util.Objects;

import dev.civitas.core.city.SpawnService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * What cancels a {@code /city spawn} warmup, SPEC 5.6.
 *
 * <p>Moving and taking damage, which together are what stop the teleport being an escape
 * button (SPEC 17.6 case 77). Looking around does not count: the check is on position, so a
 * player can turn to see who is hitting them without losing the countdown.
 */
public final class TeleportWarmupListener implements Listener {

    private final SpawnService spawns;

    public TeleportWarmupListener(SpawnService spawns) {
        this.spawns = Objects.requireNonNull(spawns, "spawns");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!spawns.isWarmingUp(event.getPlayer())) {
            return;
        }
        if (spawns.hasMovedAway(event.getPlayer(), event.getTo())) {
            spawns.cancel(event.getPlayer(), "city.spawn.cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        spawns.cancel(player, "city.spawn.cancelled-damage");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        spawns.forget(event.getPlayer().getUniqueId());
    }
}
