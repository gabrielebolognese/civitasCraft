package dev.civitas.listener;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.defense.DefenseCatalogue;
import dev.civitas.core.defense.DefenseRegistry;
import dev.civitas.core.defense.DefenseSpawner;
import dev.civitas.core.defense.DefenseUnit;
import dev.civitas.core.defense.DefenseUnitType;
import dev.civitas.core.defense.DefenseUnitType.Ability;
import dev.civitas.core.defense.TrespassService;
import dev.civitas.core.defense.UnitAbilities;
import dev.civitas.core.defense.UnitStates;
import dev.civitas.core.defense.UnitSurvival;
import dev.civitas.lang.LangManager;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
// In .entity rather than .player, despite the name. The obvious import does not resolve.
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * What SPEC 27's units actually <em>do</em>, and the SPEC 30.2 rules that keep them alive.
 *
 * <p>Deliberately not in {@link DefenseListener}. SPEC 30.1 requires exactly one targeting
 * handler and no unit-specific targeting logic anywhere else, and the surest way to keep that
 * visibly true is for the file holding it to contain nothing else that reasons about a unit and
 * a player. Nothing here decides who may be attacked; every branch begins after a hit has
 * already landed, or answers a question about a block or an item.
 *
 * <p>The arithmetic lives in {@link UnitAbilities} and {@link UnitSurvival}, both pure, because
 * two of these numbers <b>are</b> a unit's stated counterplay and SPEC 25.2 Rule 3 makes
 * counterplay a shipping gate.
 */
public final class DefenseAbilityListener implements Listener {

    private final DefenseSpawner spawner;
    private final DefenseRegistry units;
    private final DefenseCatalogue catalogue;
    private final CityRegistry cities;
    private final UnitStates states;
    private final LangManager lang;
    private final UnitSurvival survival = new UnitSurvival();

    /** SPEC 27.5's fire rate, as a credit that a shot spends. See {@link #onShoot}. */
    private final Map<Integer, Double> fireCredit = new ConcurrentHashMap<>();

    /** What the alert network tells the player it just turned on. */
    private Consumer<TrespassService.Event> effects = event -> { };

    public DefenseAbilityListener(DefenseSpawner spawner, DefenseRegistry units,
                                  DefenseCatalogue catalogue, CityRegistry cities,
                                  UnitStates states, LangManager lang) {
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.units = Objects.requireNonNull(units, "units");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.states = Objects.requireNonNull(states, "states");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    /**
     * The same sink SPEC 26.2's trespass response uses.
     *
     * <p>SPEC 27.6's alert network fires "regardless of trespass state", which sits awkwardly
     * against SPEC 26.2's promise that "no player is ever killed without being told, in plain
     * language, that they are about to be". Both are kept: the network turns the guards hostile
     * immediately, as the later and more specific rule says, <em>and</em> the player is told, in
     * the words the trespass response already uses.
     */
    public void useEffects(Consumer<TrespassService.Event> sink) {
        this.effects = Objects.requireNonNull(sink, "sink");
    }

    // ==================================================================================
    // SPEC 27.2, the Frost Sentry
    // ==================================================================================

    /**
     * A sentry's snowball slows and tires rather than hurting.
     *
     * <p>SPEC 27.2 says to cancel the snowball's damage and apply the effects instead. Both
     * halves are here, on two events, because a vanilla snowball deals no damage to anything
     * except a blaze — so hanging the debuffs off the damage event alone would work on a test
     * server and silently do nothing in a real fight.
     *
     * <p>The mining fatigue is the interesting half. During a war it slows block-breaking
     * directly, which is the attacker's main activity, and it costs almost nothing.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSnowball(org.bukkit.event.entity.ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)
                || !(event.getHitEntity() instanceof LivingEntity hit)) {
            return;
        }
        typeOfShooter(snowball)
                .filter(UnitAbilities::isFrostProjectile)
                .ifPresent(type -> {
                    apply(hit, "slowness",
                            UnitAbilities.amplifierOf(type, Ability.SLOWNESS_LEVEL),
                            UnitAbilities.ticksOf(type, Ability.SLOWNESS_SECONDS));
                    apply(hit, "mining_fatigue",
                            UnitAbilities.amplifierOf(type, Ability.MINING_FATIGUE_LEVEL),
                            UnitAbilities.ticksOf(type, Ability.MINING_FATIGUE_SECONDS));
                });
    }

    // ==================================================================================
    // SPEC 27.4, 27.6, 27.7: what happens when a hit lands
    // ==================================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        unitTypeOf(event.getDamager()).ifPresent(attacker -> {
            if (UnitAbilities.isFrostProjectile(attacker) || !attacker.dealsDamage()) {
                // SPEC 27.2: "deals no damage ever". Cancelled rather than set to zero, because
                // zero damage still triggers invulnerability frames and knockback.
                event.setCancelled(true);
                return;
            }
            if (event.getEntity() instanceof LivingEntity hit) {
                bite(attacker, hit);
                slam(attacker, event.getDamager(), hit);
            }
        });

        // The other direction: what a unit takes.
        spawner.unitIdOf(event.getEntity()).ifPresent(unitId -> {
            catalogue.byKey(units.byId(unitId).map(DefenseUnit::type).orElse(""))
                    .ifPresent(defender -> {
                        arrowResistance(event, defender);
                        alertNetwork(event, unitId, defender);
                    });
        });
    }

    /** SPEC 27.4: "Bite applies Slowness I for 2 seconds." */
    private void bite(DefenseUnitType attacker, LivingEntity hit) {
        if (UnitAbilities.bites(attacker)) {
            apply(hit, "slowness",
                    UnitAbilities.amplifierOf(attacker, Ability.BITE_SLOWNESS_LEVEL),
                    UnitAbilities.ticksOf(attacker, Ability.BITE_SLOWNESS_SECONDS));
        }
    }

    /**
     * SPEC 27.7: "On hit, targets within 3 blocks of the impact take 4 splash damage and heavy
     * knockback."
     *
     * <p>Around the victim rather than around the Colossus, because SPEC says "of the impact",
     * and a slam centred on the unit would catch people standing behind it.
     */
    private void slam(DefenseUnitType attacker, Entity source, LivingEntity hit) {
        if (!attacker.hasAbility(Ability.SLAM_RADIUS)) {
            return;
        }
        double radius = attacker.ability(Ability.SLAM_RADIUS, 0);
        Location impact = hit.getLocation();
        for (Entity nearby : hit.getWorld().getNearbyEntities(impact, radius, radius, radius)) {
            if (!(nearby instanceof Player bystander) || nearby.equals(hit)
                    || spawner.isDefenseUnit(nearby)) {
                continue;
            }
            if (!UnitAbilities.withinSlam(attacker, bystander.getLocation().distance(impact))) {
                continue;
            }
            bystander.damage(UnitAbilities.slamDamage(attacker), source);
            Vector away = bystander.getLocation().toVector().subtract(impact.toVector());
            if (away.lengthSquared() > 0) {
                bystander.setVelocity(bystander.getVelocity().add(away.normalize()
                        .multiply(UnitAbilities.slamKnockback(attacker)).setY(0.4)));
            }
        }
    }

    /**
     * SPEC 27.7's threshold, and the reason it is a threshold.
     *
     * <p>"Arrows dealing under 8 damage are reduced by 80%." An arrow at or above eight is not
     * reduced at all, which SPEC 27.7's counterplay states outright: the cap exists "so a fully
     * charged Power V bow still hurts it". Reduce everything and the tank has no ranged answer,
     * which is a SPEC 25.2 Rule 1 failure the numbers hide.
     */
    private void arrowResistance(EntityDamageByEntityEvent event, DefenseUnitType defender) {
        if (!(event.getDamager() instanceof AbstractArrow)
                || !defender.hasAbility(Ability.ARROW_RESIST_THRESHOLD)) {
            return;
        }
        double raw = event.getDamage();
        double after = UnitAbilities.arrowDamageAfterResist(defender, raw);
        if (after < raw) {
            event.setDamage(after);
        }
    }

    /**
     * SPEC 27.6's alert network.
     *
     * <p>"Damaging one City Guard causes every City Guard within 3 chunks to target the attacker
     * for 20 seconds, regardless of trespass state." It is what makes a garrison feel like a
     * garrison rather than a row of independent mobs — and SPEC 27.6 names it as the unit's own
     * weakness too, since pulling one guard pulls a predictable group into a chokepoint of the
     * attacker's choosing.
     */
    private void alertNetwork(EntityDamageByEntityEvent event, int unitId,
                              DefenseUnitType defender) {
        if (!UnitAbilities.hasAlertNetwork(defender)) {
            return;
        }
        Player attacker = attackerOf(event.getDamager());
        Optional<DefenseUnit> hit = units.byId(unitId);
        if (attacker == null || hit.isEmpty()) {
            return;
        }
        Optional<City> owner = cities.city(hit.get().cityId());
        if (owner.isEmpty() || owner.get().isMember(attacker.getUniqueId())) {
            return;
        }

        long millis = UnitAbilities.alertNetworkMillis(defender);
        double radius = UnitAbilities.alertNetworkRadiusBlocks(defender);
        long until = System.currentTimeMillis() + millis;

        int roused = 0;
        for (DefenseUnit other : units.activeOf(owner.get().id())) {
            if (!other.type().equals(hit.get().type())
                    || distance(hit.get(), other) > radius) {
                continue;
            }
            if (states.alert(other.id(), attacker.getUniqueId(), until)) {
                roused++;
            }
        }
        if (roused > 0) {
            // SPEC 26.2: "no player is ever killed without being told, in plain language, that
            // they are about to be." The network does not wait for a warning phase, so the
            // telling happens here instead of being skipped.
            effects.accept(new TrespassService.Event(TrespassService.Event.Kind.ALERTED,
                    owner.get(), attacker.getUniqueId(), attacker.getLocation(),
                    millis / 1000L, 0));
        }
    }

    private static double distance(DefenseUnit one, DefenseUnit other) {
        if (!one.world().equals(other.world())) {
            return Double.MAX_VALUE;
        }
        double dx = one.x() - other.x();
        double dz = one.z() - other.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ==================================================================================
    // SPEC 27.5, the Archer
    // ==================================================================================

    /**
     * The Archer's damage, and its fire rate in close quarters.
     *
     * <p>Two things at once, both on the shot rather than on the hit. The damage is written onto
     * the arrow because {@code Attribute.ATTACK_DAMAGE} does nothing for a bow, and because
     * vanilla bakes the Power enchantment into the arrow at launch — so writing SPEC 27.1's 7
     * here makes 7 the truth and leaves SPEC 27.5's Power III as the glint SPEC 25.3 files
     * equipment under.
     *
     * <p>"Fire rate halves while any enemy is within 5 blocks" is spent as a credit rather than
     * as a cooldown, because a cooldown needs a base interval and neither SPEC nor Bukkit
     * exposes a skeleton's. A penalty of 0.5 lets through one shot in two, which is what halving
     * a fire rate means, and it needs no number nobody wrote down.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        Optional<Integer> unitId = spawner.unitIdOf(event.getEntity());
        Optional<DefenseUnitType> type = unitTypeOf(event.getEntity());
        if (unitId.isEmpty() || type.isEmpty()) {
            return;
        }

        if (event.getProjectile() instanceof AbstractArrow arrow && type.get().dealsDamage()) {
            arrow.setDamage(type.get().damage());
        }

        double nearest = nearestPlayerDistance(event.getEntity(), type.get());
        double multiplier = UnitAbilities.fireDelayMultiplier(type.get(), nearest);
        if (multiplier <= 1.0) {
            return;
        }
        double credit = fireCredit.merge(unitId.get(), 1.0 / multiplier, Double::sum);
        if (credit < 1.0) {
            event.setCancelled(true);
            return;
        }
        fireCredit.put(unitId.get(), credit - 1.0);
    }

    private double nearestPlayerDistance(Entity shooter, DefenseUnitType type) {
        double radius = type.ability(Ability.MELEE_FIRERATE_RADIUS, 0);
        double nearest = Double.MAX_VALUE;
        for (Entity nearby : shooter.getWorld()
                .getNearbyEntities(shooter.getLocation(), radius, radius, radius)) {
            if (nearby instanceof Player player) {
                nearest = Math.min(nearest,
                        player.getLocation().distance(shooter.getLocation()));
            }
        }
        return nearest;
    }

    // ==================================================================================
    // SPEC 30.2 cases 107 and 112: staying alive
    // ==================================================================================

    /**
     * Weather, terrain, and the difference between them.
     *
     * <p>Case 107 cancels a snow golem's rain and water damage, "or every sentry dies in the
     * first storm". Case 112 puts a unit that walked into lava back at its post at 20% health,
     * once an hour, so terrain cannot delete something a city paid for.
     *
     * <p>What neither may do is cancel fire or lava for a Frost Sentry, whose counterplay in
     * SPEC 27.2 is, in full, "melts in lava or near fire". {@link UnitSurvival} keeps the two
     * apart and every branch of it is asserted, because this is the one place in the milestone
     * where a stated counterplay can be removed by an implementation that looks correct.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnvironment(EntityDamageEvent event) {
        Optional<Integer> unitId = spawner.unitIdOf(event.getEntity());
        Optional<DefenseUnitType> type = unitTypeOf(event.getEntity());
        if (unitId.isEmpty() || type.isEmpty()
                || !(event.getEntity() instanceof LivingEntity living)) {
            return;
        }

        if (UnitSurvival.isWeatherDeath(type.get().mob(), event.getCause())) {
            event.setCancelled(true);
            return;
        }
        if (!UnitSurvival.isTerrain(event.getCause())
                || !UnitSurvival.canBeSaved(type.get())
                || event.getFinalDamage() < living.getHealth()) {
            return;
        }
        if (!survival.claimSave(unitId.get(), System.currentTimeMillis(),
                catalogue.deathSaveCooldownMillis())) {
            return;
        }

        event.setCancelled(true);
        var attribute = living.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double maximum = attribute == null ? living.getHealth() : attribute.getValue();
        living.setFireTicks(0);
        living.setHealth(UnitSurvival.savedHealth(maximum, catalogue.deathSaveHealthPercent()));
        units.byId(unitId.get()).flatMap(DefenseUnit::location)
                .ifPresent(post -> living.teleport(post));
    }

    /**
     * SPEC 27.2's sentry does not carpet a city in snow.
     *
     * <p>Not in SPEC, and it has to be: a garrison of snow golems lays a snow layer wherever it
     * stands, and inside a war zone every one of those is a block change M17 logs and M18 has to
     * replay. A defence that inflates the rollback log is a defence that costs the server the
     * plugin's defining feature.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnowTrail(EntityBlockFormEvent event) {
        if (spawner.isDefenseUnit(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    // ==================================================================================
    // SPEC 30.2 cases 105, 110 and 111: hands off
    // ==================================================================================

    /**
     * One rule where SPEC lists two, and the reason for the third.
     *
     * <p>Case 110 blocks a name tag, because renaming would break the identity display. Case 111
     * blocks a lead. Neither is mentioned, but a Watchtower Keeper is an armour stand wearing
     * dyed leather and holding a spyglass, and any player may walk up and take all five items by
     * hand — which is case 105's loot piñata arriving from a direction case 105 does not
     * mention, and drop chances do nothing about it. A snow golem can likewise be sheared out of
     * existence with one click.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHandle(PlayerInteractEntityEvent event) {
        if (!spawner.isDefenseUnit(event.getRightClicked())) {
            return;
        }
        var held = event.getPlayer().getInventory().getItem(event.getHand());
        if (held == null) {
            return;
        }
        switch (held.getType()) {
            case NAME_TAG -> refuse(event, event.getPlayer(), "defense.no-name-tag");
            case LEAD -> refuse(event, event.getPlayer(), "defense.no-lead");
            default -> refuse(event, event.getPlayer(), "defense.hands-off");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (spawner.isDefenseUnit(event.getEntity())) {
            refuse(event, event.getPlayer(), "defense.no-lead");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (spawner.isDefenseUnit(event.getEntity())) {
            refuse(event, event.getPlayer(), "defense.hands-off");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUndress(PlayerArmorStandManipulateEvent event) {
        if (spawner.isDefenseUnit(event.getRightClicked())) {
            refuse(event, event.getPlayer(), "defense.hands-off");
        }
    }

    private void refuse(org.bukkit.event.Cancellable event, Player player, String key) {
        event.setCancelled(true);
        lang.send(player, key);
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    /** The catalogue entry behind an entity, if it is one of ours. */
    private Optional<DefenseUnitType> unitTypeOf(Entity entity) {
        return spawner.unitIdOf(entity)
                .flatMap(units::byId)
                .map(DefenseUnit::type)
                .flatMap(catalogue::byKey);
    }

    /** The catalogue entry behind whatever fired a projectile. */
    private Optional<DefenseUnitType> typeOfShooter(Projectile projectile) {
        return projectile.getShooter() instanceof Entity shooter
                ? unitTypeOf(shooter)
                : Optional.empty();
    }

    /** The player behind a hit, whether they threw it or swung it. */
    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /**
     * A potion effect, resolved by name.
     *
     * <p>Through Paper's registry rather than the {@code PotionEffectType} statics, matching the
     * catalogue and the spawner, so this keeps working as registries move off them. Ambient and
     * particle-free, per SPEC 25.3's "hidden potion effects".
     */
    private static void apply(LivingEntity target, String effect, int amplifier, int ticks) {
        if (ticks <= 0) {
            return;
        }
        PotionEffectType type = RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft(effect));
        if (type != null) {
            target.addPotionEffect(new PotionEffect(type, ticks, amplifier, true, true, true));
        }
    }

    /**
     * A dead unit takes its bookkeeping with it.
     *
     * <p>Its own handler rather than a hook on {@link DefenseListener}, so this class owns the
     * two maps it created and nothing has to remember to tell it. Both are keyed by unit id, and
     * SPEC 12.3 never reissues one, but a map that only ever grows is a leak on a server that
     * runs for months.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnitDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        spawner.unitIdOf(event.getEntity()).ifPresent(unitId -> {
            fireCredit.remove(unitId);
            survival.forget(unitId);
        });
    }
}
