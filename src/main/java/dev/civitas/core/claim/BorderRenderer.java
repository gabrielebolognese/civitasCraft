package dev.civitas.core.claim;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Draws claim borders as particles, SPEC 6.3 and 6.5.
 *
 * <p>Two callers: {@code /city border}, which outlines every claim edge within
 * {@code claims.border-particle-radius} for {@code claims.border-particle-seconds}, and a
 * successful claim, which outlines the one chunk just bought for a shorter burst so the
 * player can see exactly what their money got them.
 *
 * <p>Particles are spawned per-player rather than for the world, so one player toggling
 * borders does not decorate the sky for everyone standing nearby. Only edges where ownership
 * actually changes are drawn: the inside of a 100-chunk city is not a grid of lines.
 */
public final class BorderRenderer {

    private static final int CHUNK_SIZE = 16;
    private static final long TICKS_PER_SECOND = 20L;
    /** How often the outline is redrawn; particles are short-lived, so it must repeat. */
    private static final long REDRAW_TICKS = 10L;

    private final Plugin plugin;
    private final ClaimRegistry claims;
    private final ConfigManager configs;
    private final Logger logger;

    private final java.util.Map<UUID, BukkitTask> active = new ConcurrentHashMap<>();

    public BorderRenderer(Plugin plugin, ClaimRegistry claims, ConfigManager configs, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Toggles the full border view for a player, SPEC 6.5.
     *
     * @return true if borders are now showing
     */
    public boolean toggle(Player player) {
        if (stop(player.getUniqueId())) {
            return false;
        }

        int seconds = configs.get(ConfigFile.CITIES).getInt("claims.border-particle-seconds", 60);
        int radius = configs.get(ConfigFile.CITIES).getInt("claims.border-particle-radius", 64);
        start(player, seconds, () -> drawNearbyBorders(player, radius));
        return true;
    }

    /** Outlines one chunk briefly, SPEC 6.3's confirmation that a claim landed. */
    public void highlightChunk(Player player, String world, int chunkX, int chunkZ) {
        int seconds = configs.get(ConfigFile.CITIES)
                .getInt("claims.claim-border-particle-seconds", 10);
        start(player, seconds, () -> drawChunkOutline(player, world, chunkX, chunkZ));
    }

    /** Stops any outline a player has running. Called on quit so tasks do not leak. */
    public boolean stop(UUID player) {
        BukkitTask task = active.remove(player);
        if (task == null) {
            return false;
        }
        task.cancel();
        return true;
    }

    /** Cancels every outline, for plugin disable. */
    public void stopAll() {
        active.values().forEach(BukkitTask::cancel);
        active.clear();
    }

    private void start(Player player, int seconds, Runnable draw) {
        stop(player.getUniqueId());

        long expiresAt = System.currentTimeMillis() + seconds * 1000L;
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || System.currentTimeMillis() >= expiresAt) {
                stop(player.getUniqueId());
                return;
            }
            try {
                draw.run();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Border rendering failed", e);
                stop(player.getUniqueId());
            }
        }, 0L, REDRAW_TICKS);

        active.put(player.getUniqueId(), task);
    }

    /**
     * Draws every ownership boundary inside {@code radius} blocks of the player.
     *
     * <p>An edge is drawn when the chunk on one side is owned and the chunk on the other is
     * owned by somebody else, or by nobody. Interior edges are skipped, which is what keeps
     * this cheap inside a large city.
     */
    private void drawNearbyBorders(Player player, int radius) {
        World world = player.getWorld();
        String worldName = world.getName();
        Location eye = player.getLocation();

        int chunkRadius = Math.max(1, radius / CHUNK_SIZE + 1);
        int centreX = ChunkKey.toChunk(eye.getBlockX());
        int centreZ = ChunkKey.toChunk(eye.getBlockZ());

        Set<Long> drawn = new HashSet<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                int chunkX = centreX + dx;
                int chunkZ = centreZ + dz;
                Optional<Claim> here = claims.at(worldName, chunkX, chunkZ);
                if (here.isEmpty()) {
                    continue;
                }
                int owner = here.get().cityId();

                drawEdgeIfBoundary(player, worldName, chunkX, chunkZ, owner, 1, 0, radius, drawn);
                drawEdgeIfBoundary(player, worldName, chunkX, chunkZ, owner, -1, 0, radius, drawn);
                drawEdgeIfBoundary(player, worldName, chunkX, chunkZ, owner, 0, 1, radius, drawn);
                drawEdgeIfBoundary(player, worldName, chunkX, chunkZ, owner, 0, -1, radius, drawn);
            }
        }
    }

    private void drawEdgeIfBoundary(Player player, String world, int chunkX, int chunkZ,
                                    int owner, int dx, int dz, int radius, Set<Long> drawn) {
        Optional<Claim> neighbour = claims.at(world, chunkX + dx, chunkZ + dz);
        if (neighbour.isPresent() && neighbour.get().cityId() == owner) {
            return;
        }

        // Each shared edge belongs to two chunks; key it by the lower one so it is drawn once.
        long edgeKey = edgeKey(chunkX, chunkZ, dx, dz);
        if (!drawn.add(edgeKey)) {
            return;
        }
        drawEdge(player, chunkX, chunkZ, dx, dz, radius);
    }

    private void drawChunkOutline(Player player, String world, int chunkX, int chunkZ) {
        if (!player.getWorld().getName().equalsIgnoreCase(world)) {
            return;
        }
        int radius = Integer.MAX_VALUE;
        drawEdge(player, chunkX, chunkZ, 1, 0, radius);
        drawEdge(player, chunkX, chunkZ, -1, 0, radius);
        drawEdge(player, chunkX, chunkZ, 0, 1, radius);
        drawEdge(player, chunkX, chunkZ, 0, -1, radius);
    }

    /**
     * Spawns particles along one edge of a chunk, at the player's own height.
     *
     * <p>Drawn at the viewer's Y rather than at the terrain surface: the point is to see the
     * line, and following the ground would hide it in a valley and float it over a hill.
     */
    private void drawEdge(Player player, int chunkX, int chunkZ, int dx, int dz, int radius) {
        Particle particle = particleType();
        World world = player.getWorld();
        double y = player.getLocation().getY() + 1.0;

        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;

        for (int step = 0; step <= CHUNK_SIZE; step++) {
            double x;
            double z;
            if (dx != 0) {
                x = dx > 0 ? baseX + CHUNK_SIZE : baseX;
                z = baseZ + step;
            } else {
                x = baseX + step;
                z = dz > 0 ? baseZ + CHUNK_SIZE : baseZ;
            }

            Location point = new Location(world, x, y, z);
            if (radius != Integer.MAX_VALUE
                    && point.distanceSquared(player.getLocation()) > (double) radius * radius) {
                continue;
            }
            player.spawnParticle(particle, point, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private Particle particleType() {
        String name = configs.get(ConfigFile.CITIES)
                .getString("claims.border-particle", "HAPPY_VILLAGER");
        try {
            return Particle.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.log(Level.WARNING,
                    "cities.yml claims.border-particle is not a particle: {0}. Using HAPPY_VILLAGER.",
                    name);
            return Particle.HAPPY_VILLAGER;
        }
    }

    /** Canonical key for a shared edge, taken from the lower of the two chunks. */
    private static long edgeKey(int chunkX, int chunkZ, int dx, int dz) {
        int x = dx < 0 ? chunkX - 1 : chunkX;
        int z = dz < 0 ? chunkZ - 1 : chunkZ;
        int axis = dx != 0 ? 0 : 1;
        return (((long) x & 0xFFFFFFFFL) << 33) | (((long) z & 0x7FFFFFFFL) << 1) | axis;
    }

    /** Seconds to ticks, for callers that schedule off this renderer's configuration. */
    public static long toTicks(int seconds) {
        return seconds * TICKS_PER_SECOND;
    }
}
