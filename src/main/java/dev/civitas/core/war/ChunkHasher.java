package dev.civitas.core.war;

import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * The SPEC 11.8.4 failsafe: a checksum of a chunk, taken before a war and after its rollback.
 *
 * <h2>What it is for</h2>
 * SPEC 11.8.4 is unusually candid: "because no listener list is ever truly exhaustive". M17
 * covers every source SPEC 11.8.1 names, and that is still a list somebody wrote down. This
 * catches what the list missed.
 *
 * <p>It does not repair anything, and SPEC 11.8.4 says so directly: "This does not fix the
 * problem automatically, it makes the problem <em>visible</em>, which is the difference
 * between a bug players report as 'the plugin ate my house' and one an admin catches before
 * anyone notices."
 *
 * <h2>Cost</h2>
 * A full chunk is 16 x 16 x world height, which on a modern world is around 98,000 blocks. At
 * two hashes per chunk per war that is real work, so {@code war.rollback.chunk-hash-failsafe}
 * turns it off, and {@code chunk-hash-stride} samples every Nth block instead of every one. A
 * stride still detects a wall that vanished; it can miss a single block, which is the trade an
 * operator makes knowingly rather than one made for them.
 */
public final class ChunkHasher {

    private final ConfigManager configs;

    public ChunkHasher(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    public boolean isEnabled() {
        return configs.get(ConfigFile.WAR).getBoolean("rollback.chunk-hash-failsafe", true);
    }

    /** How many blocks are stepped over between samples. 1 hashes every block. */
    public int stride() {
        return Math.max(1, configs.get(ConfigFile.WAR).getInt("rollback.chunk-hash-stride", 4));
    }

    /**
     * Hashes one chunk.
     *
     * <p>Runs on the server thread: it reads the world. The chunk is loaded if it is not
     * already, because an unloaded chunk cannot be read and skipping it would leave a hole in
     * exactly the check that exists to find holes.
     *
     * <p>The mix is order-dependent on purpose. A checksum that added block values would give
     * the same answer for a wall moved one block sideways, which is precisely the kind of
     * damage this is meant to notice.
     */
    public long hash(World world, int chunkX, int chunkZ) {
        Objects.requireNonNull(world, "world");
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            world.getChunkAt(chunkX, chunkZ);
        }

        int step = stride();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        long hash = 1125899906842597L;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y += step) {
                    Block block = world.getBlockAt((chunkX << 4) + x, y, (chunkZ << 4) + z);
                    hash = hash * 31 + block.getType().ordinal();
                }
            }
        }
        return hash;
    }
}
