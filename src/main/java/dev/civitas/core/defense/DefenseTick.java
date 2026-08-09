package dev.civitas.core.defense;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/**
 * The two repeating passes SPEC 27 needs, which no event can deliver.
 *
 * <h2>Acquisition</h2>
 *
 * <p>SPEC 30.1's handler only <b>vetoes</b> — it answers "may this unit target that candidate",
 * and until something proposes a candidate there is nothing for it to allow. A City Guard that
 * has just become ALERTED stands still, because a zombie's own goals never picked that player
 * out; a Warhound is worse, because SPEC 27.4 makes it a wolf and the spawner tames it with no
 * owner, and a tamed ownerless wolf initiates nothing at all. Without this pass the whole
 * roster is inert while every test of the state machine passes.
 *
 * <p>{@code Mob#setTarget} fires {@code EntityTargetLivingEntityEvent} itself, so the target
 * this proposes is re-examined by the one handler on the way in. That is not a loop worth
 * fearing — it is the same answer arriving twice — and it is why this asks first: proposing a
 * target the rule would refuse would have the handler cancel and null it, which is a wasted
 * event per unit per tick rather than a bug.
 *
 * <h2>Watchtowers</h2>
 *
 * <p>SPEC 27.3 says outright that the Keeper's detection "is a repeating task, not AI". It never
 * targets anybody, so the targeting handler has nothing to say about it, and glowing is applied
 * to players rather than to units.
 */
public final class DefenseTick {

    private final DefenseRegistry units;
    private final DefenseCatalogue catalogue;
    private final CityRegistry cities;
    private final UnitStates states;
    private final UnitTargeting targeting;
    private final WatchtowerDetection watchtowers;

    public DefenseTick(DefenseRegistry units, DefenseCatalogue catalogue, CityRegistry cities,
                       UnitStates states, UnitTargeting targeting,
                       WatchtowerDetection watchtowers) {
        this.units = Objects.requireNonNull(units, "units");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.states = Objects.requireNonNull(states, "states");
        this.targeting = Objects.requireNonNull(targeting, "targeting");
        this.watchtowers = Objects.requireNonNull(watchtowers, "watchtowers");
    }

    // ==================================================================================
    // Acquisition
    // ==================================================================================

    /**
     * Gives every alerted or hostile unit something to go after.
     *
     * @return how many units were given a target, for the tests and for {@code /ca perf}
     */
    public int acquire(long now) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) {
            return 0;
        }

        int acquired = 0;
        for (DefenseUnit unit : units.all()) {
            UnitState state = states.stateOf(unit.id(), now);
            if (!state.canTargetPlayers()) {
                continue;
            }
            Optional<LivingEntity> entity = units.entityOf(unit.id());
            Optional<DefenseUnitType> type = catalogue.byKey(unit.type());
            if (entity.isEmpty() || type.isEmpty()
                    || !(entity.get() instanceof Mob mob) || !type.get().dealsDamage()) {
                // A unit that deals no damage never acquires anything. SPEC 27.2 and 27.3 are
                // explicit that the Frost Sentry and the Keeper do not fight.
                continue;
            }

            Optional<UnitAcquisition.Target> chosen = UnitAcquisition.choose(
                    permitted(mob, online, now), type.get().targetPriority(), type.get().range());
            if (chosen.isEmpty()) {
                continue;
            }
            Player target = byUuid(online, chosen.get().uuid());
            if (target != null && !target.equals(mob.getTarget())) {
                mob.setTarget(target);
                acquired++;
            }
        }
        return acquired;
    }

    /** Everyone the one targeting handler says this unit may attack. */
    private List<UnitAcquisition.Target> permitted(Mob mob, List<Player> online, long now) {
        List<UnitAcquisition.Target> permitted = new ArrayList<>();
        for (Player player : online) {
            if (player.getWorld() != mob.getWorld()) {
                continue;
            }
            boolean allowed = targeting.decide(mob, player, now)
                    .map(TargetingRule.Decision::allowed)
                    .orElse(false);
            if (allowed) {
                permitted.add(new UnitAcquisition.Target(player.getUniqueId(),
                        player.getLocation().distance(mob.getLocation()),
                        player.getHealth()));
            }
        }
        return permitted;
    }

    private static Player byUuid(List<Player> online, UUID uuid) {
        for (Player player : online) {
            if (player.getUniqueId().equals(uuid)) {
                return player;
            }
        }
        return null;
    }

    // ==================================================================================
    // Watchtowers, SPEC 27.3
    // ==================================================================================

    /**
     * Who a city's Keepers can see, painted and announced.
     *
     * @param announce told the owning city that a stranger has walked in
     * @return how many players are lit up
     */
    public int watchtowers(long now, BiConsumer<City, Player> announce) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        int painted = 0;

        for (City city : cities.cities()) {
            Set<UUID> seen = new LinkedHashSet<>();
            for (DefenseUnit unit : units.activeOf(city.id())) {
                Optional<DefenseUnitType> type = catalogue.byKey(unit.type());
                Optional<LivingEntity> entity = units.entityOf(unit.id());
                if (type.isEmpty() || entity.isEmpty()
                        || !type.get().hasAbility(DefenseUnitType.Ability.GLOW_REFRESH_SECONDS)) {
                    continue;
                }
                Location at = entity.get().getLocation();
                for (Player player : online) {
                    if (player.getWorld() != at.getWorld() || !isStranger(city, player)) {
                        continue;
                    }
                    if (!WatchtowerDetection.withinRadius(player.getLocation().distance(at),
                            type.get().range())) {
                        continue;
                    }
                    seen.add(player.getUniqueId());
                    if (!watchtowers.isGlowing(city.id(), player.getUniqueId())) {
                        watchtowers.startGlowing(city.id(), player.getUniqueId());
                    }
                    player.setGlowing(true);
                    painted++;

                    long cooldown = (long) type.get().ability(
                            DefenseUnitType.Ability.CHAT_ALERT_COOLDOWN_MINUTES, 5) * 60_000L;
                    if (watchtowers.claimAnnouncement(city.id(), player.getUniqueId(), now,
                            cooldown)) {
                        announce.accept(city, player);
                    }
                }
            }
            // Only the players this system lit up, and only the ones no longer in reach. A
            // player glowing for some other reason must not be dimmed by a Keeper losing sight.
            for (UUID gone : watchtowers.stopGlowing(city.id(), seen)) {
                Player player = byUuid(online, gone);
                if (player != null) {
                    player.setGlowing(false);
                }
            }
        }
        return painted;
    }

    /** Not a member of this city and not a member of one it is allied with, SPEC 27.3. */
    private boolean isStranger(City city, Player player) {
        if (city.isMember(player.getUniqueId())) {
            return false;
        }
        return cities.cityOf(player.getUniqueId())
                .map(other -> other.id() != city.id() && !allied.areAllied(city.id(), other.id()))
                .orElse(true);
    }

    private UnitTargeting.Alliances allied = (cityId, otherId) -> false;

    /** The same alliance question the targeting handler asks, wired from the same place. */
    public void useDiplomacy(UnitTargeting.Alliances alliances) {
        this.allied = Objects.requireNonNull(alliances, "alliances");
    }
}
