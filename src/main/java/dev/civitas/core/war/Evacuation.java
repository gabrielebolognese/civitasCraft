package dev.civitas.core.war;

import java.util.Objects;
import java.util.Optional;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * SPEC 11.8.2 step 1: get everyone out before the world starts moving.
 *
 * <p>"All players inside the war zone are teleported to their own city spawn (or server spawn
 * if their city is party to the war and its spawn is inside the zone)."
 *
 * <p>This runs before a single block is restored, and the ordering is the whole point. M18
 * restores blocks with physics suppressed and no regard for what is standing where; SPEC 17.4
 * case 50 says to restore anyway and check afterwards. Evacuating first means that check
 * almost never has anything to do, rather than being the only thing between a player and
 * suffocating inside a rebuilt wall.
 */
public final class Evacuation {

    private final CityRegistry cities;
    private final java.util.function.Supplier<java.util.Collection<? extends Player>> online;

    /**
     * @param online where the players come from, injected in the same shape
     *               {@code StipendTask} uses. A class that reaches into {@code Bukkit} itself
     *               cannot be tested without a server, and this one has to be: it runs
     *               immediately before the rollback and a mistake here puts somebody inside a
     *               wall
     */
    public Evacuation(CityRegistry cities,
                      java.util.function.Supplier<java.util.Collection<? extends Player>> online) {
        this.cities = Objects.requireNonNull(cities, "cities");
        this.online = Objects.requireNonNull(online, "online");
    }

    /** The real one, reading the server's player list. */
    public static Evacuation of(CityRegistry cities) {
        return new Evacuation(cities, Bukkit::getOnlinePlayers);
    }

    /** One with nobody online, for tests that are not about the evacuation itself. */
    public static Evacuation empty(CityRegistry cities) {
        return new Evacuation(cities, java.util.List::of);
    }

    /**
     * Moves every player standing in the zone somewhere safe.
     *
     * <p>Runs on the server thread: it teleports.
     *
     * @return how many were moved
     */
    public int evacuate(War war) {
        WarZone zone = war.zone();
        if (zone.isEmpty()) {
            return 0;
        }

        int moved = 0;
        for (Player player : online.get()) {
            Location at = player.getLocation();
            if (at.getWorld() == null
                    || !zone.containsBlock(at.getWorld().getName(), at.getBlockX(),
                    at.getBlockZ())) {
                continue;
            }
            player.teleportAsync(destinationFor(player, war));
            moved++;
        }
        return moved;
    }

    /**
     * Moves one player out, without reference to any particular war.
     *
     * <p>SPEC 17.4 cases 41 and 48: somebody who arrives inside a zone rather than being
     * caught in one. Their own city spawn if they have one, the world spawn otherwise — the
     * per-war check that {@link #destinationFor} makes does not apply, because a player who
     * just joined is not being evacuated ahead of a restore that is about to reach them.
     */
    public void moveOut(Player player) {
        Optional<City> city = cities.cityOf(player.getUniqueId());
        Location spawn = city.map(this::spawnOf).orElse(null);
        player.teleportAsync(spawn != null && spawn.getWorld() != null
                ? spawn
                : fallback(player));
    }

    /**
     * Where one player goes.
     *
     * <p>Their own city spawn, unless that spawn is itself inside the zone, which it will be
     * for everyone actually fighting. Those go to the world spawn instead: sending a defender
     * "home" to a chunk that is about to be rewritten would evacuate them into the problem.
     */
    Location destinationFor(Player player, War war) {
        Optional<City> city = cities.cityOf(player.getUniqueId());
        if (city.isPresent()) {
            Location spawn = spawnOf(city.get());
            if (spawn != null && spawn.getWorld() != null
                    && !war.zone().containsBlock(spawn.getWorld().getName(),
                    spawn.getBlockX(), spawn.getBlockZ())) {
                return spawn;
            }
        }
        return fallback(player);
    }

    private Location spawnOf(City city) {
        var world = Bukkit.getWorld(city.coreWorld());
        if (world == null) {
            return null;
        }
        return new Location(world, city.spawnX(), city.spawnY(), city.spawnZ(),
                city.spawnYaw(), city.spawnPitch());
    }

    private static Location fallback(Player player) {
        var worlds = Bukkit.getWorlds();
        if (!worlds.isEmpty()) {
            return worlds.get(0).getSpawnLocation();
        }
        return player.getLocation();
    }
}
