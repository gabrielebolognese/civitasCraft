package dev.civitas.core.mining;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.Money;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.world.WorldRegistry;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.MiningClaimDao;
import dev.civitas.storage.row.MiningClaimRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;

/**
 * SPEC 32.6's personal mining claims.
 *
 * <p>One chunk, 15,000 C, 500 C a day from the player's <b>own</b> balance rather than a city
 * treasury — which is the whole point. SPEC 32.6: "This is the only form of land ownership
 * available to a player with no city, and it is deliberately available to them."
 *
 * <p>SPEC 32.5 blocks city claims in the resource worlds, and SPEC 33.5 blocks PvP there. Without
 * this, everything a player builds in the place the economy expects them to mine is griefable,
 * and so nobody builds there: "an unreset resource world with persistent mines will accumulate
 * real infrastructure that players will be upset to lose."
 *
 * <h2>Released, not destroyed</h2>
 *
 * <p>SPEC 32.6 on unpaid upkeep: "7-day grace, then released. <b>Blocks are not removed.</b>"
 * The same choice SPEC 6.4 makes for city land — a lapsed claim leaves a mine standing and
 * unprotected, rather than deleting weeks of somebody's digging over a missed payment.
 */
public final class MiningClaimService {

    private final DatabaseManager db;
    private final MiningClaimDao dao;
    private final MiningClaimRegistry registry;
    private final EconomyService economy;
    private final WorldRegistry worlds;
    private final ConfigManager configs;
    private final Scheduler scheduler;

    /**
     * How an owner is told about their upkeep.
     *
     * <p>Absent means nobody is told, which is what a test constructs. In production it is
     * always set: SPEC 23.1's first principle is that "every action produces feedback", and a
     * player whose mining claim is released without a word is the exact silent failure that
     * principle exists to prevent. This was orphaned lang text for about ten minutes until the
     * key-usage sweep found the two messages nothing sent.
     */
    private Notifier notifier = (owner, key, extra) -> { };

    /** Tells one player something, if they are around to hear it. */
    @FunctionalInterface
    public interface Notifier {

        void tell(UUID owner, String messageKey,
                  net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra);
    }

    public MiningClaimService(DatabaseManager db, MiningClaimDao dao,
                             MiningClaimRegistry registry, EconomyService economy,
                             WorldRegistry worlds, ConfigManager configs, Scheduler scheduler) {
        this.db = Objects.requireNonNull(db, "db");
        this.dao = Objects.requireNonNull(dao, "dao");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Hands the service a way to reach the owner. Called once, on wiring. */
    public void useNotifier(Notifier target) {
        this.notifier = Objects.requireNonNull(target, "target");
    }

    public MiningClaimRegistry registry() {
        return registry;
    }

    // ==================================================================================
    // Claiming
    // ==================================================================================

    /**
     * Claims one chunk for a player, SPEC 32.6.
     *
     * @param limit how many claims this player may hold, from their permission node
     */
    public CompletableFuture<Result<MiningClaimRow>> claim(UUID player, String world,
                                                           int chunkX, int chunkZ, int limit,
                                                           long now) {
        if (!worlds.allowsMiningClaims(world)) {
            // No placeholder: the message tells the player where they CAN claim, which is more
            // use than the name of the world they are standing in. Passing a value a message
            // never shows is the defect M23's localisation check exists to catch.
            return completed(Result.failure("WRONG_WORLD", "mine.wrong-world"));
        }
        if (registry.isClaimed(world, chunkX, chunkZ)) {
            return completed(Result.failure("ALREADY_CLAIMED", "mine.already-claimed"));
        }
        int owned = registry.ownedBy(player).size();
        if (owned >= limit) {
            return completed(Result.failure("AT_LIMIT", "mine.at-limit",
                    Map.of("limit", String.valueOf(limit))));
        }

        BigDecimal cost = cost();
        return db.transaction(connection -> {
            // The unique index is the real guarantee, SPEC 3.4's shape: two players racing for
            // one chunk are settled by the database and the loser's money never leaves, because
            // the whole transaction rolls back.
            if (dao.findAtSync(connection, world, chunkX, chunkZ).isPresent()) {
                return Result.<MiningClaimRow>failure("ALREADY_CLAIMED", "mine.already-claimed");
            }
            if (dao.findOwnedSync(connection, player).size() >= limit) {
                return Result.<MiningClaimRow>failure("AT_LIMIT", "mine.at-limit",
                        Map.of("limit", String.valueOf(limit)));
            }

            Result<BigDecimal> paid = economy.withdraw(connection, player, cost,
                    TransactionType.CHUNK_CLAIM, null,
                    "{\"mining_claim\":\"" + world + ":" + chunkX + ":" + chunkZ + "\"}");
            if (paid instanceof Result.Failure<BigDecimal> failure) {
                return Result.<MiningClaimRow>propagate(failure);
            }

            MiningClaimRow row = new MiningClaimRow(0, player, world, chunkX, chunkZ, now,
                    cost, null);
            long id = dao.insertSync(connection, row);
            return Result.success(new MiningClaimRow(id, player, world, chunkX, chunkZ, now,
                    cost, null));
        }).thenCompose(result -> onMain(result, row -> registry.remember(row)));
    }

    /**
     * Releases a claim, refunding half of what was paid.
     *
     * <p>Half, to the player, matching SPEC 6.4's refund on city land. The refund is of
     * {@code cost_paid} rather than of the current price, which is the SPEC 21.4 F2 rule: a
     * discount must not be launderable into a full-price refund.
     */
    public CompletableFuture<Result<MiningClaimRow>> unclaim(UUID player, String world,
                                                             int chunkX, int chunkZ) {
        Optional<MiningClaimRow> existing = registry.at(world, chunkX, chunkZ);
        if (existing.isEmpty()) {
            return completed(Result.failure("NOT_CLAIMED", "mine.not-claimed"));
        }
        if (!existing.get().uuid().equals(player)) {
            return completed(Result.failure("NOT_OWNER", "mine.not-owner"));
        }

        MiningClaimRow row = existing.get();
        BigDecimal refund = Money.floor(row.costPaid()
                .multiply(BigDecimal.valueOf(refundPercent()))
                .divide(BigDecimal.valueOf(100), java.math.RoundingMode.DOWN));

        return db.transaction(connection -> {
            if (dao.deleteSync(connection, row.id()) == 0) {
                return Result.<MiningClaimRow>failure("NOT_CLAIMED", "mine.not-claimed");
            }
            if (refund.signum() > 0) {
                Result<BigDecimal> given = economy.deposit(connection, player, refund,
                        TransactionType.CHUNK_UNCLAIM_REFUND, null,
                        "{\"mining_claim\":" + row.id() + "}");
                if (given instanceof Result.Failure<BigDecimal> failure) {
                    return Result.<MiningClaimRow>propagate(failure);
                }
            }
            return Result.success(row);
        }).thenCompose(result -> onMain(result, registry::forget));
    }

    // ==================================================================================
    // Trust, SPEC 32.6
    // ==================================================================================

    /** Grants a player build access to everything this owner holds. */
    public CompletableFuture<Result<UUID>> trust(UUID owner, UUID player, long now) {
        if (owner.equals(player)) {
            return completed(Result.failure("SELF_TRUST", "mine.self-trust"));
        }
        if (registry.trustedBy(owner).contains(player)) {
            return completed(Result.failure("ALREADY_TRUSTED", "mine.already-trusted"));
        }
        int max = maxTrusted();
        if (registry.trustedBy(owner).size() >= max) {
            return completed(Result.failure("TRUST_FULL", "mine.trust-full",
                    Map.of("max", String.valueOf(max))));
        }
        return db.transaction(connection -> {
            dao.trustSync(connection, owner, player, now);
            return Result.success(player);
        }).thenCompose(result -> onMain(result, trusted ->
                registry.rememberTrust(owner, trusted)));
    }

    /** Revokes it. */
    public CompletableFuture<Result<UUID>> untrust(UUID owner, UUID player) {
        if (!registry.trustedBy(owner).contains(player)) {
            return completed(Result.failure("NOT_TRUSTED", "mine.not-trusted"));
        }
        try {
            return dao.untrust(owner, player).thenApply(removed -> {
                scheduler.runOnMain(() -> registry.forgetTrust(owner, player));
                return Result.success(player);
            });
        } catch (RuntimeException e) {
            return completed(Result.failure("STORAGE", "command.error"));
        }
    }

    // ==================================================================================
    // Upkeep, SPEC 32.6
    // ==================================================================================

    /**
     * Charges every claim its daily upkeep, from the owner's personal balance.
     *
     * @return how many claims were released for having run out of grace
     */
    public CompletableFuture<Integer> chargeUpkeep(long now) {
        BigDecimal daily = upkeepPerDay();
        long grace = graceMillis();

        return CompletableFuture.supplyAsync(() -> 0).thenCompose(ignored -> {
            CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
            for (MiningClaimRow row : registry.all()) {
                chain = chain.thenCompose(released ->
                        chargeOne(row, daily, grace, now).thenApply(gone -> released + gone));
            }
            return chain;
        });
    }

    private CompletableFuture<Integer> chargeOne(MiningClaimRow row, BigDecimal daily,
                                                 long grace, long now) {
        if (row.graceExpired(now, grace)) {
            // SPEC 32.6: released, and the blocks stay. No refund: the upkeep was not paid.
            return db.transaction(connection -> {
                dao.deleteSync(connection, row.id());
                return Result.success(row);
            }).thenApply(result -> {
                scheduler.runOnMain(() -> {
                    registry.forget(row);
                    notifier.tell(row.uuid(), "mine.released",
                            dev.civitas.lang.LangManager.placeholder("chunk",
                                    dev.civitas.msg.Formats.chunk(row.chunkX(), row.chunkZ())));
                });
                return 1;
            });
        }

        return db.transaction(connection -> {
            Result<BigDecimal> paid = economy.withdraw(connection, row.uuid(), daily,
                    TransactionType.UPKEEP_CHARGE, null,
                    "{\"mining_claim\":" + row.id() + "}");
            if (paid instanceof Result.Failure<BigDecimal>) {
                // Cannot pay. Start the clock rather than releasing at once, and leave it
                // running if it is already going: the grace is from the first missed payment.
                if (!row.isDelinquent()) {
                    dao.setDelinquentSync(connection, row.id(), now);
                }
                return Result.success(false);
            }
            if (row.isDelinquent()) {
                dao.setDelinquentSync(connection, row.id(), null);
            }
            return Result.success(true);
        }).thenApply(result -> {
            boolean paid = result instanceof Result.Success<Boolean>(Boolean ok) && ok;
            Long delinquentSince = paid
                    ? null
                    : row.isDelinquent() ? row.delinquentSince() : now;
            scheduler.runOnMain(() -> {
                registry.remember(new MiningClaimRow(row.id(), row.uuid(), row.world(),
                        row.chunkX(), row.chunkZ(), row.claimedAt(), row.costPaid(),
                        delinquentSince));
                if (!paid) {
                    long left = graceMillis() - (now - delinquentSince);
                    notifier.tell(row.uuid(), "mine.upkeep-failed",
                            dev.civitas.lang.LangManager.placeholder("amount",
                                    Money.format(daily, configs)),
                            dev.civitas.lang.LangManager.placeholder("days",
                                    String.valueOf(Math.max(0, left / 86_400_000L))));
                }
            });
            return 0;
        });
    }

    // ==================================================================================
    // Configuration, SPEC 37
    // ==================================================================================

    public BigDecimal cost() {
        return BigDecimal.valueOf(configs.get(ConfigFile.WORLD)
                .getDouble("mining-claims.cost", 15_000));
    }

    public BigDecimal upkeepPerDay() {
        return BigDecimal.valueOf(configs.get(ConfigFile.WORLD)
                .getDouble("mining-claims.upkeep-per-day", 500));
    }

    /** SPEC 32.6's base limit of one, before {@code civitas.limit.miningclaims.<n>}. */
    public int baseLimit() {
        return configs.get(ConfigFile.WORLD).getInt("mining-claims.base-limit", 1);
    }

    public int maxTrusted() {
        return configs.get(ConfigFile.WORLD).getInt("mining-claims.max-trusted", 4);
    }

    public long graceMillis() {
        return configs.get(ConfigFile.WORLD).getInt("mining-claims.grace-days", 7)
                * 86_400_000L;
    }

    /** Half, matching SPEC 6.4's refund on city land. */
    public int refundPercent() {
        return configs.get(ConfigFile.WORLD).getInt("mining-claims.unclaim-refund-percent", 50);
    }

    public boolean enabled() {
        return configs.get(ConfigFile.WORLD).getBoolean("mining-claims.enabled", true);
    }

    // ==================================================================================
    // Plumbing
    // ==================================================================================

    private <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /** Applies a cache change on the server thread, then hands the result back unchanged. */
    private <T> CompletableFuture<Result<T>> onMain(Result<T> result,
                                                    java.util.function.Consumer<T> change) {
        if (result instanceof Result.Success<T>(T value)) {
            CompletableFuture<Result<T>> done = new CompletableFuture<>();
            scheduler.runOnMain(() -> {
                change.accept(value);
                done.complete(result);
            });
            return done;
        }
        return completed(result);
    }
}
