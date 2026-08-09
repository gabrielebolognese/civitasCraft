package dev.civitas.listener;

import java.util.Objects;
import java.util.Optional;

import dev.civitas.core.defense.CityWarden;
import dev.civitas.core.defense.DefenseRegistry;
import dev.civitas.core.defense.DefenseSpawner;
import dev.civitas.core.defense.WardenService;
import dev.civitas.core.defense.WardenSuppression;
import io.papermc.paper.event.entity.WardenAngerChangeEvent;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;

/**
 * SPEC 28.8's four suppressions, and SPEC 28.6's peacetime defeat.
 *
 * <p>Every one of these is event-shaped, which is the reason they are here rather than on a tick:
 * SPEC 31 requires the sonic boom and the vibration anger to be "disabled <b>and verified</b>", and
 * an event is a thing a test can fire and then assert about.
 */
public final class WardenListener implements Listener {

    private final DefenseSpawner spawner;
    private final DefenseRegistry units;
    private final WardenService wardens;
    private final WardenSuppression suppression;

    public WardenListener(DefenseSpawner spawner, DefenseRegistry units, WardenService wardens,
                          WardenSuppression suppression) {
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.units = Objects.requireNonNull(units, "units");
        this.wardens = Objects.requireNonNull(wardens, "wardens");
        this.suppression = Objects.requireNonNull(suppression, "suppression");
    }

    // ==================================================================================
    // SPEC 28.3 and 28.8, the sonic boom
    // ==================================================================================

    /**
     * "Sonic boom: disabled entirely."
     *
     * <p>SPEC 28.3 gives the reason in its own table: the vanilla boom is 10 to 15 damage that
     * "ignores armor and shields", and "unblockable, uncounterable ranged damage has no place in a
     * defense unit". It is also the one attack that would make the SPEC 28.4 tuning table
     * meaningless, because armour is exactly what that table varies.
     *
     * <p>Only for Wardens this plugin owns. SPEC 28.8's word is "unconditionally", but read against
     * the whole server that would silently rewrite deep-dark gameplay: a wild Warden in an ancient
     * city has nothing to do with a city's defenses, and a player who beat one and took no damage
     * would be looking at a bug.
     *
     * <p>What this does <b>not</b> do is stop the windup. SPEC 28.8 asks for the goal to be removed
     * "so the animation never plays", and a Warden is a brain mob with no goals to remove — see
     * {@link WardenSuppression} for the full finding. The damage is gone; the animation is not.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSonicBoom(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.SONIC_BOOM) {
            return;
        }
        if (isOurWarden(event.getDamager())) {
            event.setCancelled(true);
        }
    }

    // ==================================================================================
    // SPEC 28.8, vibration anger
    // ==================================================================================

    /**
     * "Clear anger every tick, and drive targeting <b>exclusively</b> from the plugin."
     *
     * <p>This is the entry point the vibration system uses, so cancelling it here is what keeps the
     * anger map empty of anything the plugin did not put there — and the anger map is a Warden's
     * target. SPEC 28.8 states the failure it prevents: "the Warden will aggro on a member walking
     * past", which for a landmark a city spent 2.75 million coins on is the worst outcome available.
     *
     * <p>The plugin's own {@code setAnger} fires this same event, which is the trap:
     * cancelling unconditionally would leave the Warden permanently calm and the bug would present
     * as "the Warden does not attack" rather than as a cancelled event. {@link
     * WardenSuppression#isDriving} is how the two are told apart.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnger(WardenAngerChangeEvent event) {
        Warden warden = event.getEntity();
        if (!isOurWarden(warden) || suppression.isDriving(warden.getUniqueId())) {
            return;
        }
        if (event.getNewAnger() > event.getOldAnger()) {
            event.setCancelled(true);
        }
    }

    // ==================================================================================
    // SPEC 28.3 and 28.8, the darkness aura
    // ==================================================================================

    /**
     * "Darkness is applied by the plugin to specific players, not by the Warden's aura. Remove the
     * vanilla aura."
     *
     * <p>Vanilla blinds everything within twenty blocks whenever a Warden exists. SPEC 28.3 cuts
     * that to ten blocks and to the ALERTED state only, and gives the reason: "atmosphere without
     * blinding peaceful visitors". SPEC 25.2 Rule 2 makes that a shipping constraint rather than a
     * nicety, because SPEC 13.4 sends players to walk around other cities and vote on their builds.
     *
     * <p>The event names no source, so the aura is attributed by proximity: cancelled when one of
     * this plugin's Wardens is within the vanilla aura's own reach. A wild Warden in an ancient
     * city keeps its darkness, which is the behaviour a player expects there.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDarknessAura(EntityPotionEffectEvent event) {
        if (event.getCause() != EntityPotionEffectEvent.Cause.WARDEN) {
            return;
        }
        if (ourWardenNear(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** The vanilla aura's own radius, which is what makes proximity a fair attribution. */
    private static final double VANILLA_AURA_BLOCKS = 20;

    private boolean ourWardenNear(Entity around) {
        for (Entity nearby : around.getNearbyEntities(VANILLA_AURA_BLOCKS,
                VANILLA_AURA_BLOCKS, VANILLA_AURA_BLOCKS)) {
            if (isOurWarden(nearby)) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================================
    // SPEC 28.6, the peacetime defeat
    // ==================================================================================

    /**
     * "Outside a war, the City Warden cannot be permanently killed."
     *
     * <p>Intercepted on damage rather than on death, and the difference is the whole feature. By
     * the time {@code EntityDeathEvent} fires the entity is gone, no animation can play, and
     * {@code DefenseListener.onDeath} — which runs at MONITOR and deletes the row of any unit —
     * has already thrown away the 750,000 C asset. So the killing blow is cancelled instead: the
     * health it would have gone to zero at is what {@link WardenService#defeated} writes on the
     * way down, and SPEC 28.6's six hours start from here.
     *
     * <p>In an ACTIVE war this does nothing at all, and the ordinary death path takes it. SPEC 28.6
     * is explicit that a war kill is permanent, and SPEC 30.2 case 97 extends that to the war's
     * final day, "which is the correct stake".
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living) || !isOurWarden(living)) {
            return;
        }
        if (event.getFinalDamage() < living.getHealth()) {
            return;
        }
        Optional<CityWarden.Owned> owned = spawner.unitIdOf(living)
                .flatMap(id -> wardens.registry().byUnit(id));
        if (owned.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        // Asked before the event is touched: in a war this must fall through untouched, so that
        // the ordinary death handler deletes the row and the city has to buy another.
        if (!wardens.diesPermanentlyFor(owned.get().cityId())) {
            event.setCancelled(true);
            // Full again on the surface in six hours; what is written down is where it fell, and
            // clearing it here would be a heal the attacker earned nothing for.
            burrow(living);
        }
        wardens.defeated(owned.get(), now, killerOf(event));
    }

    /**
     * Who to name in SPEC 30.4's {@code warden.defeated_peacetime}, or nobody.
     *
     * <p>SPEC's template assumes a player did it. In peacetime a creeper, a lava flow or the void
     * are all at least as likely, and "driven underground by ?" is worse than a message that
     * simply does not name anyone — so a nameless death gets its own wording instead.
     */
    private static String killerOf(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof org.bukkit.entity.Player player) {
            return player.getName();
        }
        return null;
    }

    /**
     * The burrow SPEC 28.7 asks for, as far as the API allows.
     *
     * <p>{@code Pose.DIGGING} is the Warden's own animation for going under, which is the rare case
     * where the vanilla mob already contains exactly the behaviour needed — SPEC 28.7 says so in as
     * many words. It is set as a <b>fixed</b> pose so the client keeps showing it for the moment
     * before the entity is removed, and the entity is removed by the materializer a tick later.
     */
    private static void burrow(LivingEntity living) {
        var attribute = living.getAttribute(Attribute.MAX_HEALTH);
        // Off zero, or the very next damage tick kills it before the dematerialise lands.
        living.setHealth(Math.min(1.0, attribute == null ? 1.0 : attribute.getValue()));
        living.setPose(org.bukkit.entity.Pose.DIGGING, true);
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    /**
     * Whether this entity is a Warden this plugin placed.
     *
     * <p>The persistent-data stamp first, because it is the cheap answer and because a mob without
     * one is nobody's defense unit — and then the registry, so a Warden entity left over from a
     * city that no longer owns one is not treated as a live asset.
     */
    private boolean isOurWarden(Entity entity) {
        if (!(entity instanceof Warden)) {
            return false;
        }
        return spawner.unitIdOf(entity)
                .filter(id -> units.byId(id)
                        .filter(unit -> CityWarden.TYPE_KEY.equals(unit.type())).isPresent())
                .isPresent();
    }
}
