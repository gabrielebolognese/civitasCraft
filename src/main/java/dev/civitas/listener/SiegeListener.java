package dev.civitas.listener;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.core.siege.SiegeCamp;
import dev.civitas.core.siege.SiegeService;
import dev.civitas.core.siege.SiegeSpawner;
import dev.civitas.core.siege.SiegeTargeting;
import dev.civitas.core.siege.SiegeUnitType;
import dev.civitas.core.war.War;
import dev.civitas.core.war.WarRegistry;
import dev.civitas.core.war.WarScoring;
import dev.civitas.lang.LangManager;
import dev.civitas.msg.Channel;
import dev.civitas.msg.Messenger;
import dev.civitas.msg.ToggleCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

/**
 * Siege units and camps in the world, SPEC 29.
 *
 * <p>Four jobs, and none of them decides anything on its own: targeting asks
 * {@code SiegeTargeting}, which asks the one {@code TargetingRule}; camp damage asks
 * {@link SiegeService}; scoring asks {@link WarScoring}. What lives here is the event plumbing.
 */
public final class SiegeListener implements Listener {

    private final SiegeService siege;
    private final SiegeSpawner spawner;
    private final SiegeTargeting targeting;
    private final WarRegistry wars;
    private final WarScoring scoring;
    private final LangManager lang;
    private final Messenger messenger;

    /** Per-player debounce on camp hits, so one held mouse button is not a database flood. */
    private final Map<UUID, Long> lastHit = new ConcurrentHashMap<>();

    public SiegeListener(SiegeService siege, SiegeSpawner spawner, SiegeTargeting targeting,
                         WarRegistry wars, WarScoring scoring, LangManager lang,
                         Messenger messenger) {
        this.siege = Objects.requireNonNull(siege, "siege");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.targeting = Objects.requireNonNull(targeting, "targeting");
        this.wars = Objects.requireNonNull(wars, "wars");
        this.scoring = Objects.requireNonNull(scoring, "scoring");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.messenger = Objects.requireNonNull(messenger, "messenger");
    }

    // ==================================================================================
    // Targeting, SPEC 29.4
    // ==================================================================================

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        targeting.decide(event.getEntity(), event.getTarget()).ifPresent(decision -> {
            if (!decision.allowed()) {
                event.setCancelled(true);
                event.setTarget(null);
            }
        });
    }

    // ==================================================================================
    // The Breacher's asymmetry, SPEC 29.3
    // ==================================================================================

    /**
     * Rewrites a siege unit's damage to SPEC 29.3's per-target figure.
     *
     * <p>At {@code HIGH} rather than {@code MONITOR}: the number has to be changed before
     * anything applies it, and {@code MONITOR} is for observers. It runs after the protection
     * listeners have had their say, so a blow that was going to be cancelled still is.
     *
     * <p>The Banner Bearer's zero is honoured by cancelling outright rather than by setting zero
     * damage: a zero-damage hit still knocks a player back and still plays the hurt animation,
     * which reads as a bug in a unit whose entire description is that it does not fight.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSiegeDamage(EntityDamageByEntityEvent event) {
        Entity attacker = event.getDamager();
        Optional<SiegeUnitType> type = spawner.typeKeyOf(attacker)
                .flatMap(key -> siege.catalogue().byKey(key));
        if (type.isEmpty()) {
            return;
        }

        SiegeUnitType siegeType = type.get();
        if (siegeType.isSupport()) {
            event.setCancelled(true);
            return;
        }

        boolean againstUnit = event.getEntity() instanceof LivingEntity victim
                && defenseUnits != null && defenseUnits.test(victim);
        event.setDamage(siegeType.damageAgainst(againstUnit));
    }

    /** Whether an entity is somebody's garrison. Injected so the listener owns no lookup. */
    private java.util.function.Predicate<Entity> defenseUnits;

    public void useDefenseUnits(java.util.function.Predicate<Entity> predicate) {
        this.defenseUnits = Objects.requireNonNull(predicate, "predicate");
    }

    // ==================================================================================
    // A siege unit dying, SPEC 29.4
    // ==================================================================================

    /**
     * A dead siege unit drops nothing and is refunded nothing.
     *
     * <p>The row stays and still counts against SPEC 29.2's budget: an attacker who could replace
     * losses inside one war would have a rate limit rather than a cap.
     */
    @EventHandler
    public void onSiegeDeath(EntityDeathEvent event) {
        spawner.unitIdOf(event.getEntity()).ifPresent(id -> {
            event.getDrops().clear();
            event.setDroppedExp(0);
            siege.markUnitDead(id);
        });
    }

    // ==================================================================================
    // The camp, SPEC 29.5
    // ==================================================================================

    /**
     * A camp cannot be mined away.
     *
     * <p>SPEC 29.5 gives it 200 HP, which is a commitment of several seconds. A marker block a
     * pickaxe removes in one would make that number decorative.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCampBreak(BlockBreakEvent event) {
        campAt(event.getBlock()).ifPresent(camp -> {
            event.setCancelled(true);
            lang.send(event.getPlayer(), "siege.camp-must-be-worn-down");
        });
    }

    /** Hitting the camp is what takes it down. */
    @EventHandler(ignoreCancelled = true)
    public void onCampHit(BlockDamageEvent event) {
        Optional<SiegeCamp> found = campAt(event.getBlock());
        if (found.isEmpty()) {
            return;
        }
        SiegeCamp camp = found.get();
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();

        Optional<War> war = wars.war(camp.warId());
        if (war.isEmpty()) {
            return;
        }
        // Only the other side may knock it down. An attacker hitting their own camp, or a
        // bystander wandering past, changes nothing — the camp is a war object.
        if (!isDefendingSide(war.get(), player)) {
            return;
        }

        Long last = lastHit.get(player.getUniqueId());
        if (last != null && now - last < siege.campHitCooldownMillis()) {
            return;
        }
        lastHit.put(player.getUniqueId(), now);

        if (siege.damageCamp(camp, siege.campDamagePerHit(), now)) {
            announceDestroyed(war.get(), camp, player);
        }
    }

    private void announceDestroyed(War war, SiegeCamp camp, Player by) {
        boolean campBelongsToAttackers = war.isAttackerSide(camp.cityId());
        int points = scoring.awardCampDestroyed(war, !campBelongsToAttackers,
                siege.capacityRule().campDestroyPoints());

        String cityName = siege.cities().city(camp.cityId())
                .map(city -> city.name())
                .orElse("?");

        for (Player online : by.getServer().getOnlinePlayers()) {
            messenger.send(online.getUniqueId(), online, ToggleCategory.WAR, Channel.CHAT,
                    "siege.camp-destroyed",
                    LangManager.placeholder("cityname", cityName),
                    LangManager.placeholder("points", String.valueOf(points)));
        }
    }

    private boolean isDefendingSide(War war, Player player) {
        return siege.cities().cityOf(player.getUniqueId())
                .map(city -> war.isDefenderSide(city.id()))
                .orElse(false);
    }

    private Optional<SiegeCamp> campAt(Block block) {
        return siege.campAt(block.getWorld().getName(), block.getX() >> 4, block.getZ() >> 4)
                .filter(camp -> camp.x() == block.getX()
                        && camp.y() == block.getY()
                        && camp.z() == block.getZ());
    }
}
