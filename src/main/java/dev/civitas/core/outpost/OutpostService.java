package dev.civitas.core.outpost;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.claim.ClaimService;
import dev.civitas.core.claim.ClaimType;
import dev.civitas.core.economy.Money;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.OutpostRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;

/**
 * Outposts, SPEC 7.
 *
 * <h2>What an outpost is for</h2>
 * SPEC 7.1 is explicit: a detached chunk so a city can reach a distant biome "without
 * requiring a contiguous land bridge across the map". Every rule in SPEC 7.2 exists to stop
 * it becoming anything else. It is exactly one chunk and cannot be expanded, so it is not a
 * second city. It must sit 32 chunks from its own claims, so it cannot be used to step past
 * the SPEC 6.1 adjacency rule one hop at a time. It costs three times a normal chunk plus a
 * flat fee and 2,000 C a day, so holding four of them is a decision rather than a default.
 *
 * <h2>The SPEC 7.4 conversion</h2>
 * If the city grows until the outpost chunk borders its body, the outpost stops being one:
 * the claim becomes NORMAL, the slot is freed, and nothing is refunded. That last part is the
 * point. A city that grows toward its own outpost has got what it paid the premium for, and
 * refunding it would make "buy an outpost, then expand to it" cheaper than expanding.
 */
public final class OutpostService {

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final CityRegistry cities;
    private final ClaimRegistry claims;
    private final ClaimService claimService;
    private final OutpostRegistry outposts;
    private final TreasuryService treasury;
    private final ConfigManager configs;
    private final Scheduler scheduler;

    public OutpostService(DatabaseManager db, DaoRegistry daos, CityRegistry cities,
                          ClaimRegistry claims, ClaimService claimService,
                          OutpostRegistry outposts, TreasuryService treasury,
                          ConfigManager configs, Scheduler scheduler) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.claimService = Objects.requireNonNull(claimService, "claimService");
        this.outposts = Objects.requireNonNull(outposts, "outposts");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.costs = new OutpostCostEngine(configs);
    }

    /**
     * SPEC 39.3's pricing, replacing Part I 7.2's flat 25,000 C plus three chunk costs.
     *
     * <p>Built here rather than injected because it is a pure view over configuration, the same
     * reasoning {@code WorldRegistry} uses.
     */
    private final OutpostCostEngine costs;

    public OutpostCostEngine costs() {
        return costs;
    }

    public OutpostRegistry registry() {
        return outposts;
    }

    // ==================================================================================
    // SPEC 39.2, an outpost is up to four chunks
    // ==================================================================================

    /**
     * Every chunk of one outpost.
     *
     * <p>The multi-chunk shape needs no schema of its own: {@code claims.outpost_id} is a
     * foreign key, so several claims sharing one already is a multi-chunk outpost. Part I's
     * table stores only the warp point, which is still all an outpost row needs.
     */
    public java.util.List<Claim> chunksOf(Outpost outpost) {
        return claims.claimsOf(outpost.cityId()).stream()
                .filter(claim -> claim.type() == ClaimType.OUTPOST)
                .filter(claim -> Integer.valueOf(outpost.id()).equals(claim.outpostId()))
                .toList();
    }

    /** How many chunks an outpost holds, for the SPEC 39.6 maximum and the upkeep. */
    public int chunkCount(Outpost outpost) {
        return chunksOf(outpost).size();
    }

    /** An outpost's chunks as geometry, for the SPEC 39.6 and 39.7 rules. */
    public java.util.List<OutpostGeometry.Chunk> shapeOf(Outpost outpost) {
        return chunksOf(outpost).stream()
                .map(claim -> new OutpostGeometry.Chunk(claim.chunkX(), claim.chunkZ()))
                .toList();
    }

    /**
     * How far an outpost is from the city core, in blocks.
     *
     * <p>SPEC 39.3 measures "from the city core chunk centre to this chunk centre, in the
     * horizontal plane". Taken from the outpost's founding chunk, so expanding an outpost does
     * not move its price or its upkeep — the outpost is where it was founded.
     */
    public double blocksFromCore(City city, Outpost outpost) {
        return chunksOf(outpost).stream()
                .min(java.util.Comparator.comparingLong(Claim::id))
                .map(claim -> blocksFromCore(city, claim.chunkX(), claim.chunkZ()))
                .orElse(0.0);
    }

    /** The same, for a chunk that is not claimed yet. */
    public double blocksFromCore(City city, int chunkX, int chunkZ) {
        double dx = (chunkX * 16.0 + 8) - (city.coreChunkX() * 16.0 + 8);
        double dz = (chunkZ * 16.0 + 8) - (city.coreChunkZ() * 16.0 + 8);
        return Math.hypot(dx, dz);
    }

    /** SPEC 39.6's four-chunk maximum. */
    public int maxChunksPerOutpost() {
        return configs.get(dev.civitas.config.ConfigFile.CITIES)
                .getInt("outposts.max-chunks-per-outpost", 4);
    }

    /** SPEC 39.6: how far a new outpost must be from the city's other outposts. */
    public int minDistanceFromOwnOutposts() {
        return configs.get(dev.civitas.config.ConfigFile.CITIES)
                .getInt("outposts.min-distance-from-own-outposts", 24);
    }

    // ==================================================================================
    // Creating, SPEC 7.2 and 7.3
    // ==================================================================================

    /**
     * Checks everything SPEC 7.2 requires, without writing anything.
     *
     * <p>Separated so the GUI can show a player why a chunk will be refused, and what it
     * would cost if it were not, before they commit to it.
     *
     * @return the price, or the first rule that says no
     */
    public Result<BigDecimal> checkCreatable(UUID actor, City city, String name, String world,
                                             int chunkX, int chunkZ) {
        if (!city.hasPermission(actor, CityPermission.OUTPOST_MANAGE)) {
            return Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.OUTPOST_MANAGE.name()));
        }
        if (city.isFrozen()) {
            return Result.failure("CITY_FROZEN", "city.frozen");
        }
        if (city.isDelinquent()) {
            return Result.failure("CITY_DELINQUENT", "claim.delinquent");
        }
        if (isCityAtWar(city)) {
            // SPEC 11.11: no outpost creation while a war is running.
            return Result.failure("CITY_AT_WAR", "outpost.at-war");
        }

        Result<String> validName = validateName(city, name);
        if (validName instanceof Result.Failure<String> failure) {
            return Result.propagate(failure);
        }

        if (outposts.countOf(city.id()) >= maxOutposts(city)) {
            return Result.failure("OUTPOST_LIMIT", "outpost.limit",
                    Map.of("limit", String.valueOf(maxOutposts(city))));
        }
        if (claims.at(world, chunkX, chunkZ).isPresent()) {
            return Result.failure("CHUNK_CLAIMED", "claim.chunk-claimed");
        }

        Result<Void> distance = checkDistances(city, world, chunkX, chunkZ);
        if (distance instanceof Result.Failure<Void> failure) {
            return Result.propagate(failure);
        }

        return Result.success(creationCost(city, chunkX, chunkZ));
    }

    /** Creates an outpost at a chunk, SPEC 7.3. */
    public CompletableFuture<Result<Outpost>> create(UUID actor, City city, String name,
                                                     String world, int chunkX, int chunkZ,
                                                     double warpX, double warpY, double warpZ,
                                                     float warpYaw, float warpPitch) {
        Result<BigDecimal> checked = checkCreatable(actor, city, name, world, chunkX, chunkZ);
        if (checked instanceof Result.Failure<BigDecimal> failure) {
            return completed(Result.propagate(failure));
        }
        BigDecimal cost = checked.orElseThrow();
        long now = System.currentTimeMillis();
        String trimmed = name.trim();

        return db.transaction(connection -> {
            int outpostId = daos.outposts().insert(connection, new OutpostRow(0, city.id(),
                    trimmed, warpX, warpY, warpZ, warpYaw, warpPitch, now));

            // One transaction for both rows: an outpost with no chunk, or a chunk with no
            // outpost, is a state nothing in the plugin knows how to read.
            Result<Claim> claim = claimService.writeClaim(connection, actor, city, world,
                    chunkX, chunkZ, cost, ClaimType.OUTPOST, outpostId);
            if (claim instanceof Result.Failure<Claim> failure) {
                return Result.<Created>propagate(failure);
            }

            // The claim charged the treasury under CHUNK_CLAIM; SPEC 4.6 gives outposts their
            // own ledger type, so the movement is recorded again as what it was. The amount
            // is zero because the money has already moved: this row is a label, not a charge.
            daos.ledger().insert(connection, new dev.civitas.storage.row.LedgerRow(0, now,
                    TransactionType.OUTPOST_CREATE.name(), actor, null, city.id(),
                    cost.negate(), city.treasury(),
                    "{\"outpost\":\"" + trimmed + "\",\"accounted\":\"CHUNK_CLAIM\"}"));

            return Result.success(new Created(
                    new Outpost(outpostId, city.id(), trimmed, warpX, warpY, warpZ, warpYaw,
                            warpPitch, now),
                    claim.orElseThrow()));
        }).thenApply(result -> {
            if (result instanceof Result.Success<Created>(Created created)) {
                scheduler.runOnMain(() -> {
                    outposts.put(created.outpost());
                    claims.put(created.claim());
                });
                return Result.success(created.outpost());
            }
            return Result.propagate((Result.Failure<Created>) result);
        });
    }

    private record Created(Outpost outpost, Claim claim) { }

    // ==================================================================================
    // Deleting, SPEC 7.3
    // ==================================================================================

    /**
     * Deletes an outpost, refunding half of what it cost to the treasury.
     *
     * <p>The chunk goes with it. SPEC 6.4's "blocks and builds are not removed on unclaim"
     * applies here too: the land simply stops being protected.
     */
    public CompletableFuture<Result<Outpost>> delete(UUID actor, City city, Outpost outpost) {
        if (!city.hasPermission(actor, CityPermission.OUTPOST_MANAGE)) {
            return completed(Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.OUTPOST_MANAGE.name())));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }
        if (isCityAtWar(city)) {
            return completed(Result.failure("CITY_AT_WAR", "outpost.at-war"));
        }
        return release(city, outpost, actor);
    }

    /**
     * Releases an outpost for unpaid upkeep, SPEC 39.5.
     *
     * <p>No permission, freeze or war check, matching {@link
     * dev.civitas.core.claim.ClaimService#releaseForDebt}: this is not a member acting, it is
     * the upkeep sweep collecting, and a debt that stopped being collectable the moment an
     * admin froze a city would be a way to stop paying.
     */
    public CompletableFuture<Result<Outpost>> releaseForDebt(City city, Outpost outpost) {
        return release(city, outpost, null);
    }

    /** The shared body: drop every chunk, delete the row, refund half of each to the treasury. */
    private CompletableFuture<Result<Outpost>> release(City city, Outpost outpost, UUID actor) {
        // SPEC 39.2: an outpost is up to four chunks, so deleting releases all of them and
        // refunds half of each. Part I could take the single chunk because there was only one.
        java.util.List<Claim> held = chunksOf(outpost);
        if (held.isEmpty()) {
            return completed(Result.failure("OUTPOST_HAS_NO_CHUNK", "outpost.no-chunk"));
        }

        BigDecimal refund = held.stream()
                .map(claim -> Money.percentOf(claim.costPaid(), refundPercent()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return db.transaction(connection -> {
            for (Claim claim : held) {
                daos.claims().deleteAt(connection, claim.world(), claim.chunkX(),
                        claim.chunkZ());
            }
            daos.outposts().delete(connection, outpost.id());

            if (refund.signum() > 0) {
                Result<BigDecimal> paid = treasury.adjust(connection, city, refund,
                        TransactionType.CHUNK_UNCLAIM_REFUND, actor,
                        "{\"outpost\":\"" + outpost.name() + "\",\"chunks\":"
                                + held.size() + "}");
                if (paid instanceof Result.Failure<BigDecimal> failure) {
                    return Result.<Outpost>propagate(failure);
                }
            }
            return Result.success(outpost);
        }).thenApply(result -> {
            if (result instanceof Result.Success<Outpost>) {
                scheduler.runOnMain(() -> {
                    outposts.remove(outpost.id());
                    held.forEach(claims::remove);
                });
            }
            return result;
        });
    }

    // ==================================================================================
    // SPEC 39.7, merging
    // ==================================================================================

    /**
     * Merges any of a city's outposts that have come to touch, SPEC 39.7.
     *
     * <p>"They merge into one outpost, keeping the older one's name and warp. One slot frees."
     * Older by id, which is creation order, so the name a city has been using for longest is the
     * one that survives.
     *
     * <p>Run after a claim lands rather than before it, because a bridging chunk has to exist
     * for two outposts to be adjacent. The case that must be refused rather than performed —
     * a merge exceeding four chunks — is caught earlier by {@code checkExpandable}, so anything
     * reaching here is a merge that may go ahead.
     *
     * @return the outposts that were absorbed and no longer exist
     */
    public CompletableFuture<java.util.List<Outpost>> mergeAdjacentOutposts(City city) {
        java.util.List<Outpost> all = new java.util.ArrayList<>(outposts.of(city.id()));
        all.sort(java.util.Comparator.comparingInt(Outpost::id));

        java.util.List<Outpost> absorbed = new java.util.ArrayList<>();
        java.util.List<Claim> moved = new java.util.ArrayList<>();

        for (int i = 0; i < all.size(); i++) {
            Outpost keeper = all.get(i);
            if (absorbed.contains(keeper)) {
                continue;
            }
            for (int j = i + 1; j < all.size(); j++) {
                Outpost other = all.get(j);
                if (absorbed.contains(other) || !touching(keeper, other)) {
                    continue;
                }
                absorbed.add(other);
                moved.addAll(chunksOf(other));
            }
        }
        if (absorbed.isEmpty()) {
            return CompletableFuture.completedFuture(java.util.List.of());
        }

        java.util.Map<Long, Integer> reassign = new java.util.HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            Outpost keeper = all.get(i);
            if (absorbed.contains(keeper)) {
                continue;
            }
            for (Outpost gone : absorbed) {
                if (touching(keeper, gone)) {
                    chunksOf(gone).forEach(claim -> reassign.put(claim.id(), keeper.id()));
                }
            }
        }

        return db.transaction(connection -> {
            for (Claim claim : moved) {
                Integer keeperId = reassign.get(claim.id());
                if (keeperId != null) {
                    daos.claims().updateType(connection, claim.id(), ClaimType.OUTPOST.name(),
                            keeperId);
                }
            }
            for (Outpost gone : absorbed) {
                daos.outposts().delete(connection, gone.id());
            }
            return Result.success(absorbed);
        }).thenApply(result -> {
            if (result instanceof Result.Success<java.util.List<Outpost>>) {
                scheduler.runOnMain(() -> {
                    // Rebuilt rather than mutated: Claim.convertTo is package-private, and
                    // convertAdjacent above already re-puts a fresh Claim for the same reason.
                    moved.forEach(claim -> {
                        Integer keeperId = reassign.get(claim.id());
                        if (keeperId != null) {
                            claims.put(new Claim(claim.id(), claim.cityId(), claim.world(),
                                    claim.chunkX(), claim.chunkZ(), claim.claimedAt(),
                                    claim.claimedBy(), claim.costPaid(), ClaimType.OUTPOST,
                                    keeperId));
                        }
                    });
                    absorbed.forEach(gone -> outposts.remove(gone.id()));
                });
                return absorbed;
            }
            return java.util.List.<Outpost>of();
        });
    }

    /** Whether any chunk of one outpost shares an edge with any chunk of another. */
    private boolean touching(Outpost first, Outpost second) {
        java.util.List<OutpostGeometry.Chunk> theirs = shapeOf(second);
        return shapeOf(first).stream()
                .anyMatch(chunk -> theirs.stream().anyMatch(chunk::touches));
    }

    // ==================================================================================
    // SPEC 39.2, growing an outpost
    // ==================================================================================

    /**
     * Adds one chunk to an existing outpost, SPEC 39.2.
     *
     * <p>The chunk must border that outpost and must not take it past four. SPEC 39.3 prices it
     * at {@code F(k)} for whichever chunk it is, so the second is cheaper than the founding one
     * and the fourth is dearer — "establishing a new remote holding is a project, while adding a
     * chunk to one that already exists is not".
     */
    public CompletableFuture<Result<Claim>> expand(UUID actor, City city, Outpost outpost,
                                                   String world, int chunkX, int chunkZ) {
        Result<Void> allowed = checkExpandable(actor, city, outpost, world, chunkX, chunkZ);
        if (allowed instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }

        int chunkNumber = chunkCount(outpost) + 1;
        BigDecimal cost = expansionCost(city, outpost, chunkNumber);

        return db.transaction(connection -> claimService.writeClaim(connection, actor, city,
                        world, chunkX, chunkZ, cost, ClaimType.OUTPOST, outpost.id()))
                .thenApply(result -> {
                    if (result instanceof Result.Success<Claim>(Claim written)) {
                        scheduler.runOnMain(() -> claims.put(written));
                    }
                    return result;
                });
    }

    /** Whether {@code outpost} may take this chunk, and why not. */
    public Result<Void> checkExpandable(UUID actor, City city, Outpost outpost, String world,
                                        int chunkX, int chunkZ) {
        if (!city.hasPermission(actor, CityPermission.OUTPOST_MANAGE)) {
            return Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.OUTPOST_MANAGE.name()));
        }
        if (city.isFrozen()) {
            return Result.failure("CITY_FROZEN", "city.frozen");
        }
        if (city.isDelinquent()) {
            return Result.failure("CITY_DELINQUENT", "claim.delinquent");
        }
        if (isCityAtWar(city)) {
            return Result.failure("CITY_AT_WAR", "outpost.at-war");
        }
        if (claims.at(world, chunkX, chunkZ).isPresent()) {
            return Result.failure("CHUNK_CLAIMED", "claim.chunk-claimed");
        }

        java.util.List<Claim> held = chunksOf(outpost);
        if (held.isEmpty()) {
            return Result.failure("OUTPOST_HAS_NO_CHUNK", "outpost.no-chunk");
        }
        if (!held.get(0).world().equals(world)) {
            return Result.failure("WRONG_WORLD", "outpost.wrong-world");
        }
        if (held.size() >= maxChunksPerOutpost()) {
            return Result.failure("OUTPOST_FULL", "outpost.full",
                    Map.of("max", String.valueOf(maxChunksPerOutpost())));
        }

        OutpostGeometry.Chunk candidate = new OutpostGeometry.Chunk(chunkX, chunkZ);
        if (!OutpostGeometry.extendsOutpost(shapeOf(outpost), candidate)) {
            return Result.failure("NOT_ADJACENT", "outpost.not-adjacent");
        }

        // SPEC 39.7. A chunk that bridges two of the city's own outposts merges them, and the
        // merge is REFUSED when the result would exceed four — "the merge is blocked and the
        // claim that would trigger it is rejected", rather than merged and truncated.
        Result<Void> merge = checkMerge(city, candidate, world);
        if (merge instanceof Result.Failure<Void> failure) {
            return Result.propagate(failure);
        }
        return Result.ok();
    }

    /**
     * SPEC 39.7's size check on a bridging claim.
     *
     * <p>Only the blocked case refuses here. The two merges that <b>can</b> happen are performed
     * after the claim lands, by {@link #convertAdjacent} for the city case and
     * {@link #mergeAdjacentOutposts} for the other.
     */
    private Result<Void> checkMerge(City city, OutpostGeometry.Chunk candidate, String world) {
        java.util.List<java.util.List<OutpostGeometry.Chunk>> shapes = outposts.of(city.id())
                .stream()
                .filter(other -> chunksOf(other).stream()
                        .anyMatch(claim -> claim.world().equals(world)))
                .map(this::shapeOf)
                .toList();

        java.util.List<OutpostGeometry.Chunk> body = claims.claimsOf(city.id()).stream()
                .filter(claim -> claim.type() != ClaimType.OUTPOST)
                .filter(claim -> claim.world().equals(world))
                .map(claim -> new OutpostGeometry.Chunk(claim.chunkX(), claim.chunkZ()))
                .toList();

        if (OutpostGeometry.judge(candidate, body, shapes, maxChunksPerOutpost())
                == OutpostGeometry.Merge.BLOCKED_TOO_LARGE) {
            return Result.failure("MERGE_TOO_LARGE", "outpost.merge-too-large",
                    Map.of("max", String.valueOf(maxChunksPerOutpost())));
        }
        return Result.ok();
    }

    /**
     * Releases one chunk of an outpost, SPEC 39.11.
     *
     * <p>Refused when it would split the outpost in two, SPEC 39.14 case 133 — the same
     * contiguity rule SPEC 6.1 applies to a city body, applied inside an outpost. Releasing the
     * last chunk deletes the outpost, because SPEC 39.7 says an outpost reduced to zero chunks
     * has its record removed and its slot freed.
     */
    public CompletableFuture<Result<Claim>> unclaimChunk(UUID actor, City city, String world,
                                                          int chunkX, int chunkZ) {
        if (!city.hasPermission(actor, CityPermission.OUTPOST_MANAGE)) {
            return completed(Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.OUTPOST_MANAGE.name())));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }
        if (isCityAtWar(city)) {
            return completed(Result.failure("CITY_AT_WAR", "outpost.at-war"));
        }

        Optional<Claim> found = claims.at(world, chunkX, chunkZ)
                .filter(claim -> claim.cityId() == city.id())
                .filter(claim -> claim.type() == ClaimType.OUTPOST);
        if (found.isEmpty()) {
            return completed(Result.failure("NOT_AN_OUTPOST_CHUNK", "outpost.not-outpost-chunk"));
        }

        Claim claim = found.get();
        Optional<Outpost> owner = outposts.byId(
                claim.outpostId() == null ? -1 : claim.outpostId());
        if (owner.isEmpty()) {
            // Reachable only if a claim points at an outpost row that is gone, which is
            // corruption rather than a state the game produces.
            return completed(Result.failure("OUTPOST_UNKNOWN", "outpost.no-chunk"));
        }

        Outpost outpost = owner.get();
        java.util.List<OutpostGeometry.Chunk> shape = shapeOf(outpost);
        OutpostGeometry.Chunk going = new OutpostGeometry.Chunk(chunkX, chunkZ);
        if (!OutpostGeometry.survivesRemoval(shape, going)) {
            return completed(Result.failure("WOULD_SPLIT", "outpost.would-split"));
        }

        boolean last = shape.size() <= 1;
        BigDecimal refund = Money.percentOf(claim.costPaid(), refundPercent());

        return db.transaction(connection -> {
            daos.claims().deleteAt(connection, world, chunkX, chunkZ);
            if (last) {
                daos.outposts().delete(connection, outpost.id());
            }
            if (refund.signum() > 0) {
                Result<BigDecimal> paid = treasury.adjust(connection, city, refund,
                        TransactionType.CHUNK_UNCLAIM_REFUND, actor,
                        "{\"outpost\":\"" + outpost.name() + "\"}");
                if (paid instanceof Result.Failure<BigDecimal> failure) {
                    return Result.<Claim>propagate(failure);
                }
            }
            return Result.success(claim);
        }).thenApply(result -> {
            if (result instanceof Result.Success<Claim>) {
                scheduler.runOnMain(() -> {
                    claims.remove(claim);
                    if (last) {
                        outposts.remove(outpost.id());
                    }
                });
            }
            return result;
        });
    }

    // ==================================================================================
    // Renaming and moving the warp, SPEC 7.3
    // ==================================================================================

    public CompletableFuture<Result<Outpost>> rename(UUID actor, City city, Outpost outpost,
                                                     String newName) {
        if (!city.hasPermission(actor, CityPermission.OUTPOST_MANAGE)) {
            return completed(Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.OUTPOST_MANAGE.name())));
        }
        Result<String> validName = validateName(city, newName);
        if (validName instanceof Result.Failure<String> failure) {
            return completed(Result.propagate(failure));
        }

        Outpost renamed = outpost.withName(newName.trim());
        return db.transaction(connection -> {
            daos.outposts().update(connection, renamed.toRow());
            return Result.success(renamed);
        }).thenApply(result -> apply(result, renamed));
    }

    /**
     * Moves the warp destination, SPEC 7.3.
     *
     * <p>The new spot must be inside the outpost's own chunk. Without that, {@code setwarp}
     * would be a teleport-anywhere command that happens to cost 100 C.
     */
    public CompletableFuture<Result<Outpost>> setWarp(UUID actor, City city, Outpost outpost,
                                                      String world, double x, double y, double z,
                                                      float yaw, float pitch) {
        if (!city.hasPermission(actor, CityPermission.OUTPOST_MANAGE)) {
            return completed(Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.OUTPOST_MANAGE.name())));
        }
        Optional<Claim> chunk = claimOf(outpost);
        if (chunk.isEmpty()) {
            return completed(Result.failure("OUTPOST_HAS_NO_CHUNK", "outpost.no-chunk"));
        }
        Claim claim = chunk.get();
        if (!claim.world().equals(world)
                || Math.floorDiv((int) Math.floor(x), 16) != claim.chunkX()
                || Math.floorDiv((int) Math.floor(z), 16) != claim.chunkZ()) {
            return completed(Result.failure("OUTSIDE_OUTPOST", "outpost.warp-outside"));
        }

        Outpost moved = outpost.withWarp(x, y, z, yaw, pitch);
        return db.transaction(connection -> {
            daos.outposts().update(connection, moved.toRow());
            return Result.success(moved);
        }).thenApply(result -> apply(result, moved));
    }

    // ==================================================================================
    // SPEC 7.4: growing into your own outpost
    // ==================================================================================

    /**
     * Converts any outpost that the city's body now touches into an ordinary claim.
     *
     * <p>Called after a successful claim. SPEC 7.4 says the outpost "automatically converts
     * to a normal claim, frees an outpost slot, and refunds nothing", so all three happen
     * here and the mayor is told by the caller.
     *
     * <p>Adjacency is edge-sharing and per-world, exactly as SPEC 6.1 defines it for ordinary
     * claims: an outpost diagonally touching the city body is still an outpost, because a
     * diagonal is not adjacency anywhere else in the plugin either.
     *
     * @return the outposts that stopped being outposts
     */
    public CompletableFuture<List<Outpost>> convertAdjacent(City city) {
        List<Outpost> touching = outposts.of(city.id()).stream()
                .filter(outpost -> claimOf(outpost)
                        .filter(claim -> touchesCityBody(city, claim))
                        .isPresent())
                .toList();

        if (touching.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return db.transaction(connection -> {
            for (Outpost outpost : touching) {
                Claim claim = claimOf(outpost).orElseThrow();
                // The claim keeps its cost_paid, so the refund on a later unclaim is half of
                // the outpost premium. The city paid it; SPEC 7.4 does not take it back, and
                // it should not quietly reappear as a cheaper refund either.
                daos.claims().updateType(connection, claim.id(), ClaimType.NORMAL.name(), null);
                daos.outposts().delete(connection, outpost.id());
            }
            return touching;
        }).thenApply(converted -> {
            scheduler.runOnMain(() -> {
                for (Outpost outpost : converted) {
                    Claim claim = claimOf(outpost).orElse(null);
                    outposts.remove(outpost.id());
                    if (claim != null) {
                        claims.put(new Claim(claim.id(), claim.cityId(), claim.world(),
                                claim.chunkX(), claim.chunkZ(), claim.claimedAt(),
                                claim.claimedBy(), claim.costPaid(), ClaimType.NORMAL, null));
                    }
                }
            });
            return converted;
        });
    }

    /** Whether a chunk shares an edge with a non-outpost claim of this city, in its world. */
    private boolean touchesCityBody(City city, Claim claim) {
        int[][] neighbours = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : neighbours) {
            boolean adjacent = claims
                    .at(claim.world(), claim.chunkX() + offset[0], claim.chunkZ() + offset[1])
                    .filter(other -> other.cityId() == city.id())
                    .filter(other -> other.type() != ClaimType.OUTPOST)
                    .isPresent();
            if (adjacent) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================================
    // The SPEC 7.2 rules
    // ==================================================================================

    /**
     * The two distance minimums.
     *
     * <p>Both are Chebyshev distances in chunks, the same measure SPEC 6.2 uses for the
     * claim-cost multiplier, so "32 chunks away" means the same thing everywhere.
     */
    private Result<Void> checkDistances(City city, String world, int chunkX, int chunkZ) {
        int fromOwn = minDistanceFromOwnCity();
        int fromOthers = minDistanceFromOtherCity();

        int nearestOwn = Integer.MAX_VALUE;
        int nearestOther = Integer.MAX_VALUE;
        // SPEC 39.6's third distance, new at M10. Six four-chunk outposts is twenty-four
        // remote chunks, and without a spacing rule they could be laid end to end into a
        // continuous road across the map — which is the adjacency rule defeated by other means.
        int nearestOwnOutpost = Integer.MAX_VALUE;

        for (Claim claim : claims.allClaims()) {
            if (!claim.world().equals(world)) {
                continue;
            }
            int distance = Math.max(Math.abs(claim.chunkX() - chunkX),
                    Math.abs(claim.chunkZ() - chunkZ));
            if (claim.cityId() == city.id()) {
                // An existing outpost of the same city is not "the city body" for the 32-chunk
                // rule; SPEC 39.6 measures that from the city, and gives outposts their own
                // 24-chunk spacing below.
                if (claim.type() == ClaimType.OUTPOST) {
                    nearestOwnOutpost = Math.min(nearestOwnOutpost, distance);
                } else {
                    nearestOwn = Math.min(nearestOwn, distance);
                }
            } else {
                nearestOther = Math.min(nearestOther, distance);
            }
        }

        if (nearestOwn < fromOwn) {
            return Result.failure("TOO_CLOSE_TO_OWN_CITY", "outpost.too-close-own",
                    Map.of("required", String.valueOf(fromOwn),
                            "actual", String.valueOf(nearestOwn)));
        }
        int fromOwnOutposts = minDistanceFromOwnOutposts();
        if (fromOwnOutposts > 0 && nearestOwnOutpost < fromOwnOutposts) {
            return Result.failure("TOO_CLOSE_TO_OWN_OUTPOST", "outpost.too-close-outpost",
                    Map.of("required", String.valueOf(fromOwnOutposts),
                            "actual", String.valueOf(nearestOwnOutpost)));
        }
        if (nearestOther < fromOthers) {
            return Result.failure("TOO_CLOSE_TO_OTHER_CITY", "outpost.too-close-other",
                    Map.of("required", String.valueOf(fromOthers),
                            "actual", String.valueOf(nearestOther)));
        }
        return Result.ok();
    }

    private Result<String> validateName(City city, String name) {
        if (name == null || name.isBlank()) {
            return Result.failure("NAME_MISSING", "outpost.name-invalid");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 24 || !trimmed.matches("^[A-Za-z0-9_]+$")) {
            return Result.failure("NAME_INVALID", "outpost.name-invalid");
        }
        if (outposts.byName(city.id(), trimmed).isPresent()) {
            return Result.failure("NAME_TAKEN", "outpost.name-taken",
                    Map.of("name", trimmed));
        }
        return Result.success(trimmed);
    }

    /**
     * SPEC 7.2: 25,000 C flat plus three times the current normal chunk cost.
     *
     * <p>"Current" means what the next ordinary chunk would cost this city, so an outpost
     * gets more expensive as the city grows, exactly like the land it is priced against.
     */
    /**
     * What the founding chunk of a new outpost costs here, SPEC 39.3.
     *
     * <p>Replaces Part I 7.2's flat 25,000 C plus three times a normal chunk. SPEC 39.1 retires
     * that pricing along with the single-chunk design: with no world border, a flat fee made a
     * holding a million blocks away cost the same as one next door, which is not a price for
     * distance at all.
     */
    public BigDecimal creationCost(City city, int chunkX, int chunkZ) {
        return costs.chunkCost(claims.claimsOf(city.id()).size(), 1,
                blocksFromCore(city, chunkX, chunkZ), activeMembers(city));
    }

    /**
     * What adding one more chunk to an existing outpost costs, SPEC 39.3.
     *
     * @param chunkNumber which chunk of this outpost it will be: 2, 3 or 4
     */
    public BigDecimal expansionCost(City city, Outpost outpost, int chunkNumber) {
        return costs.chunkCost(claims.claimsOf(city.id()).size(), chunkNumber,
                blocksFromCore(city, outpost), activeMembers(city));
    }

    /** The price with its four terms shown, for SPEC 39.11's {@code /city outpost cost}. */
    public OutpostCostEngine.Breakdown priceBreakdown(City city, int chunkNumber,
                                                      int chunkX, int chunkZ) {
        return costs.breakdown(claims.claimsOf(city.id()).size(), chunkNumber,
                blocksFromCore(city, chunkX, chunkZ), activeMembers(city));
    }

    /** Daily upkeep for one outpost, SPEC 39.5: {@code 1200 * D(d) * chunks}. */
    public BigDecimal upkeepFor(City city, Outpost outpost) {
        return costs.upkeepPerDay(chunkCount(outpost), blocksFromCore(city, outpost));
    }

    /**
     * Daily upkeep for every outpost the city holds, SPEC 39.5.
     *
     * <p>Summed rather than counted: each outpost is priced by its own distance and its own
     * chunk count, so a city holding one nearby and one at half a million blocks owes very
     * different amounts for the two.
     */
    public BigDecimal upkeepFor(City city) {
        return outposts.of(city.id()).stream()
                .map(outpost -> upkeepFor(city, outpost))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Daily upkeep a one-chunk outpost founded at this spot would cost, for the create button.
     *
     * <p>Takes a position for the same reason {@link #creationCost(City, int, int)} does: under
     * SPEC 39.5 the bill scales with distance, so a figure quoted without a place is not a bill.
     */
    public BigDecimal upkeepForNewAt(City city, int chunkX, int chunkZ) {
        return costs.upkeepPerDay(1, blocksFromCore(city, chunkX, chunkZ));
    }

    /** The teleport fee, SPEC 39.5: {@code 100 * D(d)}. */
    public BigDecimal teleportCost(City city, Outpost outpost) {
        return costs.teleportCost(blocksFromCore(city, outpost));
    }

    /**
     * SPEC 7.2: two, rising to six with the Outpost Range upgrade.
     *
     * <p>The upgrade is M11's, so this reads the base until then. The conservative direction:
     * a city never gets a slot it has not paid for.
     */
    public int maxOutposts(City city) {
        int base = configs.get(ConfigFile.CITIES).getInt("outposts.base-max", 2);
        return base + upgradeLevels(city);
    }

    /**
     * SPEC 5.7 Outpost Range, capped at the SPEC 7.2 ceiling.
     *
     * <p>SPEC 7.2 says "2 base, up to 6", and SPEC 5.7 grants one per level over five levels,
     * which would reach seven. The ceiling wins: {@code outposts.max-total} is what the
     * specification promises a player.
     */
    private int upgradeLevels(City city) {
        if (upgrades == null) {
            return 0;
        }
        int base = configs.get(ConfigFile.CITIES).getInt("outposts.base-max", 2);
        int ceiling = configs.get(ConfigFile.CITIES)
                .getInt(dev.civitas.core.upgrade.UpgradeType.OUTPOST_RANGE.configPath()
                        + ".max-total", 6);
        int perLevel = (int) Math.round(upgrades.effectPerLevel(
                dev.civitas.core.upgrade.UpgradeType.OUTPOST_RANGE, 1));
        int fromUpgrade = perLevel * upgrades.levelOf(city,
                dev.civitas.core.upgrade.UpgradeType.OUTPOST_RANGE);

        return Math.max(0, Math.min(fromUpgrade, ceiling - base));
    }

    /** Told about upgrades once they exist. */
    public void useUpgrades(dev.civitas.core.upgrade.UpgradeService service) {
        this.upgrades = service;
    }

    private dev.civitas.core.upgrade.UpgradeService upgrades;

    /** SPEC 11.11: no outpost creation or deletion while a war is running. */
    private boolean isCityAtWar(City city) {
        return wars != null && wars.blocksOutposts(city.id());
    }

    private dev.civitas.core.war.WarRestrictions wars;

    /** SPEC 11.11, wired by M19. */
    public void useWars(dev.civitas.core.war.WarRestrictions restrictions) {
        this.wars = restrictions;
    }

    private int activeMembers(City city) {
        return claimService.activeMemberCount(city);
    }

    public double refundPercent() {
        return configs.get(ConfigFile.CITIES).getDouble("outposts.delete-refund-percent", 50);
    }

    public int minDistanceFromOwnCity() {
        return configs.get(ConfigFile.CITIES).getInt("outposts.min-distance-from-own-city", 32);
    }

    public int minDistanceFromOtherCity() {
        return configs.get(ConfigFile.CITIES).getInt("outposts.min-distance-from-other-city", 8);
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    /** The chunk an outpost occupies, found through the claim that points at it. */
    public Optional<Claim> claimOf(Outpost outpost) {
        for (Claim claim : claims.claimsOf(outpost.cityId())) {
            if (claim.outpostId() != null && claim.outpostId() == outpost.id()) {
                return Optional.of(claim);
            }
        }
        return Optional.empty();
    }

    private Result<Outpost> apply(Result<Outpost> result, Outpost updated) {
        if (result instanceof Result.Success<Outpost>) {
            scheduler.runOnMain(() -> outposts.put(updated));
        }
        return result;
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }
}
