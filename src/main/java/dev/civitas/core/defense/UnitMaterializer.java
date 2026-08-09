package dev.civitas.core.defense;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.defense.Materialization.Decision;
import dev.civitas.core.defense.Materialization.Point;
import dev.civitas.core.defense.Materialization.UnitState;
import dev.civitas.storage.dao.DefenseUnitDao;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Turns {@link Materialization}'s decisions into entities, SPEC 25.4.
 *
 * <p>The Bukkit-facing half. {@link Materialization} owns the rule and is pure; this owns the
 * consequences — spawning, despawning, and the health that has to survive in between.
 *
 * <h2>What this replaces</h2>
 *
 * <p>The superseded M12 respawned units on {@code ChunkLoadEvent}. That is coarser than it
 * looks: a chunk stays loaded for a player standing two hundred blocks away at view distance
 * 16, and spawn chunks never unload at all, so a city near spawn kept its whole garrison
 * standing forever. Worse, it had no despawn path at all — nothing ever took a unit down — so
 * every unit any player had ever walked past stayed loaded until a restart. That is the 2,400
 * entities SPEC 25.4 opens by refusing.
 *
 * <h2>Health is written on the way down, not on a timer alone</h2>
 *
 * <p>SPEC 25.4: "a unit at 40% health that dematerializes returns at 40% health." So the health
 * is read off the entity at the moment it is removed, and the 30-second checkpoint exists for
 * the case where the server dies before that moment ever arrives.
 */
public final class UnitMaterializer {

    private final ConfigManager configs;
    private final DefenseUnitDao units;
    private final DefenseRegistry registry;
    private final DefenseCatalogue catalogue;
    private final DefenseSpawner spawner;
    private final CityRegistry cities;
    private final Logger logger;

    /** Whether a unit's chunk is inside a war that is running. Filled by M19's wiring. */
    private WarZones wars = (cityId, world, chunkX, chunkZ) -> false;

    /** What this needs to know about wars, and no more. */
    @FunctionalInterface
    public interface WarZones {

        boolean isActiveWarZone(int cityId, String world, int chunkX, int chunkZ);
    }

    public UnitMaterializer(ConfigManager configs, DefenseUnitDao units, DefenseRegistry registry,
                            DefenseCatalogue catalogue, DefenseSpawner spawner,
                            CityRegistry cities, Logger logger) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.units = Objects.requireNonNull(units, "units");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void useWars(WarZones zones) {
        this.wars = Objects.requireNonNull(zones, "zones");
    }

    // ==================================================================================
    // The sweep
    // ==================================================================================

    /** The rule, built fresh so an operator's {@code /ca reload} takes effect. */
    public Materialization rule() {
        var defense = configs.get(ConfigFile.DEFENSE);
        return new Materialization(
                defense.getDouble("materialization.radius-blocks", 48),
                defense.getLong("materialization.dematerialize-delay-seconds", 30) * 1000L,
                defense.getInt("materialization.max-materialized", 60));
    }

    /**
     * One pass over every unit on the server.
     *
     * @return how many changed state, for {@code /ca perf} and for the tests
     */
    public int sweep(long now) {
        if (!enabled()) {
            return 0;
        }
        List<Point> players = onlinePlayers();
        List<UnitState> states = states(now);
        var changes = rule().sweep(states, players, now);

        int acted = 0;
        for (var change : changes.entrySet()) {
            Optional<DefenseUnit> unit = registry.byId(change.getKey());
            if (unit.isEmpty()) {
                continue;
            }
            boolean done = change.getValue() == Decision.MATERIALIZE
                    ? materialize(unit.get(), now)
                    : dematerialize(unit.get(), now);
            if (done) {
                acted++;
            }
        }
        return acted;
    }

    /** Every active unit, in the shape the rule reasons about. */
    private List<UnitState> states(long now) {
        List<UnitState> states = new ArrayList<>();
        for (DefenseUnit unit : registry.all()) {
            if (!unit.active()) {
                // SPEC 12.3: a unit deactivated for unpaid upkeep has no entity and should not
                // compete for a seat in the budget with one a city is paying for.
                continue;
            }
            int chunkX = (int) Math.floor(unit.x()) >> 4;
            int chunkZ = (int) Math.floor(unit.z()) >> 4;
            states.add(new UnitState(unit.id(),
                    new Point(unit.world(), unit.x(), unit.y(), unit.z()),
                    registry.isMaterialized(unit.id()),
                    unit.dormantSince() == null ? now : unit.dormantSince(),
                    wars.isActiveWarZone(unit.cityId(), unit.world(), chunkX, chunkZ)));
        }
        return states;
    }

    private static List<Point> onlinePlayers() {
        List<Point> points = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            var at = player.getLocation();
            if (at.getWorld() != null) {
                points.add(new Point(at.getWorld().getName(), at.getX(), at.getY(), at.getZ()));
            }
        }
        return points;
    }

    // ==================================================================================
    // Up and down
    // ==================================================================================

    /** Brings a unit up at the health it went down with, plus whatever it healed. */
    public boolean materialize(DefenseUnit unit, long now) {
        Optional<City> city = cities.city(unit.cityId());
        Optional<DefenseUnitType> type = catalogue.byKey(unit.type());
        if (city.isEmpty() || type.isEmpty()) {
            return false;
        }

        Optional<LivingEntity> spawned = spawner.spawn(unit, type.get(), city.get(),
                fortificationOf(city.get()));
        if (spawned.isEmpty()) {
            return false;
        }
        LivingEntity entity = spawned.get();
        registry.link(entity.getUniqueId(), unit.id());

        // Through the attribute, not the deprecated getMaxHealth: DefenseSpawner sets
        // MAX_HEALTH from the catalogue, so the attribute is what a unit's maximum actually is.
        var attribute = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double maximum = attribute == null ? entity.getHealth() : attribute.getValue();
        double restored = Materialization.regenerated(unit.healthOr(maximum), maximum,
                regenPercentPerHour(), unit.dormantSince() == null ? 0L : unit.dormantSince(),
                now, atWar(unit));
        entity.setHealth(Math.max(0.1, Math.min(restored, maximum)));

        // Dormancy ends here. Leaving the timestamp would pay the unit regeneration again the
        // next time it went down, for hours it spent standing up.
        registry.put(unit.withState(restored, null));
        write(unit.id(), restored, null);
        return true;
    }

    /** Takes a unit down, writing back the health it is standing at. */
    public boolean dematerialize(DefenseUnit unit, long now) {
        Optional<LivingEntity> entity = registry.entityOf(unit.id());
        Double health = entity.map(LivingEntity::getHealth).orElse(unit.health());

        entity.ifPresent(living -> {
            registry.unlink(living.getUniqueId());
            living.remove();
        });

        registry.put(unit.withState(health, now));
        write(unit.id(), health, now);
        return true;
    }

    /**
     * SPEC 25.4's 30-second combat checkpoint.
     *
     * <p>"A server restart while materialized must not lose health state." The dematerialise
     * path already writes health, and this exists for the case where that moment never comes
     * because the process died first.
     */
    public int checkpoint() {
        if (!enabled()) {
            return 0;
        }
        int written = 0;
        for (DefenseUnit unit : registry.all()) {
            Optional<LivingEntity> entity = registry.entityOf(unit.id());
            if (entity.isEmpty()) {
                continue;
            }
            double health = entity.get().getHealth();
            if (unit.health() != null && Math.abs(unit.health() - health) < 0.01) {
                // Unchanged since the last checkpoint. Writing it again would be a database
                // round trip per unit per thirty seconds for a garrison standing at full.
                continue;
            }
            registry.put(unit.withState(health, null));
            write(unit.id(), health, null);
            written++;
        }
        return written;
    }

    /**
     * SPEC 31 case 87: everything is dormant on startup, whatever it was when the server died.
     *
     * <p>Also clears any entity left linked, because the UUIDs in the cache refer to entities
     * from a world that has just been reloaded.
     */
    public void onStartup(long now) {
        units.markAllDormant(now).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not mark defense units dormant on startup", error);
            return 0;
        });
    }

    // ==================================================================================
    // Configuration
    // ==================================================================================

    public boolean enabled() {
        return configs.get(ConfigFile.DEFENSE)
                .getBoolean("materialization.enabled", true);
    }

    public long sweepIntervalTicks() {
        return configs.get(ConfigFile.DEFENSE)
                .getLong("materialization.sweep-interval-ticks", 40);
    }

    public long checkpointIntervalTicks() {
        return configs.get(ConfigFile.DEFENSE)
                .getLong("materialization.health-checkpoint-seconds", 30) * 20L;
    }

    private double regenPercentPerHour() {
        return configs.get(ConfigFile.DEFENSE)
                .getDouble("materialization.dormant-regen-percent-per-hour", 10);
    }

    /** SPEC 25.4: regeneration is disabled entirely during a war, so damage sticks. */
    private boolean atWar(DefenseUnit unit) {
        int chunkX = (int) Math.floor(unit.x()) >> 4;
        int chunkZ = (int) Math.floor(unit.z()) >> 4;
        return wars.isActiveWarZone(unit.cityId(), unit.world(), chunkX, chunkZ);
    }

    private int fortificationOf(City city) {
        return upgrades == null ? 0 : upgrades.fortificationLevel(city);
    }

    private Fortification upgrades;

    /** The SPEC 5.7 upgrade level, which decides a unit's maximum health. */
    @FunctionalInterface
    public interface Fortification {

        int fortificationLevel(City city);
    }

    public void useUpgrades(Fortification levels) {
        this.upgrades = Objects.requireNonNull(levels, "levels");
    }

    private void write(int id, Double health, Long dormantSince) {
        try {
            units.saveState(id, health, dormantSince).exceptionally(error -> {
                logger.log(Level.WARNING, "Could not save state for defense unit " + id, error);
                return 0;
            });
        } catch (RuntimeException e) {
            // db.call throws synchronously on a closed pool, so exceptionally alone would not
            // see it. The rule recorded at M18 after this cost three milestones a dropped write.
            logger.log(Level.WARNING, "Could not save state for defense unit " + id, e);
        }
    }
}
