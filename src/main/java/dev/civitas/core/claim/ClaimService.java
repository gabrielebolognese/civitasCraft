package dev.civitas.core.claim;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import dev.civitas.api.event.ChunkClaimEvent;
import dev.civitas.api.event.ChunkUnclaimEvent;
import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.CityRow;
import dev.civitas.storage.row.ClaimRow;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.EventBus;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Buying and releasing land, SPEC 6.
 *
 * <p>Same shape as {@link dev.civitas.core.city.CityService}: cheap in-memory checks, a
 * cancellable event, then one transaction that re-reads the treasury and re-checks the
 * chunk before writing. The re-check matters because two members of one city can pass the
 * same in-memory check in the same tick (SPEC 17.2 case 14) and two different cities can
 * race for the same chunk (case 15); the unique index on {@code (world, chunk_x, chunk_z)}
 * is the arbiter, and a constraint violation here means "someone got there first", not a
 * fault.
 */
public final class ClaimService {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final CityRegistry cities;
    private final ClaimRegistry registry;
    private final ClaimCostEngine costs;
    private final ConfigManager configs;
    private final Scheduler scheduler;
    private final EventBus events;

    /** Members with auto-claim on, SPEC 6.3. Session state; deliberately not persisted. */
    private final Set<UUID> autoClaiming = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ClaimService(DatabaseManager db, DaoRegistry daos, CityRegistry cities,
                        ClaimRegistry registry, ClaimCostEngine costs, ConfigManager configs,
                        Scheduler scheduler, EventBus events) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.costs = Objects.requireNonNull(costs, "costs");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Told after every successful claim, so SPEC 7.4's outpost conversion can run.
     *
     * <p>A settable listener rather than a constructor dependency because the outpost service
     * is built on top of this one: a claim has to be able to happen before outposts exist,
     * and does, during city creation.
     */
    public void onClaimed(java.util.function.BiConsumer<City, Claim> listener) {
        this.claimListener = Objects.requireNonNull(listener, "listener");
    }

    private java.util.function.BiConsumer<City, Claim> claimListener = (city, claim) -> { };

    public ClaimRegistry registry() {
        return registry;
    }

    public ClaimCostEngine costs() {
        return costs;
    }

    // ==================================================================================
    // Pricing
    // ==================================================================================

    /**
     * What the next chunk at a position would cost, SPEC 6.2.
     *
     * <p>Exposed so the claims screen and the command can show the breakdown before the
     * player commits, rather than only after the treasury has moved.
     */
    public ClaimCostEngine.Breakdown quote(City city, String world, int chunkX, int chunkZ) {
        int index = registry.countOf(city.id()) + 1;
        int distance = distanceFromCore(city, world, chunkX, chunkZ);
        int activeMembers = activeMemberCount(city);
        return costs.price(index, distance, activeMembers, city.ageMillis(System.currentTimeMillis()));
    }

    /**
     * Chebyshev distance from the core, SPEC 6.2.
     *
     * <p>A claim in a different world from the core has no meaningful distance to it. SPEC 20
     * decision 4 says the core defines the primary world, so land elsewhere is charged as if
     * it sat at the free radius rather than at an arbitrary huge distance, which would make
     * a second world unaffordable by accident rather than by design.
     */
    int distanceFromCore(City city, String world, int chunkX, int chunkZ) {
        if (!city.coreWorld().equalsIgnoreCase(world)) {
            return 0;
        }
        return ChunkKey.chebyshev(city.coreChunkX(), city.coreChunkZ(), chunkX, chunkZ);
    }

    /**
     * Members who count toward the SPEC 6.2 divisor.
     *
     * <p>SPEC 17.6 case 69 is the rule that matters here: only accounts with the founding
     * playtime <em>and</em> a login inside the active window count, so a city cannot halve
     * its land costs by inviting ten alts that have never played.
     */
    /**
     * How many members count toward the SPEC 6.2 divisor.
     *
     * <p>Public because an outpost is priced against what an ordinary chunk would cost
     * (SPEC 7.2), and that price needs the same divisor.
     */
    public int activeMemberCount(City city) {
        // Floored at one because this is a divisor: a city whose members have all gone quiet
        // must still be quoted a price, not an infinite one.
        return Math.max(1, rawActiveMemberCount(city));
    }

    /**
     * The same count without the divisor's floor of one.
     *
     * <p>SPEC 13.3's Cities by Population board ranks this, and there the difference matters:
     * a city with nobody active should read zero rather than one. The rule for what counts as
     * active lives here, in one place, so the board and the price can never disagree about it.
     */
    public int rawActiveMemberCount(City city) {
        FileConfiguration cityConfig = configs.get(ConfigFile.CITIES);
        long window = cityConfig.getLong("claims.active-member-days", 14) * MILLIS_PER_DAY;
        long minPlaytime = cityConfig.getLong("creation.min-playtime-hours", 2) * 3_600_000L;
        long cutoff = System.currentTimeMillis() - window;

        int active = 0;
        for (var member : city.members()) {
            PlayerRow row = cachedPlayers.get(member.uuid());
            if (row == null) {
                // Nothing cached for this member yet. Counting them would let an unverified
                // account cheapen land, so they are left out until a lookup fills them in.
                continue;
            }
            if (row.lastSeen() >= cutoff && row.activePlaytimeMs() >= minPlaytime) {
                active++;
            }
        }
        return active;
    }

    /**
     * Player rows behind the active-member count.
     *
     * <p>Refreshed off the main thread by {@link #refreshActiveMembers}; read synchronously
     * during pricing, because SPEC 2.1 forbids a database round trip on the path a player
     * takes when they type {@code /city claim}.
     */
    private final Map<UUID, PlayerRow> cachedPlayers = new java.util.concurrent.ConcurrentHashMap<>();

    /** Reloads the player rows a city's pricing depends on. */
    public CompletableFuture<Void> refreshActiveMembers(City city) {
        return daos.players().findByCity(city.id()).thenAccept(rows -> {
            for (PlayerRow row : rows) {
                cachedPlayers.put(row.uuid(), row);
            }
        });
    }

    /** Seeds the pricing cache for every city, at startup. */
    public CompletableFuture<Void> loadActiveMembers() {
        return daos.players().findAllWithCity().thenAccept(rows -> {
            for (PlayerRow row : rows) {
                cachedPlayers.put(row.uuid(), row);
            }
        });
    }

    // ==================================================================================
    // Claiming, SPEC 6.3
    // ==================================================================================

    /**
     * Buys one chunk.
     *
     * <p>The ten SPEC 6.3 preconditions are checked in the order the specification lists
     * them, so the message names the first thing actually wrong.
     */
    public CompletableFuture<Result<Claim>> claim(UUID actor, City city, String world,
                                                  int chunkX, int chunkZ) {
        Result<ClaimCostEngine.Breakdown> checked = checkClaimable(actor, city, world, chunkX, chunkZ);
        if (checked instanceof Result.Failure<ClaimCostEngine.Breakdown> failure) {
            return completed(Result.propagate(failure));
        }
        BigDecimal cost = checked.orElseThrow().total();

        if (!events.fire(new ChunkClaimEvent(city, actor, world, chunkX, chunkZ, cost))) {
            return completed(Result.failure("CANCELLED", "claim.cancelled"));
        }

        return db.transaction(connection -> writeClaim(connection, actor, city, world,
                        chunkX, chunkZ, cost, ClaimType.NORMAL, null))
                .thenApply(result -> applyOnMain(result, claimed -> {
                    registry.put(claimed);
                    claimListener.accept(city, claimed);
                }));
    }

    /**
     * Every SPEC 6.3 precondition except the funds check, which the transaction owns.
     *
     * <p>Split out so the claims screen and {@code radius} can ask "would this work?" without
     * attempting it.
     *
     * @return the price on success
     */
    public Result<ClaimCostEngine.Breakdown> checkClaimable(UUID actor, City city, String world,
                                                            int chunkX, int chunkZ) {
        return checkClaimable(actor, city, world, chunkX, chunkZ, Set.of(), 0);
    }

    /**
     * The same checks, with chunks bought earlier in the same transaction counted as owned.
     *
     * <p>{@code radius} needs this: without it the second chunk of a square is refused as not
     * adjacent, because the registry has not seen the first one yet. Passing the pending set
     * through rather than rescuing a failure afterwards keeps the checks in SPEC order, so a
     * chunk inside another city's buffer is still refused for that reason.
     *
     * @param pending       chunks already bought in this transaction
     * @param alreadyBought how many, so the price keeps climbing across the square
     */
    private Result<ClaimCostEngine.Breakdown> checkClaimable(UUID actor, City city, String world,
                                                             int chunkX, int chunkZ,
                                                             Set<Contiguity.Chunk> pending,
                                                             int alreadyBought) {
        FileConfiguration cityConfig = configs.get(ConfigFile.CITIES);

        // 1. Permission.
        if (!city.hasPermission(actor, CityPermission.CLAIM)) {
            return Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.CLAIM.name()));
        }

        // SPEC 17.2 case 20: a chunk beyond the representable range cannot be keyed, and
        // aliasing it onto another chunk would hand a city land it does not own.
        if (!ChunkKey.isInRange(chunkX) || !ChunkKey.isInRange(chunkZ)) {
            return Result.failure("OUT_OF_RANGE", "claim.out-of-range");
        }

        // 2. Unclaimed.
        Contiguity.Chunk candidate = new Contiguity.Chunk(chunkX, chunkZ);
        if (pending.contains(candidate)) {
            return Result.failure("ALREADY_OWNED", "claim.already-owned");
        }
        Optional<Claim> existing = registry.at(world, chunkX, chunkZ);
        if (existing.isPresent()) {
            return Result.failure(existing.get().cityId() == city.id()
                            ? "ALREADY_OWNED" : "CHUNK_CLAIMED",
                    existing.get().cityId() == city.id()
                            ? "claim.already-owned" : "claim.chunk-claimed");
        }

        // 3. Edge-adjacent to this city's land in this world.
        Set<Contiguity.Chunk> owned = new LinkedHashSet<>(registry.contiguousChunksOf(city.id(), world));
        owned.addAll(pending);
        if (!owned.isEmpty() && !Contiguity.isAdjacent(owned, candidate)) {
            return Result.failure("NOT_ADJACENT", "claim.not-adjacent");
        }
        if (owned.isEmpty() && !city.coreWorld().equalsIgnoreCase(world)) {
            // A city's first chunk in a new world would be detached from everything, which
            // is what an outpost is for (SPEC 7.1). Claiming is not the route to one.
            return Result.failure("NO_ANCHOR_IN_WORLD", "claim.no-anchor");
        }

        // 4. World is enabled for cities.
        Result<Void> worldCheck = checkWorld(world);
        if (worldCheck instanceof Result.Failure<Void> failure) {
            return Result.propagate(failure);
        }

        // 5. Outside another city's buffer.
        int buffer = cityConfig.getInt("claims.buffer-chunks", 5);
        if (buffer > 0 && registry.isForeignLandWithin(world, chunkX, chunkZ, buffer, city.id())) {
            return Result.failure("TOO_CLOSE", "claim.too-close",
                    Map.of("chunks", String.valueOf(buffer)));
        }

        // 7. Not delinquent on upkeep.
        if (city.isDelinquent()) {
            return Result.failure("CITY_DELINQUENT", "claim.delinquent");
        }

        // 8. Not frozen.
        if (city.isFrozen()) {
            return Result.failure("CITY_FROZEN", "city.frozen");
        }

        // 9. Not mid-war. The war system is M19; until it exists this is always satisfied,
        // and it is written out rather than omitted so M19 has one place to fill in.
        if (isCityAtWar(city)) {
            return Result.failure("CITY_AT_WAR", "claim.at-war");
        }

        // 10. Not an admin-protected region. Admin protection is M21, same reasoning.
        if (isAdminProtected(world, chunkX, chunkZ)) {
            return Result.failure("ADMIN_PROTECTED", "claim.admin-protected");
        }

        // 6. Funds. Quoted here; the transaction re-reads the treasury before charging it.
        int index = registry.countOf(city.id()) + alreadyBought + 1;
        return Result.success(costs.price(index,
                distanceFromCore(city, world, chunkX, chunkZ),
                activeMemberCount(city),
                city.ageMillis(System.currentTimeMillis())));
    }

    /**
     * Claims an odd-sided square centred on a chunk, SPEC 6.3.
     *
     * <p>Atomic, per SPEC 17.2 case 17: either every chunk in the square is bought or none
     * is. Half a square would leave the player charged for land they did not ask for in a
     * shape they did not want.
     *
     * @param radius the {@code n} in {@code /city claim radius n}, giving an n-by-n square
     */
    public CompletableFuture<Result<List<Claim>>> claimRadius(UUID actor, City city, String world,
                                                              int centreX, int centreZ, int radius) {
        int max = configs.get(ConfigFile.CITIES).getInt("claims.radius-claim-max", 5);
        if (radius < 1 || radius > max) {
            return completed(Result.failure("RADIUS_OUT_OF_BOUNDS", "claim.radius-bounds",
                    Map.of("max", String.valueOf(max))));
        }
        if (!city.hasPermission(actor, CityPermission.CLAIM)) {
            return completed(Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.CLAIM.name())));
        }

        int arm = (radius - 1) / 2;
        List<int[]> targets = growthOrder(city, world, centreX, centreZ, arm);
        if (targets == null) {
            return completed(Result.failure("NOT_ADJACENT", "claim.not-adjacent"));
        }

        return db.transaction(connection -> {
            List<Claim> claimed = new ArrayList<>();
            Set<Contiguity.Chunk> pending = new LinkedHashSet<>();

            for (int[] target : targets) {
                // Re-price against what the earlier chunks in this same square already cost,
                // so a nine-chunk square is priced as nine successive chunks, not nine copies
                // of the first.
                Result<ClaimCostEngine.Breakdown> check = checkClaimable(actor, city, world,
                        target[0], target[1], pending, claimed.size());
                if (check instanceof Result.Failure<ClaimCostEngine.Breakdown> failure) {
                    return Result.<List<Claim>>failure(failure.reason(), failure.messageKey(),
                            withChunk(failure.placeholders(), target[0], target[1]));
                }

                Result<Claim> written = writeClaim(connection, actor, city, world,
                        target[0], target[1], check.orElseThrow().total(), ClaimType.NORMAL, null);
                if (written instanceof Result.Failure<Claim> failure) {
                    return Result.<List<Claim>>failure(failure.reason(), failure.messageKey(),
                            withChunk(failure.placeholders(), target[0], target[1]));
                }
                claimed.add(written.orElseThrow());
                pending.add(new Contiguity.Chunk(target[0], target[1]));
            }
            return Result.success(claimed);
        }).thenApply(result -> applyOnMain(result, list -> {
            list.forEach(registry::put);
            list.stream().findFirst().ifPresent(first -> claimListener.accept(city, first));
        }));
    }

    /**
     * Writes one claim and charges the treasury, inside a caller-supplied transaction.
     *
     * <p>Also used by city creation for the free core chunk, which is why the type and the
     * cost are parameters rather than assumed.
     */
    public Result<Claim> writeClaim(Connection connection, UUID actor, City city, String world,
                                    int chunkX, int chunkZ, BigDecimal cost, ClaimType type,
                                    Integer outpostId) throws SQLException {
        long now = System.currentTimeMillis();

        Optional<CityRow> current = daos.cities().findById(connection, city.id());
        if (current.isEmpty()) {
            return Result.failure("CITY_GONE", "city.unknown");
        }
        BigDecimal treasury = current.get().treasury();

        // SPEC 6.3 precondition 6, re-read inside the transaction so two simultaneous claims
        // cannot both pass a check against the same stale balance.
        if (cost.signum() > 0 && treasury.compareTo(cost) < 0) {
            return Result.failure("TREASURY_SHORT", "city.treasury.insufficient",
                    Map.of("required", cost.toPlainString(),
                            "balance", treasury.toPlainString()));
        }

        long claimId;
        try {
            claimId = daos.claims().insert(connection, new ClaimRow(0, city.id(), world,
                    chunkX, chunkZ, now, actor, cost, type.name(), outpostId));
        } catch (SQLException e) {
            // SPEC 17.2 cases 14 and 15: somebody took this chunk between the check and the
            // write. The unique index settles it, and the loser pays nothing because the
            // whole transaction rolls back.
            if (isUniqueViolation(e)) {
                return Result.failure("CHUNK_CLAIMED", "claim.chunk-claimed");
            }
            throw e;
        }

        if (cost.signum() > 0) {
            BigDecimal after = treasury.subtract(cost);
            daos.cities().updateTreasury(connection, city.id(), after);
            daos.ledger().insert(connection, new LedgerRow(0, now,
                    TransactionType.CHUNK_CLAIM.name(), actor, null, city.id(),
                    cost.negate(), after, chunkMetadata(world, chunkX, chunkZ)));
            city.setTreasury(after);
        }

        return Result.success(new Claim(claimId, city.id(), world, chunkX, chunkZ, now, actor,
                cost, type, outpostId));
    }

    // ==================================================================================
    // Unclaiming, SPEC 6.4
    // ==================================================================================

    /** Gives a chunk back, refunding half of what was paid to the treasury. */
    public CompletableFuture<Result<Claim>> unclaim(UUID actor, City city, String world,
                                                    int chunkX, int chunkZ) {
        Result<Claim> checked = checkUnclaimable(actor, city, world, chunkX, chunkZ);
        if (checked instanceof Result.Failure<Claim> failure) {
            return completed(Result.propagate(failure));
        }
        Claim claim = checked.orElseThrow();
        BigDecimal refund = costs.refundFor(claim.costPaid());

        if (!events.fire(new ChunkUnclaimEvent(city, actor, claim, refund))) {
            return completed(Result.failure("CANCELLED", "claim.unclaim-cancelled"));
        }

        return db.transaction(connection -> releaseClaim(connection, actor, city, claim, refund))
                .thenApply(result -> applyOnMain(result, released -> {
                    registry.remove(released);
                    resetSpawnIfStranded(city, released);
                }));
    }

    /** Every SPEC 6.4 check. Exposed so a confirmation screen can warn before committing. */
    public Result<Claim> checkUnclaimable(UUID actor, City city, String world,
                                          int chunkX, int chunkZ) {
        if (!city.hasPermission(actor, CityPermission.UNCLAIM)) {
            return Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.UNCLAIM.name()));
        }
        if (city.isFrozen()) {
            return Result.failure("CITY_FROZEN", "city.frozen");
        }
        if (isCityAtWar(city)) {
            return Result.failure("CITY_AT_WAR", "claim.at-war");
        }

        Optional<Claim> found = registry.at(world, chunkX, chunkZ);
        if (found.isEmpty() || found.get().cityId() != city.id()) {
            return Result.failure("NOT_YOUR_CLAIM", "claim.not-yours");
        }
        Claim claim = found.get();

        if (claim.isCore()) {
            return Result.failure("CORE_CHUNK", "claim.core");
        }
        if (containsSpawn(city, claim)) {
            return Result.failure("CONTAINS_SPAWN", "claim.contains-spawn");
        }

        Result<Void> contiguity = checkContiguity(city, claim);
        if (contiguity instanceof Result.Failure<Void> failure) {
            return Result.propagate(failure);
        }
        return Result.success(claim);
    }

    /**
     * The SPEC 6.1 invariant, checked on removal.
     *
     * <p>The refusal lists the chunks that would be stranded, because "this would split your
     * city" is unhelpful when a player is looking at a hundred chunks and cannot see which.
     */
    Result<Void> checkContiguity(City city, Claim claim) {
        if (!configs.get(ConfigFile.CITIES).getBoolean("claims.enforce-contiguity", true)) {
            return Result.ok();
        }
        if (!claim.type().isContiguous()) {
            return Result.ok();
        }
        if (!claim.world().equalsIgnoreCase(city.coreWorld())) {
            // Land outside the core's world is its own component by construction, so there
            // is nothing for its removal to split.
            return Result.ok();
        }

        Set<Contiguity.Chunk> owned = registry.contiguousChunksOf(city.id(), claim.world());
        Contiguity.Chunk core = new Contiguity.Chunk(city.coreChunkX(), city.coreChunkZ());
        List<Contiguity.Chunk> orphans = Contiguity.orphansAfterRemoving(owned, core,
                new Contiguity.Chunk(claim.chunkX(), claim.chunkZ()));

        if (orphans.isEmpty()) {
            return Result.ok();
        }
        String listed = orphans.stream()
                .limit(8)
                .map(Contiguity.Chunk::toString)
                .collect(Collectors.joining("; "));
        return Result.failure("BREAKS_CONTIGUITY", "claim.breaks-contiguity",
                Map.of("count", String.valueOf(orphans.size()), "chunks", listed));
    }

    /** Deletes a claim and credits the refund, inside a caller-supplied transaction. */
    Result<Claim> releaseClaim(Connection connection, UUID actor, City city, Claim claim,
                               BigDecimal refund) throws SQLException {
        long now = System.currentTimeMillis();

        int removed = daos.claims().deleteAt(connection, claim.world(), claim.chunkX(), claim.chunkZ());
        if (removed == 0) {
            return Result.failure("NOT_YOUR_CLAIM", "claim.not-yours");
        }

        if (refund.signum() > 0) {
            Optional<CityRow> current = daos.cities().findById(connection, city.id());
            if (current.isEmpty()) {
                return Result.failure("CITY_GONE", "city.unknown");
            }
            BigDecimal after = current.get().treasury().add(refund);
            daos.cities().updateTreasury(connection, city.id(), after);
            daos.ledger().insert(connection, new LedgerRow(0, now,
                    TransactionType.CHUNK_UNCLAIM_REFUND.name(), actor, null, city.id(),
                    refund, after, chunkMetadata(claim.world(), claim.chunkX(), claim.chunkZ())));
            city.setTreasury(after);
        }
        return Result.success(claim);
    }

    /**
     * Unclaims an n-by-n square, skipping chunks the city does not own.
     *
     * <p>Not atomic in the way claiming is, deliberately: a player releasing a square is
     * giving land up, and refusing the whole square because one chunk of it is the core
     * would mean they can never tidy up around it.
     */
    public CompletableFuture<Result<List<Claim>>> unclaimRadius(UUID actor, City city, String world,
                                                                int centreX, int centreZ, int radius) {
        int max = configs.get(ConfigFile.CITIES).getInt("claims.radius-claim-max", 5);
        if (radius < 1 || radius > max) {
            return completed(Result.failure("RADIUS_OUT_OF_BOUNDS", "claim.radius-bounds",
                    Map.of("max", String.valueOf(max))));
        }

        int arm = (radius - 1) / 2;
        List<int[]> targets = square(centreX, centreZ, arm);
        // Furthest first: releasing the outside of a square before its middle means the
        // middle is never briefly stranded, so contiguity does not refuse a legal square.
        targets.sort(Comparator.comparingInt(
                (int[] target) -> ChunkKey.chebyshev(centreX, centreZ, target[0], target[1])).reversed());

        List<Claim> released = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        return db.transaction(connection -> {
            for (int[] target : targets) {
                Result<Claim> check = checkUnclaimableWithReleased(actor, city, world,
                        target[0], target[1], released);
                if (check instanceof Result.Failure<Claim> failure) {
                    if ("NO_CITY_PERMISSION".equals(failure.reason())
                            || "CITY_FROZEN".equals(failure.reason())
                            || "CITY_AT_WAR".equals(failure.reason())) {
                        return Result.<List<Claim>>propagate(failure);
                    }
                    skipped.add(target[0] + "," + target[1]);
                    continue;
                }
                Claim claim = check.orElseThrow();
                Result<Claim> result = releaseClaim(connection, actor, city, claim,
                        costs.refundFor(claim.costPaid()));
                if (result instanceof Result.Failure<Claim> failure) {
                    return Result.<List<Claim>>propagate(failure);
                }
                released.add(claim);
            }
            if (released.isEmpty()) {
                return Result.<List<Claim>>failure("NOTHING_UNCLAIMED", "claim.radius-nothing");
            }
            return Result.success(released);
        }).thenApply(result -> applyOnMain(result, list -> list.forEach(claim -> {
            registry.remove(claim);
            resetSpawnIfStranded(city, claim);
        })));
    }

    /** Unclaim checks that treat chunks already released in this transaction as gone. */
    private Result<Claim> checkUnclaimableWithReleased(UUID actor, City city, String world,
                                                       int chunkX, int chunkZ,
                                                       List<Claim> alreadyReleased) {
        for (Claim claim : alreadyReleased) {
            if (claim.world().equalsIgnoreCase(world)
                    && claim.chunkX() == chunkX && claim.chunkZ() == chunkZ) {
                return Result.failure("NOT_YOUR_CLAIM", "claim.not-yours");
            }
        }
        return checkUnclaimable(actor, city, world, chunkX, chunkZ);
    }

    // ==================================================================================
    // Releasing land the city can no longer pay for, SPEC 17.3 case 32
    // ==================================================================================

    /**
     * The chunks a debt sweep may take, outermost first.
     *
     * <p>"Outermost" is Chebyshev distance from the core, so a city shrinks inward rather
     * than losing its middle. Anything that cannot legally go is filtered out here rather
     * than attempted and refused: the core, the chunk holding the city spawn, outposts, and
     * any chunk whose removal would strand land (SPEC 6.1). Filtering by contiguity one
     * candidate at a time would be wrong, since removing the furthest chunk can make the
     * next-furthest safe to remove, so the caller re-asks after each release.
     *
     * @param limit how many to return
     */
    public List<Claim> releasableForDebt(City city, int limit) {
        return registry.claimsOf(city.id()).stream()
                .filter(claim -> claim.type() == ClaimType.NORMAL)
                .filter(claim -> !containsSpawn(city, claim))
                .filter(claim -> checkContiguity(city, claim).isSuccess())
                .sorted(Comparator.comparingInt((Claim claim) ->
                        distanceFromCore(city, claim.world(), claim.chunkX(), claim.chunkZ()))
                        .reversed()
                        .thenComparing(Comparator.comparingLong(Claim::claimedAt).reversed()))
                .limit(Math.max(0, limit))
                .toList();
    }

    /**
     * Releases a chunk on the plugin's own authority, refunding to the treasury as usual.
     *
     * <p>No permission check and no actor: this is the server collecting on a debt, not a
     * member giving land up, and SPEC 17.3 case 32 requires it to happen whether or not
     * anyone with UNCLAIM is online. Every other rule still applies, and the refund is what
     * may clear the debt.
     */
    public CompletableFuture<Result<Claim>> releaseForDebt(City city, Claim claim) {
        if (claim.isCore()) {
            return completed(Result.failure("CORE_CHUNK", "claim.core"));
        }
        if (containsSpawn(city, claim)) {
            return completed(Result.failure("CONTAINS_SPAWN", "claim.contains-spawn"));
        }
        Result<Void> contiguity = checkContiguity(city, claim);
        if (contiguity instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }

        BigDecimal refund = costs.refundFor(claim.costPaid());
        if (!events.fire(new ChunkUnclaimEvent(city, null, claim, refund))) {
            return completed(Result.failure("CANCELLED", "claim.unclaim-cancelled"));
        }

        return db.transaction(connection -> releaseClaim(connection, null, city, claim, refund))
                .thenApply(result -> applyOnMain(result, registry::remove));
    }

    // ==================================================================================
    // Consequences of losing land
    // ==================================================================================

    /**
     * SPEC 17.2 case 22: if the chunk holding the city spawn stops being owned, spawn falls
     * back to the centre of the core chunk and the mayor is told.
     */
    private void resetSpawnIfStranded(City city, Claim released) {
        if (!containsSpawn(city, released)) {
            return;
        }
        double x = city.coreChunkX() * 16.0 + 8.5;
        double z = city.coreChunkZ() * 16.0 + 8.5;
        city.setSpawn(x, city.spawnY(), z, city.spawnYaw(), city.spawnPitch());

        db.run(connection -> {
            CityRow row = city.toRow();
            return daos.cities().update(connection, row);
        });
    }

    private boolean containsSpawn(City city, Claim claim) {
        return claim.world().equalsIgnoreCase(city.coreWorld())
                && ChunkKey.toChunk((int) Math.floor(city.spawnX())) == claim.chunkX()
                && ChunkKey.toChunk((int) Math.floor(city.spawnZ())) == claim.chunkZ();
    }

    /**
     * SPEC 17.2 case 19: when the core is force-unclaimed by an admin, the oldest remaining
     * claim is promoted so the city keeps an anchor.
     *
     * @return the promoted claim, or empty if the city has no land left and is now homeless
     */
    public CompletableFuture<Optional<Claim>> promoteOldestToCore(City city) {
        Optional<Claim> oldest = registry.claimsOf(city.id()).stream()
                .filter(claim -> claim.type() == ClaimType.NORMAL)
                .min(Comparator.comparingLong(Claim::claimedAt));

        if (oldest.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Claim promoted = oldest.get();
        return db.run(connection -> {
            daos.claims().updateType(connection, promoted.id(), ClaimType.CORE.name(), null);
            CityRow row = city.toRow();
            return daos.cities().update(connection, new CityRow(row.id(), row.name(),
                    row.displayName(), row.tag(), row.mayorUuid(), row.foundedAt(), row.treasury(),
                    promoted.world(), promoted.chunkX(), promoted.chunkZ(),
                    row.spawnX(), row.spawnY(), row.spawnZ(), row.spawnYaw(), row.spawnPitch(),
                    row.openJoin(), row.motd(), row.upkeepDue(), row.delinquentSince(),
                    row.warProtectionUntil(), row.frozen(), row.deletedAt()));
        }).thenApply(ignored -> {
            scheduler.runOnMain(() -> {
                promoted.convertTo(ClaimType.CORE, null);
                city.setCore(promoted.world(), promoted.chunkX(), promoted.chunkZ());
            });
            return Optional.of(promoted);
        });
    }

    /** Drops a disbanded city's land from the cache. The rows are deleted by the city service. */
    public void forgetCity(int cityId) {
        registry.removeCity(cityId);
    }

    /**
     * Adds a claim that has already committed to the cache.
     *
     * <p>Used by city creation for the core chunk, which is written through
     * {@link #writeClaim} inside the founding transaction and so is not registered by any of
     * the paths above.
     */
    public void register(Claim claim) {
        registry.put(claim);
    }

    /** Total invested in a city's land, the base SPEC 4.3 upkeep is a percentage of. */
    public BigDecimal landValueOf(int cityId) {
        return ClaimCostEngine.landValue(registry.claimsOf(cityId));
    }

    // ==================================================================================
    // Auto-claim, SPEC 6.3
    // ==================================================================================

    /** @return the new state */
    public boolean toggleAutoClaim(UUID player) {
        if (autoClaiming.remove(player)) {
            return false;
        }
        autoClaiming.add(player);
        return true;
    }

    public boolean isAutoClaiming(UUID player) {
        return autoClaiming.contains(player);
    }

    public void stopAutoClaiming(UUID player) {
        autoClaiming.remove(player);
    }

    // ==================================================================================
    // Seams for milestones that do not exist yet
    // ==================================================================================

    /**
     * SPEC 6.3 precondition 9 and SPEC 11.11: no claiming or unclaiming while a war is on.
     *
     * <p>Always false until M19 builds the war system. Written out rather than omitted so
     * there is exactly one place to fill in, and so the refusal message already exists.
     */
    private boolean isCityAtWar(City city) {
        return false;
    }

    /**
     * SPEC 6.3 precondition 10: admin-protected chunks cannot be claimed.
     *
     * <p>Always false until M21 adds {@code /ca claim protect}. Same reasoning as above.
     */
    private boolean isAdminProtected(String world, int chunkX, int chunkZ) {
        return false;
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private Result<Void> checkWorld(String world) {
        FileConfiguration config = configs.get(ConfigFile.CONFIG);
        if (config.getStringList("worlds.blacklisted").stream()
                .anyMatch(name -> name.equalsIgnoreCase(world))) {
            return Result.failure("WORLD_BLACKLISTED", "city.create.world-blocked");
        }
        if (config.getStringList("worlds.city-enabled").stream()
                .noneMatch(name -> name.equalsIgnoreCase(world))) {
            return Result.failure("WORLD_NOT_ENABLED", "city.create.world-disabled");
        }
        return Result.ok();
    }

    /** The chunks of an n-by-n square, in no particular order. */
    static List<int[]> square(int centreX, int centreZ, int arm) {
        List<int[]> chunks = new ArrayList<>();
        for (int dx = -arm; dx <= arm; dx++) {
            for (int dz = -arm; dz <= arm; dz++) {
                chunks.add(new int[] {centreX + dx, centreZ + dz});
            }
        }
        return chunks;
    }

    /**
     * Orders a square so each chunk is adjacent to something owned by the time it is bought.
     *
     * <p>Buying centre-outward looks natural and is wrong: a player standing two chunks
     * outside their own border and asking for a 3-by-3 has a perfectly legal square whose
     * near edge touches their land, but whose centre touches nothing. Ordering by distance
     * from the centre would try the centre first and refuse the lot.
     *
     * <p>So the square is grown instead of scanned: repeatedly take any chunk that is
     * adjacent to the city's existing land or to a chunk already taken in this pass. What is
     * left when no chunk qualifies is genuinely unreachable.
     *
     * @return the order to buy in, or null if part of the square could never be adjacent
     */
    private List<int[]> growthOrder(City city, String world, int centreX, int centreZ, int arm) {
        Set<Contiguity.Chunk> reachable =
                new LinkedHashSet<>(registry.contiguousChunksOf(city.id(), world));
        boolean cityHasNoLandHere = reachable.isEmpty();

        List<int[]> remaining = new ArrayList<>(square(centreX, centreZ, arm));
        List<int[]> order = new ArrayList<>(remaining.size());

        if (cityHasNoLandHere) {
            // Nothing to chain from, so adjacency does not apply and the checks downstream
            // will decide. Centre-first is as good an order as any.
            remaining.sort(Comparator.comparingInt(
                    target -> ChunkKey.chebyshev(centreX, centreZ, target[0], target[1])));
            return remaining;
        }

        boolean progressed = true;
        while (progressed && !remaining.isEmpty()) {
            progressed = false;
            for (java.util.Iterator<int[]> it = remaining.iterator(); it.hasNext();) {
                int[] target = it.next();
                Contiguity.Chunk candidate = new Contiguity.Chunk(target[0], target[1]);
                if (Contiguity.isAdjacent(reachable, candidate)) {
                    order.add(target);
                    reachable.add(candidate);
                    it.remove();
                    progressed = true;
                }
            }
        }

        return remaining.isEmpty() ? order : null;
    }

    private static Map<String, String> withChunk(Map<String, String> base, int chunkX, int chunkZ) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(base);
        merged.put("chunk", chunkX + "," + chunkZ);
        return merged;
    }

    private static String chunkMetadata(String world, int chunkX, int chunkZ) {
        return "{\"world\":\"" + world + "\",\"chunk_x\":" + chunkX + ",\"chunk_z\":" + chunkZ + "}";
    }

    private <T> Result<T> applyOnMain(Result<T> result, java.util.function.Consumer<T> apply) {
        if (result instanceof Result.Success<T>(T value) && value != null) {
            scheduler.runOnMain(() -> apply.accept(value));
        }
        return result;
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /** Same reasoning as {@code CityService.isUniqueViolation}: SQLState class 23. */
    static boolean isUniqueViolation(SQLException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("23")) {
                    return true;
                }
                String message = sql.getMessage();
                if (message != null && message.contains("UNIQUE constraint failed")) {
                    return true;
                }
            }
        }
        return false;
    }
}
