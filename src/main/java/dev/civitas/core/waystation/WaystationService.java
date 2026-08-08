package dev.civitas.core.waystation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.core.mining.MiningClaimRegistry;
import dev.civitas.core.world.WorldRegistry;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.WaystationChunkRow;
import dev.civitas.storage.row.WaystationRow;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;

/**
 * Creating, growing and removing waystations, SPEC 39.10.
 *
 * <p>Deliberately not a variant of {@code OutpostService}. The two share a silhouette — a
 * multi-chunk holding, bought from the treasury, with a warp point — and almost none of their
 * rules: a waystation is priced from its world's spawn rather than the city core, capped at one
 * per world rather than counted against the outpost pool, never a war target, and never allowed
 * a defense unit. Folding them together would mean a class in which every rule carried a
 * condition, and SPEC 39.10 opens by calling a waystation "a different thing with a different
 * purpose".
 */
public final class WaystationService {

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final WaystationRegistry registry;
    private final WaystationCostEngine costs;
    private final TreasuryService treasury;
    private final MiningClaimRegistry mining;
    private final WorldRegistry worlds;
    private final ConfigManager configs;
    private final Scheduler scheduler;

    public WaystationService(DatabaseManager db, DaoRegistry daos, WaystationRegistry registry,
                             TreasuryService treasury, MiningClaimRegistry mining,
                             ConfigManager configs, Scheduler scheduler) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.mining = Objects.requireNonNull(mining, "mining");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.costs = new WaystationCostEngine(configs);
        this.worlds = new WorldRegistry(configs);
    }

    public WaystationRegistry registry() {
        return registry;
    }

    public WaystationCostEngine costs() {
        return costs;
    }

    // ==================================================================================
    // Creating, SPEC 39.10
    // ==================================================================================

    /** Whether a waystation may be founded here, and what the founding chunk would cost. */
    public Result<BigDecimal> checkCreatable(UUID actor, City city, String world,
                                             int chunkX, int chunkZ) {
        Result<Void> shared = checkShared(actor, city, world, chunkX, chunkZ);
        if (shared instanceof Result.Failure<Void> failure) {
            return Result.propagate(failure);
        }
        if (registry.of(city.id(), world).isPresent()) {
            // SPEC 39.10: one per city per resource world. A separate pool from the outpost
            // limit, so a city at its outpost cap may still found both of these.
            return Result.failure("WAYSTATION_EXISTS", "waystation.already-here",
                    Map.of("world", world));
        }
        return Result.success(costs.chunkCost(1, blocksFromSpawn(world, chunkX, chunkZ)));
    }

    public CompletableFuture<Result<Waystation>> create(UUID actor, City city, String world,
                                                        int chunkX, int chunkZ,
                                                        double warpX, double warpY, double warpZ,
                                                        float warpYaw, float warpPitch) {
        Result<BigDecimal> checked = checkCreatable(actor, city, world, chunkX, chunkZ);
        if (checked instanceof Result.Failure<BigDecimal> failure) {
            return CompletableFuture.completedFuture(Result.propagate(failure));
        }
        BigDecimal cost = checked.orElseThrow();
        long now = System.currentTimeMillis();

        return db.transaction(connection -> {
            Result<BigDecimal> paid = treasury.adjust(connection, city, cost.negate(),
                    TransactionType.CHUNK_CLAIM, actor,
                    "{\"waystation\":\"" + world + "\",\"chunk\":1}");
            if (paid instanceof Result.Failure<BigDecimal> failure) {
                return Result.<Created>propagate(failure);
            }

            int id = daos.waystations().insertSync(connection,
                    new WaystationRow(0, city.id(), world, now,
                            warpX, warpY, warpZ, warpYaw, warpPitch));
            // One transaction for both rows: a waystation with no chunk, or a chunk with no
            // waystation, is a state nothing in the plugin knows how to read.
            WaystationChunkRow chunk = new WaystationChunkRow(0, id, world, chunkX, chunkZ,
                    now, cost);
            long chunkId = daos.waystations().insertChunkSync(connection, chunk);

            return Result.success(new Created(
                    new Waystation(id, city.id(), world, warpX, warpY, warpZ,
                            warpYaw, warpPitch, now),
                    new WaystationChunkRow(chunkId, id, world, chunkX, chunkZ, now, cost)));
        }).thenApply(result -> {
            Result<Created> applied = applyOnMain(result, created -> {
                registry.put(created.waystation());
                registry.putChunk(created.chunk());
            });
            return applied instanceof Result.Success<Created> success
                    ? Result.success(success.value().waystation())
                    : Result.<Waystation>propagate((Result.Failure<Created>) applied);
        });
    }

    // ==================================================================================
    // Growing to the second chunk, SPEC 39.10
    // ==================================================================================

    /** Whether this chunk may join the city's waystation in this world, and what it costs. */
    public Result<BigDecimal> checkExpandable(UUID actor, City city, String world,
                                              int chunkX, int chunkZ) {
        Result<Void> shared = checkShared(actor, city, world, chunkX, chunkZ);
        if (shared instanceof Result.Failure<Void> failure) {
            return Result.propagate(failure);
        }
        Waystation waystation = registry.of(city.id(), world).orElse(null);
        if (waystation == null) {
            return Result.failure("NO_WAYSTATION", "waystation.none-here",
                    Map.of("world", world));
        }

        List<WaystationChunkRow> held = registry.chunksOf(waystation.id());
        if (held.size() >= costs.maxChunks()) {
            return Result.failure("WAYSTATION_FULL", "waystation.full",
                    Map.of("max", String.valueOf(costs.maxChunks())));
        }
        if (!bordersAny(held, chunkX, chunkZ)) {
            // SPEC 39.10: "1 to 2 chunks, edge-connected". The same edge rule SPEC 6.1 gives
            // city land, for the same reason: a holding is a place, not scattered tiles.
            return Result.failure("NOT_ADJACENT", "waystation.not-adjacent");
        }
        return Result.success(costs.chunkCost(held.size() + 1,
                blocksFromSpawn(world, chunkX, chunkZ)));
    }

    public CompletableFuture<Result<WaystationChunkRow>> expand(UUID actor, City city,
                                                                String world,
                                                                int chunkX, int chunkZ) {
        Result<BigDecimal> checked = checkExpandable(actor, city, world, chunkX, chunkZ);
        if (checked instanceof Result.Failure<BigDecimal> failure) {
            return CompletableFuture.completedFuture(Result.propagate(failure));
        }
        BigDecimal cost = checked.orElseThrow();
        Waystation waystation = registry.of(city.id(), world).orElseThrow();
        long now = System.currentTimeMillis();

        return db.transaction(connection -> {
            Result<BigDecimal> paid = treasury.adjust(connection, city, cost.negate(),
                    TransactionType.CHUNK_CLAIM, actor,
                    "{\"waystation\":\"" + world + "\",\"chunk\":2}");
            if (paid instanceof Result.Failure<BigDecimal> failure) {
                return Result.<WaystationChunkRow>propagate(failure);
            }
            WaystationChunkRow row = new WaystationChunkRow(0, waystation.id(), world,
                    chunkX, chunkZ, now, cost);
            long id = daos.waystations().insertChunkSync(connection, row);
            return Result.success(new WaystationChunkRow(id, waystation.id(), world,
                    chunkX, chunkZ, now, cost));
        }).thenApply(result -> applyOnMain(result, registry::putChunk));
    }

    // ==================================================================================
    // Removing
    // ==================================================================================

    /**
     * Deletes a waystation, refunding half of every chunk to the treasury.
     *
     * <p>SPEC 39.10's table names no refund at all. Half is chosen to match what SPEC 39.5
     * gives an outpost and SPEC 6.4 gives city land, on the grounds that a city dismantling
     * infrastructure it paid for should get back what it would get back anywhere else, and
     * that a rule differing from its two neighbours needs a reason SPEC does not supply.
     * Recorded in {@code OPEN_QUESTIONS.md}.
     */
    public CompletableFuture<Result<Waystation>> delete(UUID actor, City city, String world) {
        if (!city.hasPermission(actor, CityPermission.OUTPOST_MANAGE)) {
            return CompletableFuture.completedFuture(Result.failure("NO_CITY_PERMISSION",
                    "city.no-permission",
                    Map.of("permission", CityPermission.OUTPOST_MANAGE.name())));
        }
        if (city.isFrozen()) {
            return CompletableFuture.completedFuture(Result.failure("CITY_FROZEN", "city.frozen"));
        }
        Waystation waystation = registry.of(city.id(), world).orElse(null);
        if (waystation == null) {
            return CompletableFuture.completedFuture(Result.failure("NO_WAYSTATION",
                    "waystation.none-here", Map.of("world", world)));
        }

        List<WaystationChunkRow> held = registry.chunksOf(waystation.id());
        BigDecimal refund = held.stream()
                .map(chunk -> costs.refundFor(chunk.costPaid()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return db.transaction(connection -> {
            daos.waystations().deleteChunksOfSync(connection, waystation.id());
            daos.waystations().deleteSync(connection, waystation.id());

            if (refund.signum() > 0) {
                Result<BigDecimal> paid = treasury.adjust(connection, city, refund,
                        TransactionType.CHUNK_UNCLAIM_REFUND, actor,
                        "{\"waystation\":\"" + world + "\",\"chunks\":" + held.size() + "}");
                if (paid instanceof Result.Failure<BigDecimal> failure) {
                    return Result.<Waystation>propagate(failure);
                }
            }
            return Result.success(waystation);
        }).thenApply(result -> applyOnMain(result,
                removed -> registry.remove(removed.id())));
    }

    /** Every waystation of a disbanded city goes with it. Registered on the disband hook. */
    public CompletableFuture<Integer> removeCity(int cityId) {
        List<Waystation> held = registry.of(cityId);
        if (held.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return db.run(connection -> {
            for (Waystation waystation : held) {
                daos.waystations().deleteChunksOfSync(connection, waystation.id());
                daos.waystations().deleteSync(connection, waystation.id());
            }
            return held.size();
        }).thenApply(ignored -> {
            scheduler.runOnMain(() -> registry.removeCity(cityId));
            return held.size();
        });
    }

    // ==================================================================================
    // Upkeep, SPEC 39.10
    // ==================================================================================

    /** What one waystation costs a day, {@code 1500 * W(d) * chunks}. */
    public BigDecimal upkeepFor(Waystation waystation) {
        List<WaystationChunkRow> held = registry.chunksOf(waystation.id());
        if (held.isEmpty()) {
            return BigDecimal.ZERO;
        }
        WaystationChunkRow founding = held.get(0);
        return costs.upkeepPerDay(held.size(), blocksFromSpawn(waystation.world(),
                founding.chunkX(), founding.chunkZ()));
    }

    /** What all of a city's waystations cost a day, for the upkeep sweep. */
    public BigDecimal upkeepFor(City city) {
        return registry.of(city.id()).stream()
                .map(this::upkeepFor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ==================================================================================
    // Shared rules
    // ==================================================================================

    private Result<Void> checkShared(UUID actor, City city, String world,
                                     int chunkX, int chunkZ) {
        if (!costs.enabled()) {
            return Result.failure("WAYSTATIONS_DISABLED", "waystation.disabled");
        }
        if (!city.hasPermission(actor, CityPermission.OUTPOST_MANAGE)) {
            return Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.OUTPOST_MANAGE.name()));
        }
        if (city.isFrozen()) {
            return Result.failure("CITY_FROZEN", "city.frozen");
        }
        if (!isResourceWorld(world)) {
            return Result.failure("WRONG_WORLD", "waystation.wrong-world");
        }
        if (registry.isClaimed(world, chunkX, chunkZ)) {
            return Result.failure("CHUNK_CLAIMED", "waystation.chunk-taken");
        }
        if (mining.isClaimed(world, chunkX, chunkZ)) {
            // The two systems that can own ground in a resource world do not overlap. SPEC
            // 39.10 says they "coexist and do not overlap", and a player may hold both — but
            // not on the same chunk.
            return Result.failure("MINING_CLAIM_HERE", "waystation.mining-claim-here");
        }
        return Result.success(null);
    }

    /** SPEC 39.10: {@code resource} and {@code resource_nether} only. */
    public boolean isResourceWorld(String world) {
        List<String> allowed = configs.get(ConfigFile.CITIES)
                .getStringList("waystations.worlds");
        if (!allowed.isEmpty()) {
            return allowed.stream().anyMatch(name -> name.equalsIgnoreCase(world));
        }
        // Falls back to the world registry rather than to a hardcoded pair, so an operator who
        // renamed their resource worlds in world.yml does not have to say so twice.
        return world != null
                && (world.equalsIgnoreCase(worlds.resource())
                        || world.equalsIgnoreCase(worlds.resourceNether()));
    }

    /**
     * SPEC 39.10 measures from that world's spawn, "not from the city core".
     *
     * <p>It has to: the core is in {@code world} and the waystation is in {@code resource}, so
     * there is no distance between them to measure. The world spawn is also where every player
     * arrives, which makes the premium mean what it looks like it means.
     */
    public double blocksFromSpawn(String world, int chunkX, int chunkZ) {
        double blockX = chunkX * 16.0 + 8.0;
        double blockZ = chunkZ * 16.0 + 8.0;

        org.bukkit.World loaded = org.bukkit.Bukkit.getServer() == null
                ? null
                : org.bukkit.Bukkit.getWorld(world);
        double spawnX = 0.0;
        double spawnZ = 0.0;
        if (loaded != null) {
            spawnX = loaded.getSpawnLocation().getX();
            spawnZ = loaded.getSpawnLocation().getZ();
        }
        return Math.hypot(blockX - spawnX, blockZ - spawnZ);
    }

    private static boolean bordersAny(List<WaystationChunkRow> held, int chunkX, int chunkZ) {
        for (WaystationChunkRow chunk : held) {
            int dx = Math.abs(chunk.chunkX() - chunkX);
            int dz = Math.abs(chunk.chunkZ() - chunkZ);
            if (dx + dz == 1) {
                return true;
            }
        }
        return false;
    }

    private <T> Result<T> applyOnMain(Result<T> result, java.util.function.Consumer<T> action) {
        if (result instanceof Result.Success<T> success) {
            scheduler.runOnMain(() -> action.accept(success.value()));
        }
        return result;
    }

    /** Both rows a creation writes, so the cache is updated from one place. */
    private record Created(Waystation waystation, WaystationChunkRow chunk) {
    }
}
