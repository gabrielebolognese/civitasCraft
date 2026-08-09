package dev.civitas.listener;

import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.defense.DefenseBehaviour;
import dev.civitas.core.defense.DefenseService;
import dev.civitas.core.defense.DefenseUnit;
import dev.civitas.core.defense.DefenseUnitType;
import dev.civitas.command.Replies;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Result;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Defense units in the world, SPEC 12.3 to 12.5.
 *
 * <p>Placement from an egg, death, the SPEC 12.3 targeting table, and the SPEC 12.5
 * persistence rules. The decisions all live in {@link DefenseBehaviour}; this class is the
 * wiring that asks it.
 */
public final class DefenseListener implements Listener {

    private final org.bukkit.plugin.Plugin plugin;
    private final DefenseService defense;
    private final DefenseBehaviour behaviour;
    private final CityRegistry cities;
    private final LangManager lang;
    private final Logger logger;

    public DefenseListener(org.bukkit.plugin.Plugin plugin, DefenseService defense,
                           DefenseBehaviour behaviour, CityRegistry cities, LangManager lang,
                           Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.defense = Objects.requireNonNull(defense, "defense");
        this.behaviour = Objects.requireNonNull(behaviour, "behaviour");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // Placing, SPEC 12.4
    // ==================================================================================

    /**
     * Placing a purchased egg.
     *
     * <p>Cancelled unconditionally once the egg is recognised, so the vanilla spawn never
     * happens: a plain zombie appearing next to the guard would be somebody else's problem to
     * explain.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceEgg(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }
        ItemStack held = event.getItem();
        Optional<DefenseService.EggStamp> stamp = defense.readEgg(held);
        if (stamp.isEmpty()) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        Optional<City> city = cities.city(stamp.get().cityId());
        if (city.isEmpty() || !city.get().isMember(player.getUniqueId())) {
            lang.send(player, "defense.egg-not-yours");
            return;
        }
        Optional<DefenseUnitType> type = defense.catalogue().byKey(stamp.get().typeKey());
        if (type.isEmpty()) {
            lang.send(player, "defense.unknown-unit");
            return;
        }

        var at = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation()
                .add(0.5, 0, 0.5);

        defense.place(player.getUniqueId(), city.get(), type.get(),
                        at.getWorld().getName(), at.getX(), at.getY(), at.getZ())
                .whenComplete((result, error) -> org.bukkit.Bukkit.getScheduler()
                        .runTask(plugin, () -> {
                            if (error != null) {
                                lang.send(player, "command.error");
                                return;
                            }
                            if (result instanceof Result.Failure<DefenseUnit> failure) {
                                Replies.sendFailure(player, lang, failure);
                                return;
                            }
                            consumeOne(player, held);
                            lang.send(player, "defense.placed",
                                    LangManager.placeholder("unit", type.get().displayName()));
                        }));
    }

    private static void consumeOne(Player player, ItemStack held) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return;
        }
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held.getAmount() > 0 ? held : null);
    }

    // ==================================================================================
    // Dying, SPEC 12.3
    // ==================================================================================

    /**
     * A unit that dies is gone for good.
     *
     * <p>Drops are cleared as well as the row: a Siege Golem dropping iron would make killing
     * a city's defenses profitable, which SPEC 12.1 rules out by calling units consumed
     * resources.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Optional<Integer> id = defense.spawner().unitIdOf(event.getEntity());
        if (id.isEmpty()) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);

        defense.registry().byId(id.get()).ifPresent(unit ->
                defense.onDeath(unit, event.getEntity().getUniqueId())
                        .exceptionally(error -> {
                            logger.log(java.util.logging.Level.WARNING,
                                    "Could not remove a dead defense unit", error);
                            return 0;
                        }));
    }

    // ==================================================================================
    // Targeting, SPEC 12.3
    // ==================================================================================

    /**
     * What a unit is allowed to go after.
     *
     * <p>The whole SPEC 12.3 table, applied at the moment the mob picks a target. Peacetime
     * players are the case that matters: a unit that targets a visitor is a unit that makes
     * its city unvisitable.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        Optional<Integer> id = defense.spawner().unitIdOf(event.getEntity());
        if (id.isEmpty()) {
            return;
        }
        Optional<DefenseUnit> unit = defense.registry().byId(id.get());
        Optional<City> owner = unit.flatMap(found -> cities.city(found.cityId()));
        if (owner.isEmpty()) {
            event.setCancelled(true);
            return;
        }

        LivingEntity target = event.getTarget();
        if (target == null) {
            return;
        }

        if (target instanceof Player player) {
            double distance = distanceBetween(event.getEntity(), player);
            if (behaviour.towardsPlayer(owner.get(), player.getUniqueId(), distance)
                    == DefenseBehaviour.Reaction.IGNORE) {
                event.setCancelled(true);
                event.setTarget(null);
            }
            return;
        }

        boolean hostile = target instanceof Monster;
        if (!hostile) {
            // A guard must not decide the city's cows are the enemy.
            event.setCancelled(true);
            event.setTarget(null);
            return;
        }
        if (behaviour.towardsHostile(owner.get()) == DefenseBehaviour.Reaction.IGNORE) {
            event.setCancelled(true);
            event.setTarget(null);
        }
    }

    /** A unit never targets something for a reason the table does not cover. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTargetReason(EntityTargetEvent event) {
        if (!defense.spawner().isDefenseUnit(event.getEntity())) {
            return;
        }
        if (event.getReason() == EntityTargetEvent.TargetReason.RANDOM_TARGET) {
            // Iron golems pick fights on their own; a Siege Golem does not.
            event.setCancelled(true);
        }
    }

    // ==================================================================================
    // Persistence, SPEC 12.5
    // ==================================================================================

    /**
     * SPEC 12.5: on chunk load, verify the entity still exists and respawn it if it does not.
     *
     * <p>A unit can be lost to {@code /kill}, to chunk corruption, or to a world edit, and a
     * city that paid 60,000 C for it should not have to notice.
     */
    /**
     * Relinks entities a chunk brings back, SPEC 12.5.
     *
     * <p>What this no longer does is <b>respawn</b>. The superseded M12 treated a chunk loading
     * as the signal to bring a unit up, which is coarser than it looks — a chunk stays loaded
     * for a player two hundred blocks away, and spawn chunks never unload — and it had no
     * despawn path at all, so a garrison stayed standing until a restart. SPEC 25.4's sweep
     * owns that decision now, and it decides by distance rather than by chunk residency.
     *
     * <p>The relinking is still needed: an entity that survives a chunk round trip is the same
     * unit, and losing the link would leave the sweep thinking it had dematerialised while a
     * mob stood there.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            defense.spawner().unitIdOf(entity).ifPresent(id ->
                    defense.registry().link(entity.getUniqueId(), id));
        }
    }

    /** A chunk unloading forgets its entities without touching their rows. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (defense.spawner().isDefenseUnit(entity)) {
                defense.registry().unlink(entity.getUniqueId());
            }
        }
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private static double distanceBetween(Entity one, Entity other) {
        if (one.getWorld() != other.getWorld()) {
            return Double.MAX_VALUE;
        }
        return one.getLocation().distance(other.getLocation());
    }
}
