package dev.civitas.core.mining;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.storage.dao.MiningClaimDao;
import dev.civitas.storage.row.MiningClaimRow;

/**
 * Every mining claim, in memory, SPEC 32.6.
 *
 * <p>Cache-first for the sharpest reason any registry here has: {@code ProtectionService} asks
 * "who owns this chunk" on <b>every block event</b>, and SPEC 17.7 case 81 requires that to stay
 * O(1). A database read there would be a query per block broken.
 *
 * <p>Keyed by a {@code (world, chunkX, chunkZ)} record rather than by {@code ChunkKey}'s packed
 * long. {@code ClaimRegistry}'s packing needs a world-index allocator that is private to it, and
 * a second allocator is a second thing that could disagree about which world is index 3. The
 * record costs one small allocation per lookup, which matters only if the lookup is on the hot
 * path — and it is not: {@code ProtectionService} asks the world kind first, so a block broken in
 * the main overworld never reaches this map at all.
 */
public final class MiningClaimRegistry {

    private final MiningClaimDao dao;
    private final Logger logger;

    /** One chunk, case-insensitively by world name. */
    private record ChunkRef(String world, int chunkX, int chunkZ) {

        static ChunkRef of(String world, int chunkX, int chunkZ) {
            return new ChunkRef(world.toLowerCase(java.util.Locale.ROOT), chunkX, chunkZ);
        }
    }

    private final Map<ChunkRef, MiningClaimRow> byChunk = new ConcurrentHashMap<>();

    /** Owner to their claims, for {@code /mine info} and the limit check. */
    private final Map<UUID, List<MiningClaimRow>> byOwner = new ConcurrentHashMap<>();

    /** Owner to whom they trust, SPEC 32.6. Per owner, not per claim: see the migration. */
    private final Map<UUID, Set<UUID>> trusted = new ConcurrentHashMap<>();

    public MiningClaimRegistry(MiningClaimDao dao, Logger logger) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // Loading
    // ==================================================================================

    /** Loads every claim and every trust grant. Called once, on storage ready. */
    public CompletableFuture<Integer> loadAll() {
        try {
            return dao.findAll()
                    .thenCombine(dao.findAllTrust(), (rows, grants) -> {
                        byChunk.clear();
                        byOwner.clear();
                        trusted.clear();
                        rows.forEach(this::remember);
                        grants.forEach((owner, list) ->
                                trusted.put(owner, ConcurrentHashMap.newKeySet()));
                        grants.forEach((owner, list) -> trusted.get(owner).addAll(list));
                        return byChunk.size();
                    })
                    .exceptionally(error -> {
                        // Failing empty would leave every mine base unprotected, so this is
                        // loud rather than quiet.
                        logger.log(Level.SEVERE, "Could not load mining claims. Mining claims "
                                + "will not be protected until this is resolved.", error);
                        return 0;
                    });
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not load mining claims. Mining claims will not be "
                    + "protected until this is resolved.", e);
            return CompletableFuture.completedFuture(0);
        }
    }

    // ==================================================================================
    // Asking, on the hot path
    // ==================================================================================

    /** Who owns this chunk, if anybody. O(1). */
    public Optional<MiningClaimRow> at(String world, int chunkX, int chunkZ) {
        if (world == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byChunk.get(ChunkRef.of(world, chunkX, chunkZ)));
    }

    /** Whether anybody has claimed this chunk. The question {@code PvpPolicy} asks. */
    public boolean isClaimed(String world, int chunkX, int chunkZ) {
        return at(world, chunkX, chunkZ).isPresent();
    }

    /**
     * Whether this player may build here: the owner, or somebody they trust.
     *
     * <p>SPEC 32.6 gives mining claims "full block and container protection, Part I 5.5 rules",
     * and one trust level rather than the twenty-two-flag bitmask a city has. A mining claim is
     * one chunk belonging to one player; ranks would be machinery with nothing to organise.
     */
    public boolean mayBuild(UUID player, String world, int chunkX, int chunkZ) {
        Optional<MiningClaimRow> claim = at(world, chunkX, chunkZ);
        if (claim.isEmpty()) {
            return true;
        }
        if (player == null) {
            return false;
        }
        UUID owner = claim.get().uuid();
        return owner.equals(player) || trustedBy(owner).contains(player);
    }

    /** What this player owns. */
    public List<MiningClaimRow> ownedBy(UUID player) {
        return List.copyOf(byOwner.getOrDefault(player, List.of()));
    }

    /** Whom this player trusts, SPEC 32.6. */
    public Set<UUID> trustedBy(UUID owner) {
        return Set.copyOf(trusted.getOrDefault(owner, Set.of()));
    }

    /** Every claim, for the upkeep sweep. */
    public List<MiningClaimRow> all() {
        return List.copyOf(byChunk.values());
    }

    public int count() {
        return byChunk.size();
    }

    // ==================================================================================
    // Mutating the cache
    // ==================================================================================

    /** Adds a claim to the cache, after the row has been written. */
    public void remember(MiningClaimRow row) {
        byChunk.put(ChunkRef.of(row.world(), row.chunkX(), row.chunkZ()), row);
        byOwner.compute(row.uuid(), (owner, existing) -> {
            List<MiningClaimRow> updated = existing == null
                    ? new java.util.ArrayList<>()
                    : new java.util.ArrayList<>(existing);
            updated.removeIf(claim -> claim.id() == row.id());
            updated.add(row);
            return List.copyOf(updated);
        });
    }

    /** Drops a claim from the cache, after the row has gone. */
    public void forget(MiningClaimRow row) {
        byChunk.remove(ChunkRef.of(row.world(), row.chunkX(), row.chunkZ()));
        byOwner.computeIfPresent(row.uuid(), (owner, existing) -> {
            List<MiningClaimRow> updated = new java.util.ArrayList<>(existing);
            updated.removeIf(claim -> claim.id() == row.id());
            return updated.isEmpty() ? null : List.copyOf(updated);
        });
    }

    public void rememberTrust(UUID owner, UUID player) {
        trusted.computeIfAbsent(owner, key -> ConcurrentHashMap.newKeySet()).add(player);
    }

    public void forgetTrust(UUID owner, UUID player) {
        trusted.computeIfPresent(owner, (key, set) -> {
            set.remove(player);
            return set.isEmpty() ? null : set;
        });
    }

}
