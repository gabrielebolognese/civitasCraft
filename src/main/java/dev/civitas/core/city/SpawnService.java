package dev.civitas.core.city;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Result;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Teleporting home, SPEC 5.6.
 *
 * <p>Five seconds of warmup, cancelled by moving or by being hurt, then a thirty-second
 * cooldown. Both numbers are config keys and both exist for the same reason, SPEC 17.6 case
 * 77: without the warmup, {@code /city spawn} is an escape button that makes losing a fight
 * impossible, and without the cooldown it is one a player can hold down.
 *
 * <p>A warmup is held per player rather than per request, so asking again while one is
 * running restarts nothing: the first request stands and the player is told it is already
 * counting down. That matters because the alternative, cancelling and restarting, would let
 * a player under attack keep the timer permanently at five seconds and never actually leave.
 */
public final class SpawnService {

    private final Plugin plugin;
    private final CityRegistry cities;
    private final ConfigManager configs;
    private final LangManager lang;

    private final Map<UUID, Warmup> warmups = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTeleport = new ConcurrentHashMap<>();

    public SpawnService(Plugin plugin, CityRegistry cities, ConfigManager configs,
                        LangManager lang) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    // ==================================================================================
    // Where home is
    // ==================================================================================

    /** The city spawn as a live location, or empty if its world is not loaded. */
    public java.util.Optional<Location> spawnOf(City city) {
        World world = org.bukkit.Bukkit.getWorld(city.coreWorld());
        if (world == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Location(world, city.spawnX(), city.spawnY(),
                city.spawnZ(), city.spawnYaw(), city.spawnPitch()));
    }

    // ==================================================================================
    // Teleporting
    // ==================================================================================

    /**
     * Starts the warmup, or refuses with a reason.
     *
     * @return success once the countdown has started, not once the player has arrived
     */
    public Result<Long> requestTeleport(Player player) {
        City city = cities.cityOf(player.getUniqueId()).orElse(null);
        if (city == null) {
            return Result.failure("NO_CITY", "city.none");
        }
        if (warmups.containsKey(player.getUniqueId())) {
            return Result.failure("ALREADY_WARMING", "city.spawn.already-warming");
        }

        long remaining = cooldownRemaining(player);
        if (remaining > 0) {
            return Result.failure("COOLDOWN", "city.spawn.cooldown",
                    Map.of("seconds", String.valueOf((remaining + 999) / 1000)));
        }

        Location destination = spawnOf(city).orElse(null);
        if (destination == null) {
            return Result.failure("WORLD_NOT_LOADED", "city.spawn.world-missing",
                    Map.of("world", city.coreWorld()));
        }

        long seconds = warmupSeconds(player);
        if (seconds <= 0) {
            complete(player, destination);
            return Result.success(0L);
        }

        Location origin = player.getLocation().clone();
        BukkitTask task = org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            warmups.remove(player.getUniqueId());
            if (player.isOnline()) {
                complete(player, destination);
            }
        }, seconds * 20L);

        warmups.put(player.getUniqueId(), new Warmup(task, origin));
        lang.send(player, "city.spawn.warmup",
                LangManager.placeholder("seconds", String.valueOf(seconds)));
        return Result.success(seconds);
    }

    private void complete(Player player, Location destination) {
        player.teleport(destination);
        lastTeleport.put(player.getUniqueId(), System.currentTimeMillis());
        lang.send(player, "city.spawn.arrived");
    }

    /**
     * Stops a warmup, if one is running.
     *
     * @param messageKey what to tell the player, or null to cancel silently
     * @return whether anything was cancelled
     */
    public boolean cancel(Player player, String messageKey) {
        Warmup warmup = warmups.remove(player.getUniqueId());
        if (warmup == null) {
            return false;
        }
        warmup.task().cancel();
        if (messageKey != null) {
            lang.send(player, messageKey);
        }
        return true;
    }

    /** Whether the player has moved far enough from where they started to count as moving. */
    public boolean hasMovedAway(Player player, Location to) {
        Warmup warmup = warmups.get(player.getUniqueId());
        if (warmup == null || to == null || to.getWorld() == null) {
            return false;
        }
        Location origin = warmup.origin();
        if (!to.getWorld().equals(origin.getWorld())) {
            return true;
        }
        double tolerance = configs.get(ConfigFile.CITIES)
                .getDouble("spawn.warmup-move-tolerance-blocks", 0.5);
        return to.distanceSquared(origin) > tolerance * tolerance;
    }

    public boolean isWarmingUp(Player player) {
        return warmups.containsKey(player.getUniqueId());
    }

    /** Milliseconds left before this player may teleport again. */
    public long cooldownRemaining(Player player) {
        if (player.hasPermission(BYPASS_COOLDOWN)) {
            return 0L;
        }
        Long last = lastTeleport.get(player.getUniqueId());
        if (last == null) {
            return 0L;
        }
        long cooldown = configs.get(ConfigFile.CITIES)
                .getLong("spawn.cooldown-seconds", 30) * 1000L;
        return Math.max(0L, last + cooldown - System.currentTimeMillis());
    }

    /**
     * How long this player must wait before the teleport fires.
     *
     * <p>SPEC 11.6 lengthens it during a war, which is why this is a method rather than a
     * constant read at the call site; M19 changes one line here.
     */
    private long warmupSeconds(Player player) {
        if (player.hasPermission(BYPASS_COOLDOWN)) {
            return 0L;
        }
        var cities = configs.get(ConfigFile.CITIES);
        return isAtWar(player)
                ? cities.getLong("spawn.war-warmup-seconds", 15)
                : cities.getLong("spawn.warmup-seconds", 5);
    }

    /** SPEC 11.6, once wars exist in M19. */
    private boolean isAtWar(Player player) {
        return false;
    }

    /** Forgets a player who has logged out. */
    public void forget(UUID player) {
        Warmup warmup = warmups.remove(player);
        if (warmup != null) {
            warmup.task().cancel();
        }
        lastTeleport.remove(player);
    }

    /** Stops every warmup, on plugin disable. */
    public void stopAll() {
        warmups.values().forEach(warmup -> warmup.task().cancel());
        warmups.clear();
    }

    /** SPEC 10: an operator or a donor rank may skip the wait. */
    public static final String BYPASS_COOLDOWN = "civitas.bypass.cooldown";

    private record Warmup(BukkitTask task, Location origin) { }
}
