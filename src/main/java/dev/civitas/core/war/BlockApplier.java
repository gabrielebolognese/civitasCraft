package dev.civitas.core.war;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Puts one block back, SPEC 11.8.2 steps 4, 5 and 7.
 *
 * <h2>Physics is suppressed, and it is not optional</h2>
 * Every restore uses {@code setBlockData(data, false)}. SPEC 11.8.2 step 4 is explicit about
 * why, and CLAUDE.md repeats it as a thing that will bite: physics during a rollback cascades.
 * Restore one sand block and it falls before its neighbour is placed; restore a water source
 * and it floods the hole the next entry was going to fill; restore a redstone block and the
 * circuit fires into a half-restored contraption. The restore ends up corrupting itself, and
 * every individual write looks correct.
 *
 * <p>The cost of suppressing it is that lighting and redstone are left stale, which SPEC
 * 11.8.2 step 7 fixes with a second pass over the <em>boundary</em> of what was restored.
 * Boundary only, deliberately: nudging the interior would start the cascade the first pass
 * spent its whole effort avoiding.
 */
public final class BlockApplier {

    private final Logger logger;

    /** Chunks this applier loaded and should release again, SPEC 17.4 case 49. */
    private final Set<Long> loadedByUs = new HashSet<>();

    /** Positions written, so the second pass knows where the boundary is. */
    private final Set<Long> restored = new HashSet<>();

    public BlockApplier(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Restores one position.
     *
     * <p>Runs on the server thread: it touches the world.
     *
     * @param blockData the {@code BlockData} string the log captured
     * @return whether the block was written
     */
    public boolean apply(World world, int x, int y, int z, String blockData) {
        if (world == null || blockData == null || blockData.isBlank()) {
            return false;
        }
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            // A log from a server whose world height has since changed. Skipping one block is
            // better than throwing away the rest of the rollback.
            return false;
        }

        try {
            ensureLoaded(world, x >> 4, z >> 4);
            BlockData data = Bukkit.createBlockData(blockData);
            Block block = world.getBlockAt(x, y, z);

            // The whole point. Never the physics-enabled overload.
            block.setBlockData(data, false);

            restored.add(pack(x, y, z));
            return true;
        } catch (IllegalArgumentException e) {
            // A block type this build no longer has, or a malformed string. One position is
            // lost; the rest of the city still comes back.
            logger.log(Level.WARNING, "Could not restore " + blockData + " at "
                    + x + "," + y + "," + z, e);
            return false;
        }
    }

    /**
     * SPEC 11.8.2 step 7: let lighting and redstone settle at the edges of what was restored.
     *
     * <p>Only positions with at least one neighbour that was <em>not</em> restored are
     * touched. Inside a restored region everything is already consistent with everything else,
     * and updating it would be the cascade this class exists to prevent.
     *
     * @return how many boundary blocks were updated
     */
    public int applyBoundaryPhysics(World world) {
        if (world == null || restored.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (long packed : restored) {
            int x = unpackX(packed);
            int y = unpackY(packed);
            int z = unpackZ(packed);
            if (!isBoundary(x, y, z)) {
                continue;
            }
            Block block = world.getBlockAt(x, y, z);
            // Rewriting the same data with physics on is how Bukkit asks for an update
            // without changing anything.
            block.setBlockData(block.getBlockData(), true);
            updated++;
        }
        return updated;
    }

    /** A position is on the boundary if any of its six neighbours was not restored. */
    private boolean isBoundary(int x, int y, int z) {
        return !restored.contains(pack(x + 1, y, z))
                || !restored.contains(pack(x - 1, y, z))
                || !restored.contains(pack(x, y + 1, z))
                || !restored.contains(pack(x, y - 1, z))
                || !restored.contains(pack(x, y, z + 1))
                || !restored.contains(pack(x, y, z - 1));
    }

    /**
     * SPEC 17.4 case 49: "Chunk is loaded on demand, restored, then unloaded. Never assume a
     * chunk is loaded."
     */
    private void ensureLoaded(World world, int chunkX, int chunkZ) {
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        world.getChunkAt(chunkX, chunkZ);
        loadedByUs.add(pack(chunkX, 0, chunkZ));
    }

    /**
     * Releases the chunks this applier loaded.
     *
     * <p>Only those: a chunk that was already loaded belongs to a player standing in it, and
     * unloading it out from under them is not this class's business.
     */
    public int releaseChunks(World world) {
        if (world == null) {
            return 0;
        }
        int released = 0;
        for (long packed : loadedByUs) {
            int chunkX = unpackX(packed);
            int chunkZ = unpackZ(packed);
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                world.unloadChunk(chunkX, chunkZ, true);
                released++;
            }
        }
        loadedByUs.clear();
        return released;
    }

    /**
     * Moves any player standing inside a restored block somewhere safe.
     *
     * <p>SPEC 17.4 case 50: "Restore anyway, then run a safe-location check on all nearby
     * players." The restore takes priority; a player who ends up inside a wall is moved, not
     * a reason to leave the wall missing.
     *
     * @return how many players were moved
     */
    public int freeTrappedPlayers(World world) {
        if (world == null) {
            return 0;
        }
        int moved = 0;
        for (var player : world.getPlayers()) {
            Location at = player.getLocation();
            if (!restored.contains(pack(at.getBlockX(), at.getBlockY(), at.getBlockZ()))) {
                continue;
            }
            if (at.getBlock().isPassable()) {
                continue;
            }
            Location safe = at.clone();
            safe.setY(world.getHighestBlockYAt(at.getBlockX(), at.getBlockZ()) + 1.0);
            player.teleport(safe);
            moved++;
        }
        return moved;
    }

    public int restoredCount() {
        return restored.size();
    }

    /** Frees the position set between pages, so a long rollback does not grow without bound. */
    public void clearRestored() {
        restored.clear();
    }

    // Packing matches ChunkKey's reasoning: 26 bits per horizontal axis is far more than a
    // world border allows, and Y needs only 12.
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static int unpackX(long packed) {
        return sign((int) (packed >> 38) & 0x3FFFFFF, 26);
    }

    private static int unpackY(long packed) {
        return sign((int) (packed >> 26) & 0xFFF, 12);
    }

    private static int unpackZ(long packed) {
        return sign((int) (packed & 0x3FFFFFF), 26);
    }

    private static int sign(int value, int bits) {
        int shift = 32 - bits;
        return (value << shift) >> shift;
    }
}
