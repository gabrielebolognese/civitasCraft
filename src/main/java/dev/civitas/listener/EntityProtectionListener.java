package dev.civitas.listener;

import java.util.Objects;
import java.util.Optional;

import dev.civitas.core.protection.ProtectionAction;
import dev.civitas.core.protection.ProtectionGuard;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Entity damage inside claims, SPEC 5.5.
 *
 * <p>Two rules meet here. A player attacking another player is PvP, which SPEC 5.5 disables
 * outside war whatever the ranks say. A player attacking anything else is treated as damage
 * to the city's property and gated on BUILD, <em>except</em> hostile mobs: a visitor who
 * cannot kill the zombie chasing them would find the world unplayable, and SPEC 5.5 carves
 * them out explicitly.
 */
public final class EntityProtectionListener implements Listener {

    private final ProtectionGuard guard;

    public EntityProtectionListener(ProtectionGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Optional<Player> attacker = attackerOf(event.getDamager());
        if (attacker.isEmpty()) {
            return;
        }
        Entity victim = event.getEntity();

        if (victim instanceof Player) {
            if (!guard.allows(attacker.get(), victim.getLocation(), ProtectionAction.PVP)) {
                event.setCancelled(true);
            }
            return;
        }

        // SPEC 5.5: "except hostile mobs". Enemy covers monsters, slimes, ghasts, phantoms
        // and whatever else a future version decides is hostile, which a hand-written list
        // would not.
        if (victim instanceof Enemy) {
            return;
        }

        // A player's own tamed animal is theirs to hit wherever it stands.
        if (victim instanceof Tameable tameable
                && attacker.get().getUniqueId().equals(ownerOf(tameable))) {
            return;
        }

        if (!guard.allows(attacker.get(), victim.getLocation(), ProtectionAction.ENTITY_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    /**
     * The player behind an attack.
     *
     * <p>Follows a projectile back to whoever fired it, so a bow does not become a way around
     * every rule in this file.
     */
    private static Optional<Player> attackerOf(Entity damager) {
        if (damager instanceof Player player) {
            return Optional.of(player);
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return Optional.of(player);
            }
        }
        return Optional.empty();
    }

    private static java.util.UUID ownerOf(Tameable tameable) {
        return tameable.getOwner() == null ? null : tameable.getOwner().getUniqueId();
    }
}
