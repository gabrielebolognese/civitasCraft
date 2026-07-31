package dev.civitas.core.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import dev.civitas.storage.dao.ClaimDao;
import dev.civitas.storage.row.ClaimRow;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The in-memory claim cache, SPEC 2.3.
 *
 * <p>"Claims are stored in a {@code Long2ObjectMap<Claim>} keyed by a packed
 * {@code (worldId, chunkX, chunkZ)} long for O(1) lookup, because this is queried on every
 * block event." From M4 onward {@link #at} runs on every block break, block place, bucket
 * use and interaction on the server, so it does a single primitive-keyed hash lookup and
 * never touches the database.
 *
 * <p>A second index by city id backs the operations that ask about a whole city, such as the
 * contiguity fill and the claim count, which would otherwise mean scanning every claim on
 * the server.
 */
public final class ClaimRegistry {

    private final ClaimDao claims;

    /** The hot path: packed chunk key to claim. */
    private final Long2ObjectMap<Claim> byChunk =
            Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());

    /** Secondary index so "every claim of this city" does not scan the world. */
    private final Map<Integer, Set<Long>> byCity = new ConcurrentHashMap<>();

    /** World name to the small index packed into the key. Session-scoped, never persisted. */
    private final Map<String, Integer> worldIndices = new ConcurrentHashMap<>();
    private final AtomicInteger nextWorldIndex = new AtomicInteger();

    public ClaimRegistry(ClaimDao claims) {
        this.claims = Objects.requireNonNull(claims, "claims");
    }

    /**
     * Loads every claim on the server.
     *
     * <p>One query, at startup, off the main thread. SPEC 2.3 makes the database a
     * persistence target rather than a read path, so this is the only time claims are read
     * from it during normal operation.
     *
     * @return how many claims were loaded
     */
    public CompletableFuture<Integer> loadAll() {
        return claims.findAll().thenApply(rows -> {
            clear();
            for (ClaimRow row : rows) {
                put(Claim.fromRow(row));
            }
            return byChunk.size();
        });
    }

    // --- the hot path -----------------------------------------------------------------

    /**
     * Who owns this chunk.
     *
     * @return the claim, or empty for wilderness
     */
    public Optional<Claim> at(String world, int chunkX, int chunkZ) {
        Integer index = worldIndices.get(key(world));
        if (index == null || !ChunkKey.isInRange(chunkX) || !ChunkKey.isInRange(chunkZ)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byChunk.get(ChunkKey.pack(index, chunkX, chunkZ)));
    }

    /** Convenience for block coordinates, which is what every listener actually has. */
    public Optional<Claim> atBlock(String world, int blockX, int blockZ) {
        return at(world, ChunkKey.toChunk(blockX), ChunkKey.toChunk(blockZ));
    }

    public boolean isClaimed(String world, int chunkX, int chunkZ) {
        return at(world, chunkX, chunkZ).isPresent();
    }

    /** The city owning a chunk, or empty for wilderness. */
    public Optional<Integer> ownerOf(String world, int chunkX, int chunkZ) {
        return at(world, chunkX, chunkZ).map(Claim::cityId);
    }

    // --- per-city views ---------------------------------------------------------------

    public Collection<Claim> claimsOf(int cityId) {
        Set<Long> keys = byCity.get(cityId);
        if (keys == null) {
            return List.of();
        }
        List<Claim> found = new ArrayList<>(keys.size());
        for (long key : keys) {
            Claim claim = byChunk.get(key);
            if (claim != null) {
                found.add(claim);
            }
        }
        return Collections.unmodifiableList(found);
    }

    /** How many chunks a city holds, which is also the index of the next one it buys. */
    public int countOf(int cityId) {
        Set<Long> keys = byCity.get(cityId);
        return keys == null ? 0 : keys.size();
    }

    /**
     * A city's contiguous chunks in one world, the input to the SPEC 6.1 flood-fill.
     *
     * <p>Outposts are excluded: SPEC 6.1 says the invariant covers non-outpost claims only,
     * since an outpost is detached by definition.
     */
    public Set<Contiguity.Chunk> contiguousChunksOf(int cityId, String world) {
        Set<Contiguity.Chunk> chunks = new LinkedHashSet<>();
        for (Claim claim : claimsOf(cityId)) {
            if (claim.type().isContiguous() && claim.world().equalsIgnoreCase(world)) {
                chunks.add(new Contiguity.Chunk(claim.chunkX(), claim.chunkZ()));
            }
        }
        return chunks;
    }

    /**
     * Whether any city other than {@code excludeCityId} holds land within {@code radius}
     * chunks, the SPEC 6.3 precondition 5 buffer.
     *
     * <p>Scans the square rather than every claim, so the cost depends on the buffer size and
     * not on how much land the server has sold. At the default buffer of 5 that is 121
     * lookups.
     */
    public boolean isForeignLandWithin(String world, int chunkX, int chunkZ, int radius,
                                       int excludeCityId) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Optional<Claim> claim = at(world, chunkX + dx, chunkZ + dz);
                if (claim.isPresent() && claim.get().cityId() != excludeCityId) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every world this registry has seen a claim in. */
    public Set<String> knownWorlds() {
        return Collections.unmodifiableSet(worldIndices.keySet());
    }

    public int size() {
        return byChunk.size();
    }

    // --- mutation, called only by ClaimService ----------------------------------------

    void put(Claim claim) {
        long key = ChunkKey.pack(worldIndexOf(claim.world()), claim.chunkX(), claim.chunkZ());
        byChunk.put(key, claim);
        byCity.computeIfAbsent(claim.cityId(), id -> ConcurrentHashMap.newKeySet()).add(key);
    }

    void remove(Claim claim) {
        Integer index = worldIndices.get(key(claim.world()));
        if (index == null) {
            return;
        }
        long chunkKey = ChunkKey.pack(index, claim.chunkX(), claim.chunkZ());
        byChunk.remove(chunkKey);
        Set<Long> keys = byCity.get(claim.cityId());
        if (keys != null) {
            keys.remove(chunkKey);
            if (keys.isEmpty()) {
                byCity.remove(claim.cityId());
            }
        }
    }

    /** Drops every claim of a city, used when it is disbanded or admin-purged. */
    void removeCity(int cityId) {
        Set<Long> keys = byCity.remove(cityId);
        if (keys != null) {
            for (long key : keys) {
                byChunk.remove(key);
            }
        }
    }

    /** Re-indexes a claim whose owning city changed, SPEC 9.4.3 {@code /ca claim transfer}. */
    void reassign(Claim claim, int newCityId) {
        remove(claim);
        put(new Claim(claim.id(), newCityId, claim.world(), claim.chunkX(), claim.chunkZ(),
                claim.claimedAt(), claim.claimedBy(), claim.costPaid(), claim.type(),
                claim.outpostId()));
    }

    void clear() {
        byChunk.clear();
        byCity.clear();
        worldIndices.clear();
        nextWorldIndex.set(0);
    }

    /**
     * Assigns a world its packed index on first sight.
     *
     * @throws IllegalStateException if the server has more worlds than the key can address,
     *                               which is loud rather than silently aliasing two worlds
     */
    private int worldIndexOf(String world) {
        return worldIndices.computeIfAbsent(key(world), name -> {
            int index = nextWorldIndex.getAndIncrement();
            if (index >= ChunkKey.MAX_WORLDS) {
                throw new IllegalStateException("More than " + ChunkKey.MAX_WORLDS
                        + " worlds hold claims; the packed chunk key cannot address them all.");
            }
            return index;
        });
    }

    /** World names are matched case-insensitively, as Bukkit treats them. */
    private static String key(String world) {
        return world.toLowerCase(Locale.ROOT);
    }
}
