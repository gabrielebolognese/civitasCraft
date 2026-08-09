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

    /**
     * SPEC 33's PvP policy, the single authority on player-versus-player damage.
     *
     * <p>Required, not optional. An optional one would mean two rules — the policy when it is
     * present and {@code ProtectionService}'s claim rule when it is not — and two rules that
     * answer the same question are two rules that will come to disagree. It was optional for
     * about ten minutes during M4a, until a check found that nothing constructed this listener
     * without it.
     */
    private final dev.civitas.core.combat.PvpPolicy pvp;

    /**
     * Whether an entity is one of a city's defense units, for SPEC 26.2's violation list.
     *
     * <p>A seam rather than a dependency on the defense package, because protection is wired
     * long before defense is and must work on a server where defense is switched off entirely.
     * Answers false until M12c's wiring hands over the real test.
     */
    private java.util.function.Predicate<Entity> defenseUnits = entity -> false;

    public EntityProtectionListener(ProtectionGuard guard,
                                    dev.civitas.core.combat.PvpPolicy pvp) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.pvp = Objects.requireNonNull(pvp, "pvp");
    }

    public void useDefenseUnits(java.util.function.Predicate<Entity> test) {
        this.defenseUnits = Objects.requireNonNull(test, "test");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Optional<Player> attacker = attackerOf(event.getDamager());
        if (attacker.isEmpty()) {
            return;
        }
        Entity victim = event.getEntity();

        if (victim instanceof Player hurt) {
            // SPEC 33 replaces Part I 5.5 and 11.6 in full, so the policy decides everywhere
            // rather than the claim rule deciding inside claims and vanilla deciding outside.
            // Judged at the VICTIM's location, per SPEC 33.9 case 117, which also settles case
            // 118's splash potion thrown from wilderness into a claim.
            var chunk = hurt.getLocation().getChunk();
            if (pvp.check(attacker.get().getUniqueId(), hurt.getUniqueId(),
                    hurt.getWorld().getName(), chunk.getX(), chunk.getZ(),
                    System.currentTimeMillis()).denied()) {
                event.setCancelled(true);
                reportMemberHurt(attacker.get(), hurt);
            }
            return;
        }

        // SPEC 26.2 lists "Damaging a defense unit" as a violation, and nothing else here
        // would catch it: most of SPEC 27's roster are Zombies and Skeletons, so the Enemy
        // carve-out below lets them be hit without ever asking the guard. Reported and not
        // blocked, deliberately — SPEC 25.2's Rule 3 requires every unit to have a stated
        // counterplay, and a guard that cannot be hit has none.
        if (defenseUnits.test(victim)) {
            guard.reportDirectViolation(attacker.get(), victim.getLocation());
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
     * SPEC 26.2's "damaging a city member", which no protection check produces.
     *
     * <p>PvP is decided by {@link dev.civitas.core.combat.PvpPolicy} in a branch that returns
     * before the guard is ever asked, so a swing at a citizen inside their own city generates
     * nothing. Reported only when the person hit is a member of the city whose land they are
     * standing on: SPEC 26.2 says "damaging a city member", and a scuffle between two
     * outsiders in somebody's streets is not that.
     */
    private void reportMemberHurt(Player attacker, Player hurt) {
        var at = hurt.getLocation();
        if (at.getWorld() == null) {
            return;
        }
        guard.service().cityAt(at.getWorld().getName(), at.getBlockX() >> 4, at.getBlockZ() >> 4)
                .filter(city -> city.isMember(hurt.getUniqueId()))
                .ifPresent(city -> guard.reportDirectViolation(attacker, at));
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
