package dev.civitas.core.abuse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;

/**
 * What a player put there, SPEC 21.10.5.
 *
 * <p>Closes two quest exploits that are the same exploit twice. SPEC 21.4 F9: "'Place 512
 * blocks' completed by placing and breaking one block 512 times." F10: "'Mine 128 iron ore'
 * completed by placing and mining the same ore repeatedly."
 *
 * <p>The rule SPEC gives: "Blocks placed by a player are tagged in a per-chunk placed-block
 * cache. Breaking a player-placed block does not count for mining quests, and re-placing in a
 * recently-broken position does not count for building quests. Cache TTL 24 hours."
 *
 * <h2>Two facts per position, not one</h2>
 *
 * <p>It is tempting to remember only what a player placed. That closes F10 and leaves F9 open,
 * because the building half of the loop is the <b>place</b>, and at the moment of placing there
 * is nothing there to have been tagged. So a broken position is remembered too, and re-placing
 * into it earns nothing until the memory expires.
 *
 * <h2>Bounded, because this is on the hottest path in the plugin</h2>
 *
 * <p>Every block break and place consults it. SPEC 21.10.5 asks for "memory-bounded with LRU
 * eviction", so chunks are held in access order and the least recently touched is dropped once
 * the cap is reached. Eviction is safe in the direction that matters: a forgotten position
 * <b>counts</b>, so the worst case is a player getting quest credit they marginally should not
 * have, never a player robbed of credit they earned.
 *
 * <p>Not persisted, deliberately. A restart forgets, which is the same safe direction, and
 * writing every block placement to disk would be the war block logger again for a quest.
 */
public final class PlacedBlockCache {

    /** One chunk, identified by world and chunk coordinates. */
    private record ChunkRef(String world, int chunkX, int chunkZ) {
    }

    /** What happened at each position in one chunk, and when. */
    private static final class ChunkEntry {

        private final Map<Long, Long> placed = new java.util.HashMap<>();
        private final Map<Long, Long> broken = new java.util.HashMap<>();

        private boolean isEmpty() {
            return placed.isEmpty() && broken.isEmpty();
        }

        private void expire(long before) {
            placed.values().removeIf(stamp -> stamp < before);
            broken.values().removeIf(stamp -> stamp < before);
        }
    }

    private final ConfigManager configs;

    /**
     * Chunks in access order, oldest first, so the eldest is the one to drop.
     *
     * <p>Guarded by {@code this} rather than made concurrent: {@code LinkedHashMap} in access
     * order mutates its ordering on a <b>read</b>, so even a get is a write here and a
     * concurrent map would not help.
     */
    private final LinkedHashMap<ChunkRef, ChunkEntry> chunks =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ChunkRef, ChunkEntry> eldest) {
                    return size() > maxChunks();
                }
            };

    public PlacedBlockCache(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    // ==================================================================================
    // The two questions F9 and F10 ask
    // ==================================================================================

    /**
     * Records a placement and answers whether it should count toward a building quest.
     *
     * @return false if a block was recently broken here, so this is the F9 loop
     */
    public synchronized boolean onPlace(String world, int x, int y, int z, long now) {
        ChunkEntry entry = entryFor(world, x, z, now);
        long position = position(x, y, z);

        boolean refilling = entry.broken.containsKey(position);
        entry.placed.put(position, now);
        // Placing over a break ends that break's relevance: the position is now occupied by
        // something a player put there, which is what the next break needs to know.
        entry.broken.remove(position);
        return !refilling;
    }

    /**
     * Records a break and answers whether it should count toward a mining or breaking quest.
     *
     * @return false if a player placed this block, so this is the F10 loop
     */
    public synchronized boolean onBreak(String world, int x, int y, int z, long now) {
        ChunkEntry entry = entryFor(world, x, z, now);
        long position = position(x, y, z);

        boolean playerPlaced = entry.placed.remove(position) != null;
        entry.broken.put(position, now);
        return !playerPlaced;
    }

    // ==================================================================================
    // Reading, for the contest verification SPEC 21.10.5 mentions and for the tests
    // ==================================================================================

    /** Whether a player put a block here inside the TTL. */
    public synchronized boolean wasPlayerPlaced(String world, int x, int y, int z, long now) {
        ChunkEntry entry = chunks.get(new ChunkRef(world, x >> 4, z >> 4));
        if (entry == null) {
            return false;
        }
        entry.expire(now - ttlMillis());
        return entry.placed.containsKey(position(x, y, z));
    }

    /** Whether a block was broken here inside the TTL. */
    public synchronized boolean wasRecentlyBroken(String world, int x, int y, int z, long now) {
        ChunkEntry entry = chunks.get(new ChunkRef(world, x >> 4, z >> 4));
        if (entry == null) {
            return false;
        }
        entry.expire(now - ttlMillis());
        return entry.broken.containsKey(position(x, y, z));
    }

    /** How many chunks are held. For {@code /ca perf} and for the eviction test. */
    public synchronized int trackedChunks() {
        return chunks.size();
    }

    /** Drops everything. Used on reload and by tests. */
    public synchronized void clear() {
        chunks.clear();
    }

    /**
     * Drops expired positions everywhere, and any chunk left with nothing in it.
     *
     * <p>Reads already expire the chunk they touch, so this exists for the chunks nobody has
     * touched since: without it a quiet corner of the map would hold yesterday's positions
     * until LRU pressure evicted it, which on a small server might be never.
     */
    public synchronized int sweep(long now) {
        long before = now - ttlMillis();
        int dropped = 0;
        var iterator = chunks.entrySet().iterator();
        while (iterator.hasNext()) {
            ChunkEntry entry = iterator.next().getValue();
            entry.expire(before);
            if (entry.isEmpty()) {
                iterator.remove();
                dropped++;
            }
        }
        return dropped;
    }

    // ==================================================================================
    // Internals
    // ==================================================================================

    private ChunkEntry entryFor(String world, int x, int z, long now) {
        ChunkEntry entry = chunks.computeIfAbsent(new ChunkRef(world, x >> 4, z >> 4),
                key -> new ChunkEntry());
        entry.expire(now - ttlMillis());
        return entry;
    }

    /** A position inside its chunk. The chunk is already identified by the key above. */
    private static long position(int x, int y, int z) {
        return ((long) y << 8) | ((x & 15) << 4) | (z & 15);
    }

    /** SPEC 21.11 {@code anti-abuse.placed-block-cache-ttl-hours}, default 24. */
    public long ttlMillis() {
        return configs.get(ConfigFile.ECONOMY)
                .getLong("anti-abuse.placed-block-cache-ttl-hours", 24) * 3_600_000L;
    }

    /**
     * How many chunks to remember.
     *
     * <p>SPEC 21.10.5 requires the cache to be memory-bounded and names no bound, so this
     * number is this implementation's. At 4,096 chunks it covers a 64x64 chunk area of active
     * building, which is far more than any one server is working on at once, and costs a few
     * megabytes at worst.
     */
    public int maxChunks() {
        return configs.get(ConfigFile.ECONOMY)
                .getInt("anti-abuse.placed-block-cache-max-chunks", 4096);
    }
}
