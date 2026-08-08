package dev.civitas.core.waystation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.storage.dao.WaystationDao;
import dev.civitas.storage.row.WaystationChunkRow;
import dev.civitas.storage.row.WaystationRow;

/**
 * Every waystation and its chunks, in memory.
 *
 * <p>Cached for the reason SPEC 2.3 gives generally and one specific to this system: protection
 * asks "who owns this chunk" on every block event, and in a resource world the answer comes from
 * here. A database read on that path is the thing SPEC 17.7 case 81 exists to forbid.
 *
 * <p>Keyed by a {@code (world, x, z)} record rather than by {@code ChunkKey}'s packed long, for
 * the reason {@code MiningClaimRegistry} records: the packing needs a world-index allocator that
 * is private to {@code ClaimRegistry}, and a second allocator is a second thing that could
 * disagree about which world is index 3 — in a place where the consequence would be one city's
 * protection applied to another's ground. The allocation costs nothing here, because
 * {@code ProtectionService} asks the world kind first and a block broken in the main overworld
 * never reaches this map at all.
 */
public final class WaystationRegistry {

    /** A chunk, in the shape this registry keys on. */
    public record Position(String world, int chunkX, int chunkZ) {

        public Position {
            Objects.requireNonNull(world, "world");
        }
    }

    private final WaystationDao waystations;

    private final Map<Integer, Waystation> byId = new ConcurrentHashMap<>();
    private final Map<Position, Integer> owners = new ConcurrentHashMap<>();
    private final Map<Position, WaystationChunkRow> chunks = new ConcurrentHashMap<>();

    public WaystationRegistry(WaystationDao waystations) {
        this.waystations = Objects.requireNonNull(waystations, "waystations");
    }

    /** @return how many waystations exist */
    public CompletableFuture<Integer> loadAll() {
        return waystations.findAll().thenCompose(rows ->
                waystations.findAllChunks().thenApply(chunkRows -> {
                    byId.clear();
                    owners.clear();
                    chunks.clear();
                    for (WaystationRow row : rows) {
                        byId.put(row.id(), Waystation.from(row));
                    }
                    for (WaystationChunkRow chunk : chunkRows) {
                        Position at = new Position(chunk.world(), chunk.chunkX(), chunk.chunkZ());
                        owners.put(at, chunk.waystationId());
                        chunks.put(at, chunk);
                    }
                    return byId.size();
                }));
    }

    // ==================================================================================
    // Reads
    // ==================================================================================

    public Optional<Waystation> byId(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** A city's waystations, oldest first. At most one per resource world, so at most two. */
    public List<Waystation> of(int cityId) {
        List<Waystation> found = new ArrayList<>();
        for (Waystation waystation : byId.values()) {
            if (waystation.cityId() == cityId) {
                found.add(waystation);
            }
        }
        found.sort(Comparator.comparingLong(Waystation::createdAt)
                .thenComparingInt(Waystation::id));
        return found;
    }

    /** The city's waystation in one world, which SPEC 39.10 allows at most one of. */
    public Optional<Waystation> of(int cityId, String world) {
        return of(cityId).stream().filter(waystation -> waystation.isIn(world)).findFirst();
    }

    /** Which waystation owns this chunk, if any. The protection hot path. */
    public Optional<Waystation> at(String world, int chunkX, int chunkZ) {
        Integer id = owners.get(new Position(world, chunkX, chunkZ));
        return id == null ? Optional.empty() : byId(id);
    }

    /** Whether any waystation owns this chunk. Cheaper than {@link #at} when that is all. */
    public boolean isClaimed(String world, int chunkX, int chunkZ) {
        return owners.containsKey(new Position(world, chunkX, chunkZ));
    }

    /** A waystation's chunks, oldest first. */
    public List<WaystationChunkRow> chunksOf(int waystationId) {
        List<WaystationChunkRow> found = new ArrayList<>();
        for (WaystationChunkRow chunk : chunks.values()) {
            if (chunk.waystationId() == waystationId) {
                found.add(chunk);
            }
        }
        found.sort(Comparator.comparingLong(WaystationChunkRow::id));
        return found;
    }

    public int chunkCount(int waystationId) {
        int count = 0;
        for (WaystationChunkRow chunk : chunks.values()) {
            if (chunk.waystationId() == waystationId) {
                count++;
            }
        }
        return count;
    }

    // ==================================================================================
    // Writes, applied after the transaction commits
    // ==================================================================================

    public void put(Waystation waystation) {
        byId.put(waystation.id(), waystation);
    }

    public void putChunk(WaystationChunkRow chunk) {
        Position at = new Position(chunk.world(), chunk.chunkX(), chunk.chunkZ());
        owners.put(at, chunk.waystationId());
        chunks.put(at, chunk);
    }

    public void removeChunk(String world, int chunkX, int chunkZ) {
        Position at = new Position(world, chunkX, chunkZ);
        owners.remove(at);
        chunks.remove(at);
    }

    /** Drops a waystation and every chunk pointing at it. */
    public void remove(int waystationId) {
        byId.remove(waystationId);
        owners.entrySet().removeIf(entry -> entry.getValue() == waystationId);
        chunks.entrySet().removeIf(entry -> entry.getValue().waystationId() == waystationId);
    }

    /** Every waystation of a disbanded city, so the disband hook can clear them. */
    public void removeCity(int cityId) {
        of(cityId).forEach(waystation -> remove(waystation.id()));
    }
}
