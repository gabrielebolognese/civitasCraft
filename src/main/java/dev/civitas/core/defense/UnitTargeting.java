package dev.civitas.core.defense;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.defense.TargetingRule.Candidate;
import dev.civitas.core.defense.TargetingRule.Decision;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

/**
 * Resolves a Bukkit entity into {@link TargetingRule}'s question, SPEC 30.1.
 *
 * <p>All the Bukkit lookups and none of the decisions. {@link TargetingRule} stays pure and
 * testable one branch at a time; this turns "that thing over there" into the eleven facts the
 * rule needs, and the listener that calls it has nothing in it but the event plumbing.
 *
 * <p>SPEC 30.1: "There must be exactly one such handler and no unit-specific targeting logic
 * anywhere else." This class and the rule behind it are that handler's whole content.
 */
public final class UnitTargeting {

    /** SPEC 26.4: five seconds after joining or respawning, nothing may target you. */
    private static final long JOIN_GRACE_MILLIS = 5_000L;

    private final TargetingRule rule = new TargetingRule();
    private final CityRegistry cities;
    private final DefenseRegistry units;
    private final DefenseSpawner spawner;
    private final UnitStates states;
    private final DefenseCatalogue catalogue;
    private final dev.civitas.config.ConfigManager configs;

    /** When each player last joined or respawned. Memory only; a restart resets everyone. */
    private final Map<UUID, Long> grace = new ConcurrentHashMap<>();

    public UnitTargeting(CityRegistry cities, DefenseRegistry units, DefenseSpawner spawner,
                         UnitStates states, DefenseCatalogue catalogue,
                         dev.civitas.config.ConfigManager configs) {
        this.cities = Objects.requireNonNull(cities, "cities");
        this.units = Objects.requireNonNull(units, "units");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.states = Objects.requireNonNull(states, "states");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /** Records a join or a respawn, starting the SPEC 26.4 grace. */
    public void graceFor(UUID player, long now) {
        grace.put(player, now);
    }

    public void forget(UUID player) {
        grace.remove(player);
    }

    /**
     * May this unit target this entity?
     *
     * @return empty when the attacker is not one of ours, so the caller leaves the event alone
     */
    public Optional<Decision> decide(Entity attacker, LivingEntity target, long now) {
        Optional<Integer> unitId = spawner.unitIdOf(attacker);
        if (unitId.isEmpty() || target == null) {
            return Optional.empty();
        }
        Optional<DefenseUnit> unit = units.byId(unitId.get());
        if (unit.isEmpty()) {
            // An entity carrying our tag whose row is gone. Cancel rather than allow: a mob
            // nothing owns should not be hunting anybody on a city's behalf.
            return Optional.of(new Decision(false, "NO_SUCH_UNIT"));
        }
        Optional<City> owner = cities.city(unit.get().cityId());
        if (owner.isEmpty()) {
            return Optional.of(new Decision(false, "NO_OWNING_CITY"));
        }

        double range = rangeOf(unit.get().type());
        UnitStates.Current current = states.of(unitId.get(), now);

        return Optional.of(rule.decide(
                new TargetingRule.Unit(current.state(), current.alertedTarget(), range),
                candidateOf(owner.get(), attacker, target, now)));
    }

    private Candidate candidateOf(City owner, Entity attacker, LivingEntity target, long now) {
        boolean isUnit = spawner.isDefenseUnit(target);

        if (!(target instanceof Player player)) {
            return new Candidate(false, target instanceof Monster, isUnit, target.getUniqueId(),
                    false, false, false, false, false, false,
                    distance(attacker, target));
        }

        UUID uuid = player.getUniqueId();
        Long joined = grace.get(uuid);
        boolean inGrace = joined != null && now - joined < JOIN_GRACE_MILLIS;

        return new Candidate(true, false, isUnit, uuid,
                owner.isMember(uuid),
                isAllied(owner, uuid),
                player.hasPermission("civitas.bypass.war"),
                player.getGameMode() == GameMode.CREATIVE
                        || player.getGameMode() == GameMode.SPECTATOR,
                inGrace,
                isEnemyInWarZone(owner, uuid),
                distance(attacker, target));
    }

    /**
     * How far a unit can see, SPEC 30.1's last line.
     *
     * <p>Per unit where the roster gives one — SPEC 27 has an Archer at 20 blocks and a
     * Warhound chasing to 24 — falling back to one figure until M12d ships that roster. Read
     * rather than cached so {@code /ca reload} takes effect, and read per type rather than
     * hardcoded so M12d adds keys instead of changing this class.
     */
    private double rangeOf(String type) {
        var defense = configs.get(dev.civitas.config.ConfigFile.DEFENSE);
        double fallback = defense.getDouble("targeting.default-range-blocks", 16.0);
        return defense.getDouble("units." + type + ".range", fallback);
    }

    private static double distance(Entity attacker, Entity target) {
        if (attacker.getWorld() != target.getWorld()) {
            return Double.MAX_VALUE;
        }
        return attacker.getLocation().distance(target.getLocation());
    }

    // ==================================================================================
    // The seams other milestones fill
    // ==================================================================================

    private boolean isAllied(City owner, UUID player) {
        if (diplomacy == null) {
            return false;
        }
        return cities.cityOf(player)
                .map(other -> other.id() != owner.id() && diplomacy.areAllied(owner.id(),
                        other.id()))
                .orElse(false);
    }

    private boolean isEnemyInWarZone(City owner, UUID player) {
        return wars != null && wars.isEnemyInZone(owner.id(), player);
    }

    private Alliances diplomacy;
    private Wars wars;

    /** SPEC 26.4: members of allied cities are never targets. */
    @FunctionalInterface
    public interface Alliances {

        boolean areAllied(int cityId, int otherCityId);
    }

    /** SPEC 26.3: HOSTILE targets enemy members inside the war zone, and nobody else. */
    @FunctionalInterface
    public interface Wars {

        boolean isEnemyInZone(int cityId, UUID player);
    }

    public void useDiplomacy(Alliances alliances) {
        this.diplomacy = Objects.requireNonNull(alliances, "alliances");
    }

    public void useWars(Wars warRegistry) {
        this.wars = Objects.requireNonNull(warRegistry, "warRegistry");
    }
}
