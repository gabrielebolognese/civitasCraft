package dev.civitas.core.war;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

/**
 * Captures and restores whatever a block holds beyond its {@code BlockData}.
 *
 * <h2>Why this is an interface</h2>
 * SPEC 11.8.1 says to "serialize the full NBT to {@code old_nbt} using Paper's
 * {@code BlockState} snapshot serialization". <strong>No such API exists.</strong> The
 * paper-api 1.21.11 jar contains no class matching {@code nbt}; {@code TileState} exposes only
 * {@code isSnapshot} and the plugin-owned {@code PersistentDataContainer}, and
 * {@code BlockState} exposes only {@code copy}. Vanilla NBT is reachable from a plugin only
 * through NMS, which SPEC 2.1 forbids unless unavoidable.
 *
 * <p>So the capture is an interface with a per-type Bukkit implementation
 * ({@link BukkitTilePayloadCodec}) that reads what the API does expose. That covers every
 * element of SPEC 18.3's manual protocol except the individual bees inside a hive, which
 * Bukkit's {@code EntityBlockStorage} counts but will not hand over.
 *
 * <p>An NMS-backed or library-backed implementation can replace it without touching the
 * logger, the listeners, or the rollback engine that reads what this wrote. That is the whole
 * reason it is an interface rather than a static helper.
 */
public interface TilePayloadCodec {

    /**
     * Captures the extra state of a block about to change.
     *
     * @return the serialized payload, or {@code null} for a block that carries nothing beyond
     *         its {@code BlockData}. Null is the overwhelmingly common case: stone, dirt and
     *         every other plain block go through the log with no payload at all.
     */
    byte[] capture(BlockState state);

    /**
     * Puts a captured payload back, after the block itself has been restored.
     *
     * <p>Called by M18's rollback. Applying a payload to a block whose type does not match the
     * one it came from must be a no-op rather than an error: the log is replayed in reverse
     * and a mismatch means an earlier entry has not been applied yet.
     *
     * @return whether anything was restored
     */
    boolean restore(Block block, byte[] payload);

    /** Whether this block type carries anything worth capturing. */
    boolean hasPayload(BlockState state);

    /**
     * What this implementation cannot capture, for {@code /ca war verify} and for the
     * operator-facing warning at startup.
     *
     * <p>Honesty here is the point. A codec that silently drops a hive's bees and reports
     * nothing turns SPEC 18.3 step 8 into a test that passes while being wrong.
     */
    java.util.List<String> knownLimitations();
}
