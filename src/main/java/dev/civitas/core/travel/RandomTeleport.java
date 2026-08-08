package dev.civitas.core.travel;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.claim.ClaimRegistry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * SPEC 32.4's {@code /rtp}.
 *
 * <p>With no world border (SPEC 32.3), this is what gives the map its shape. SPEC 32.4:
 * "Structure comes from where the game <b>sends</b> people, not from where it <b>stops</b>
 * them." Inside the radius is the settled core, where every player who ever used {@code /rtp}
 * began; beyond it is frontier reachable only by deliberate travel.
 *
 * <h2>Where it must never put somebody</h2>
 *
 * <p>SPEC 32.4's table, and each is a separate rejection so a diagnostic can say which:
 * inside any claim, inside a claim buffer, inside an outpost, inside an admin-protected region,
 * within 200 blocks of another player, or anywhere unsafe.
 *
 * <h2>Off the main thread</h2>
 *
 * <p>Forty candidates, each needing a chunk that is almost certainly not loaded. Loading them
 * synchronously would generate terrain on the server thread forty times over, which is the kind
 * of stall that shows up as "the server froze when someone ran /rtp". Chunks load through
 * Paper's async loader and the block inspection happens on the main thread once each arrives,
 * the same async-then-main discipline the storage layer follows.
 */
public final class RandomTeleport {

    /** Why one candidate was rejected. Separate values so a diagnostic can name the reason. */
    public enum Rejection {
        CLAIMED,
        CLAIM_BUFFER,
        ADMIN_PROTECTED,
        NEAR_PLAYER,
        UNSAFE,
        ACCEPTED
    }

    private final ConfigManager configs;
    private final ClaimRegistry claims;
    private final org.bukkit.plugin.Plugin plugin;

    private ChunkTest adminProtected = (world, x, z) -> false;

    /** A question about one chunk, injected so this class needs no admin storage. */
    @FunctionalInterface
    public interface ChunkTest {

        boolean test(String world, int chunkX, int chunkZ);
    }

    /**
     * @param plugin needed only by {@link #find}, which schedules work; the rule methods are
     *               pure and a test that only exercises those may pass null
     */
    public RandomTeleport(org.bukkit.plugin.Plugin plugin, ConfigManager configs,
                          ClaimRegistry claims) {
        this.plugin = plugin;
        this.configs = Objects.requireNonNull(configs, "configs");
        this.claims = Objects.requireNonNull(claims, "claims");
    }

    public void useAdminProtection(ChunkTest protectedChunks) {
        this.adminProtected = Objects.requireNonNull(protectedChunks, "protectedChunks");
    }

    // ==================================================================================
    // The rules, without a server
    // ==================================================================================

    /**
     * Whether a chunk is somewhere a player may be dropped, ignoring what is in it.
     *
     * <p>Pure: everything it needs is a claim lookup and a list of where people are standing.
     * The block-level safety check needs the chunk loaded and lives in {@link #isSafe}.
     *
     * @param others where other players currently are, as {@code [world, x, z]} triples
     */
    public Rejection judgeChunk(String world, int chunkX, int chunkZ,
                                List<Object[]> others) {
        if (claims.at(world, chunkX, chunkZ).isPresent()) {
            return Rejection.CLAIMED;
        }
        int buffer = bufferChunks();
        // City id 0 belongs to nobody, so every claim counts as foreign and the buffer around
        // all of them is respected — which is what SPEC 32.4 asks for: "never lands inside any
        // claim, any claim buffer, any outpost".
        if (buffer > 0 && claims.isForeignLandWithin(world, chunkX, chunkZ, buffer, 0)) {
            return Rejection.CLAIM_BUFFER;
        }
        if (adminProtected.test(world, chunkX, chunkZ)) {
            return Rejection.ADMIN_PROTECTED;
        }

        int centreX = (chunkX << 4) + 8;
        int centreZ = (chunkZ << 4) + 8;
        int minimum = minimumPlayerDistance();
        for (Object[] other : others) {
            if (!String.valueOf(other[0]).equalsIgnoreCase(world)) {
                continue;
            }
            long dx = centreX - (int) other[1];
            long dz = centreZ - (int) other[2];
            if (dx * dx + dz * dz < (long) minimum * minimum) {
                return Rejection.NEAR_PLAYER;
            }
        }
        return Rejection.ACCEPTED;
    }

    /**
     * Whether a player can stand here without dying, SPEC 32.4's "safe location".
     *
     * <p>"Solid ground, breathable, not in lava, not in the void, sky access preferred." Sky
     * access is the only soft one, and it is what stops {@code /rtp} dropping somebody into a
     * sealed cave a hundred blocks down.
     */
    public static boolean isSafe(World world, int x, int z) {
        int surface = world.getHighestBlockYAt(x, z);
        if (surface <= world.getMinHeight() || surface >= world.getMaxHeight() - 2) {
            return false;
        }
        Block ground = world.getBlockAt(x, surface, z);
        Block feet = world.getBlockAt(x, surface + 1, z);
        Block head = world.getBlockAt(x, surface + 2, z);

        if (!ground.getType().isSolid()) {
            // Water, lava, or the top of a plant. None of them is footing.
            return false;
        }
        if (isDeadly(ground.getType()) || isDeadly(feet.getType())
                || isDeadly(head.getType())) {
            return false;
        }
        return feet.getType().isAir() && head.getType().isAir();
    }

    private static boolean isDeadly(Material material) {
        return material == Material.LAVA || material == Material.FIRE
                || material == Material.SOUL_FIRE || material == Material.MAGMA_BLOCK
                || material == Material.CACTUS || material == Material.POWDER_SNOW
                || material == Material.WITHER_ROSE || material == Material.SWEET_BERRY_BUSH;
    }

    /** Where a player stands on top of the block at {@code (x, z)}, centred and facing out. */
    public static Location standingOn(World world, int x, int z) {
        return new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1.0, z + 0.5);
    }

    // ==================================================================================
    // The search
    // ==================================================================================

    /**
     * Looks for somewhere to drop a player, giving up after the configured number of tries.
     *
     * <p>SPEC 32.4: "If 40 candidate locations fail validation, report honestly and refund."
     * Empty is that honest report — the caller has not charged anything yet, because
     * {@code TeleportService} charges on arrival.
     */
    public CompletableFuture<Optional<Location>> find(World world, int radius,
                                                      List<Object[]> others) {
        Objects.requireNonNull(plugin, "a plugin is required to run the search");
        return attempt(world, radius, others, maxAttempts(), new CompletableFuture<>());
    }

    private CompletableFuture<Optional<Location>> attempt(World world, int radius,
                                                          List<Object[]> others, int left,
                                                          CompletableFuture<Optional<Location>>
                                                                  result) {
        if (left <= 0) {
            result.complete(Optional.empty());
            return result;
        }
        int x = ThreadLocalRandom.current().nextInt(-radius, radius + 1);
        int z = ThreadLocalRandom.current().nextInt(-radius, radius + 1);

        if (judgeChunk(world.getName(), x >> 4, z >> 4, others) != Rejection.ACCEPTED) {
            // Rejected without loading anything, which is the cheap majority of the failures
            // near a settled core.
            return attempt(world, radius, others, left - 1, result);
        }

        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk ->
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    if (isSafe(world, x, z)) {
                        result.complete(Optional.of(standingOn(world, x, z)));
                        return;
                    }
                    attempt(world, radius, others, left - 1, result);
                })).exceptionally(error -> {
                    // A chunk that will not load is a rejected candidate, not a failed search.
                    org.bukkit.Bukkit.getScheduler().runTask(plugin,
                            () -> attempt(world, radius, others, left - 1, result));
                    return null;
                });
        return result;
    }

    /** Where everyone currently is, for the SPEC 32.4 minimum distance. */
    public static List<Object[]> positionsOf(Iterable<? extends Player> players, Player except) {
        List<Object[]> positions = new java.util.ArrayList<>();
        for (Player player : players) {
            if (player.equals(except)) {
                continue;
            }
            Location at = player.getLocation();
            positions.add(new Object[] {at.getWorld().getName(), at.getBlockX(),
                    at.getBlockZ()});
        }
        return positions;
    }

    // ==================================================================================
    // Configuration
    // ==================================================================================

    /** SPEC 32.4's 15,000 blocks: the edge of the settled core, not a wall. */
    public int maxRadius() {
        return configs.get(ConfigFile.WORLD).getInt("travel.rtp.max-radius", 15_000);
    }

    /** SPEC 32.4's 25,000 for the resource worlds, "to slow the depletion of ground". */
    public int resourceMaxRadius() {
        return configs.get(ConfigFile.WORLD)
                .getInt("travel.rtp.resource-max-radius", 25_000);
    }

    public int minimumPlayerDistance() {
        return configs.get(ConfigFile.WORLD).getInt("travel.rtp.min-player-distance", 200);
    }

    public int maxAttempts() {
        return configs.get(ConfigFile.WORLD).getInt("travel.rtp.max-attempts", 40);
    }

    /** SPEC 16.2's claim buffer, reused so {@code /rtp} respects the same distance a claim does. */
    public int bufferChunks() {
        return configs.get(ConfigFile.CITIES).getInt("claims.buffer-chunks", 5);
    }
}
