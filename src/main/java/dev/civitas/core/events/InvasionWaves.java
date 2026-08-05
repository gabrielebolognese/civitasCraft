package dev.civitas.core.events;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ClaimRegistry;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The SPEC 13.5 Invasion: waves of hostile mobs near city borders.
 *
 * <h2>Why mobs are tagged</h2>
 * SPEC 13.5 pays a city treasury for mobs "killed inside their claims", which cannot mean any
 * mob: a city with a dark room would earn the invasion reward forever, and one built over a
 * cave would earn it by accident. So every mob this class spawns is stamped in its persistent
 * data, and only a stamped mob pays out. The stamp survives chunk unload and restart, which
 * matters because a wave outlives the tick that spawned it.
 *
 * <p>SPEC 13.5 says defense units "earn their keep here", so invasion mobs are ordinary
 * hostile mobs: M12's units already attack hostiles inside their claim and need no special
 * case to fight these.
 */
public final class InvasionWaves {

    /** Marks a mob as belonging to an invasion, and to which one. */
    public static final String TAG = "civitas_invasion";

    private final Plugin plugin;
    private final CityRegistry cities;
    private final ClaimRegistry claims;
    private final EventEffects effects;
    private final Logger logger;
    private final NamespacedKey key;

    public InvasionWaves(Plugin plugin, CityRegistry cities, ClaimRegistry claims,
                         EventEffects effects, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.key = new NamespacedKey(plugin, TAG);
    }

    public NamespacedKey key() {
        return key;
    }

    /** Whether this entity was spawned by an invasion, and so pays a treasury when it dies. */
    public boolean isInvasionMob(Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(key, PersistentDataType.INTEGER);
    }

    /** Which invasion spawned it, for a reward that cannot be claimed against a later event. */
    public Optional<Integer> invasionOf(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                entity.getPersistentDataContainer().get(key, PersistentDataType.INTEGER));
    }

    /**
     * Spawns one wave around every city, on the server thread.
     *
     * @return how many mobs were spawned
     */
    public int spawnWave(ServerEvent invasion) {
        if (!effects.isInvasionActive()) {
            return 0;
        }
        int perWave = effects.invasionMobsPerWave();
        List<EntityType> types = mobTypes();
        if (types.isEmpty() || perWave <= 0) {
            return 0;
        }

        int spawned = 0;
        for (City city : cities.cities()) {
            spawned += spawnAround(city, invasion, perWave, types);
        }
        return spawned;
    }

    /**
     * Spawns a city's share of a wave just outside its border.
     *
     * <p>Outside rather than inside, deliberately. SPEC 13.5 says "near city borders", and
     * dropping twenty hostiles into somebody's town square would destroy the build the
     * rollback promise exists to protect. Arriving at the edge is what makes defense units and
     * walls worth having.
     */
    private int spawnAround(City city, ServerEvent invasion, int count, List<EntityType> types) {
        List<Claim> owned = List.copyOf(claims.claimsOf(city.id()));
        if (owned.isEmpty()) {
            return 0;
        }

        World world = plugin.getServer().getWorld(owned.get(0).world());
        if (world == null) {
            return 0;
        }

        int radius = Math.max(1, effects.invasionSpawnRadius());
        int spawned = 0;

        for (int index = 0; index < count; index++) {
            Claim edge = owned.get(index % owned.size());
            // Step out from the claim by the configured radius, in one of four directions, so
            // a wave arrives spread around the city rather than in one heap.
            int direction = index % 4;
            int blockX = (edge.chunkX() << 4) + 8 + (direction == 0 ? radius : direction == 1 ? -radius : 0);
            int blockZ = (edge.chunkZ() << 4) + 8 + (direction == 2 ? radius : direction == 3 ? -radius : 0);

            if (claims.at(world.getName(), blockX >> 4, blockZ >> 4).isPresent()) {
                // The step landed on somebody's land after all; skip rather than spawn inside.
                continue;
            }

            Location at = new Location(world, blockX + 0.5,
                    world.getHighestBlockYAt(blockX, blockZ) + 1.0, blockZ + 0.5);
            EntityType type = types.get(index % types.size());

            try {
                Entity mob = world.spawnEntity(at, type);
                mob.getPersistentDataContainer().set(key, PersistentDataType.INTEGER,
                        invasion.id());
                if (mob instanceof LivingEntity living) {
                    living.setRemoveWhenFarAway(false);
                }
                spawned++;
            } catch (IllegalArgumentException e) {
                logger.log(Level.FINE, "Could not spawn an invasion mob at " + at, e);
            }
        }
        return spawned;
    }

    /** Removes every invasion mob still standing, when the event ends. */
    public int despawnAll() {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isInvasionMob(entity)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    private List<EntityType> mobTypes() {
        return effects.invasionMobTypes().stream()
                .map(name -> {
                    try {
                        return EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        logger.warning("events.yml names an unknown invasion mob: " + name);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }
}
