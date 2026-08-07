package dev.civitas.core.outpost;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.SpawnService;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Result;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Teleporting to an outpost, SPEC 7.2 and 7.4.
 *
 * <p>100 C, an 8-second warmup and a 3-minute cooldown, all longer than {@code /city spawn}'s
 * because an outpost is somewhere a player would otherwise have to walk to. Movement and
 * damage cancel it, exactly as they do for the city spawn, and for the same SPEC 17.6 case 77
 * reason: a teleport that cannot be interrupted is an escape button.
 *
 * <p>The fee is charged on arrival rather than on request. A player who is knocked out of the
 * warmup has not travelled and should not have paid.
 */
public final class OutpostTeleport {

    private final Plugin plugin;
    private final OutpostService outposts;
    private final EconomyService economy;
    private final ConfigManager configs;
    private final LangManager lang;

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTeleport = new ConcurrentHashMap<>();

    public OutpostTeleport(Plugin plugin, OutpostService outposts, EconomyService economy,
                           ConfigManager configs, LangManager lang) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.outposts = Objects.requireNonNull(outposts, "outposts");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    // ==================================================================================
    // Requesting
    // ==================================================================================

    /**
     * Starts the warmup, or refuses with a reason.
     *
     * @return the warmup in seconds once it has started
     */
    public Result<Long> request(Player player, City city, Outpost outpost) {
        if (!city.hasPermission(player.getUniqueId(), CityPermission.OUTPOST_TP)) {
            return Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.OUTPOST_TP.name()));
        }
        if (isCityAtWar(city)) {
            // SPEC 7.4: disabled entirely during a war, to prevent instant reinforcement.
            return Result.failure("CITY_AT_WAR", "outpost.tp-at-war");
        }
        if (pending.containsKey(player.getUniqueId())) {
            return Result.failure("ALREADY_WARMING", "city.spawn.already-warming");
        }

        long remaining = cooldownRemaining(player);
        if (remaining > 0) {
            return Result.failure("COOLDOWN", "outpost.tp-cooldown",
                    Map.of("seconds", String.valueOf((remaining + 999) / 1000)));
        }

        BigDecimal fee = teleportCost();
        if (economy.balanceOrZero(player.getUniqueId()).compareTo(fee) < 0) {
            return Result.failure("INSUFFICIENT_FUNDS", "economy.insufficient-funds",
                    Map.of("required", fee.toPlainString(),
                            "balance", economy.balanceOrZero(player.getUniqueId())
                                    .toPlainString(),
                            "short", fee.subtract(economy.balanceOrZero(player.getUniqueId()))
                                    .toPlainString()));
        }

        Optional<Location> destination = destinationOf(outpost);
        if (destination.isEmpty()) {
            return Result.failure("WORLD_NOT_LOADED", "outpost.world-missing");
        }

        long seconds = warmupSeconds(player);
        if (seconds <= 0) {
            complete(player, outpost, destination.get());
            return Result.success(0L);
        }

        Location origin = player.getLocation().clone();
        BukkitTask task = org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(player.getUniqueId());
            if (player.isOnline()) {
                complete(player, outpost, destinationOf(outpost).orElse(destination.get()));
            }
        }, seconds * 20L);

        pending.put(player.getUniqueId(), new Pending(task, origin));
        // "name", not "outpost": every message in the outpost section uses <name>, and these
        // two call sites were the only ones passing a different resolver. The message showed
        // the player a literal "<name>" instead of where they were going, from M10 until M23.
        lang.send(player, "outpost.tp-warmup",
                LangManager.placeholder("name", outpost.name()),
                LangManager.placeholder("seconds", String.valueOf(seconds)));
        return Result.success(seconds);
    }

    private void complete(Player player, Outpost outpost, Location destination) {
        BigDecimal fee = teleportCost();

        economy.take(player.getUniqueId(), fee, TransactionType.OUTPOST_TELEPORT_FEE, null,
                        "{\"outpost\":\"" + outpost.name() + "\"}")
                .whenComplete((result, error) -> org.bukkit.Bukkit.getScheduler()
                        .runTask(plugin, () -> {
                            if (error != null || result instanceof Result.Failure<BigDecimal>) {
                                // Charged nothing, so travelled nowhere.
                                lang.send(player, "outpost.tp-unpaid");
                                return;
                            }
                            player.teleport(safeLanding(destination));
                            lastTeleport.put(player.getUniqueId(), System.currentTimeMillis());
                            lang.send(player, "outpost.tp-arrived",
                                    LangManager.placeholder("name", outpost.name()));
                        }));
    }

    // ==================================================================================
    // Landing safely, SPEC 7.4
    // ==================================================================================

    /**
     * SPEC 7.4: an unsafe destination becomes the highest safe Y in the same chunk.
     *
     * <p>Unsafe means suffocating, standing in lava, or below the world. A warp point can
     * become unsafe long after it was set, because somebody built over it or the outpost was
     * flooded, and dropping a player into a wall is a worse answer than moving them.
     */
    public Location safeLanding(Location wanted) {
        if (isSafe(wanted)) {
            return wanted;
        }
        World world = wanted.getWorld();
        if (world == null) {
            return wanted;
        }

        int x = wanted.getBlockX();
        int z = wanted.getBlockZ();
        for (int y = world.getMaxHeight() - 2; y > world.getMinHeight(); y--) {
            Location candidate = new Location(world, x + 0.5, y, z + 0.5,
                    wanted.getYaw(), wanted.getPitch());
            if (isSafe(candidate)) {
                return candidate;
            }
        }
        return wanted;
    }

    private static boolean isSafe(Location location) {
        World world = location.getWorld();
        if (world == null || location.getY() < world.getMinHeight()
                || location.getY() > world.getMaxHeight()) {
            return false;
        }
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);

        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }
        if (isDeadly(feet.getType()) || isDeadly(head.getType())) {
            return false;
        }
        return ground.getType().isSolid();
    }

    private static boolean isDeadly(Material material) {
        return material == Material.LAVA || material == Material.FIRE
                || material == Material.CAMPFIRE || material == Material.SOUL_FIRE
                || material == Material.MAGMA_BLOCK;
    }

    // ==================================================================================
    // Cancelling
    // ==================================================================================

    public boolean cancel(Player player, String messageKey) {
        Pending waiting = pending.remove(player.getUniqueId());
        if (waiting == null) {
            return false;
        }
        waiting.task().cancel();
        if (messageKey != null) {
            lang.send(player, messageKey);
        }
        return true;
    }

    public boolean isWarmingUp(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    /** Whether they have moved far enough from where the countdown started. */
    public boolean hasMovedAway(Player player, Location to) {
        Pending waiting = pending.get(player.getUniqueId());
        if (waiting == null || to == null || to.getWorld() == null) {
            return false;
        }
        Location origin = waiting.origin();
        if (!to.getWorld().equals(origin.getWorld())) {
            return true;
        }
        double tolerance = configs.get(ConfigFile.CITIES)
                .getDouble("spawn.warmup-move-tolerance-blocks", 0.5);
        return to.distanceSquared(origin) > tolerance * tolerance;
    }

    public void forget(UUID player) {
        Pending waiting = pending.remove(player);
        if (waiting != null) {
            waiting.task().cancel();
        }
        lastTeleport.remove(player);
    }

    public void stopAll() {
        pending.values().forEach(waiting -> waiting.task().cancel());
        pending.clear();
    }

    // ==================================================================================
    // Config and helpers
    // ==================================================================================

    public Optional<Location> destinationOf(Outpost outpost) {
        Optional<Claim> chunk = outposts.claimOf(outpost);
        if (chunk.isEmpty()) {
            return Optional.empty();
        }
        World world = org.bukkit.Bukkit.getWorld(chunk.get().world());
        return world == null
                ? Optional.empty()
                : Optional.of(new Location(world, outpost.warpX(), outpost.warpY(),
                        outpost.warpZ(), outpost.warpYaw(), outpost.warpPitch()));
    }

    public long cooldownRemaining(Player player) {
        if (player.hasPermission(SpawnService.BYPASS_COOLDOWN)) {
            return 0L;
        }
        Long last = lastTeleport.get(player.getUniqueId());
        if (last == null) {
            return 0L;
        }
        long cooldown = configs.get(ConfigFile.CITIES)
                .getLong("outposts.teleport-cooldown-seconds", 180) * 1000L;
        return Math.max(0L, last + cooldown - System.currentTimeMillis());
    }

    private long warmupSeconds(Player player) {
        if (player.hasPermission(SpawnService.BYPASS_COOLDOWN)) {
            return 0L;
        }
        return configs.get(ConfigFile.CITIES).getLong("outposts.teleport-warmup-seconds", 8);
    }

    public BigDecimal teleportCost() {
        return new BigDecimal(configs.get(ConfigFile.CITIES)
                .getString("outposts.teleport-cost", "100"));
    }

    /**
     * SPEC 7.4: "Outpost teleport is disabled entirely during a war involving that city, to
     * prevent instant reinforcement."
     *
     * <p>SPEC 11.6 repeats it from the other direction, listing "teleporting into or out of
     * the war zone via outpost warps" among the things that stay blocked even in war. A war
     * is meant to be fought across ground somebody has to cross.
     */
    private boolean isCityAtWar(City city) {
        return wars != null && wars.blocksOutposts(city.id());
    }

    private dev.civitas.core.war.WarRestrictions wars;

    /** SPEC 7.4 and 11.11, wired by M19. */
    public void useWars(dev.civitas.core.war.WarRestrictions restrictions) {
        this.wars = restrictions;
    }

    private record Pending(BukkitTask task, Location origin) { }
}
