package dev.civitas.core.city;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.api.event.CityCreateEvent;
import dev.civitas.api.event.CityDisbandEvent;
import dev.civitas.api.event.CityJoinEvent;
import dev.civitas.api.event.CityKickEvent;
import dev.civitas.api.event.CityLeaveEvent;
import dev.civitas.api.event.CityTransferEvent;
import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ClaimService;
import dev.civitas.core.claim.ClaimType;
import dev.civitas.core.economy.Funds;
import dev.civitas.core.economy.PlayerAccountService;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.CityBanRow;
import dev.civitas.storage.row.CityInviteRow;
import dev.civitas.storage.row.CityMemberRow;
import dev.civitas.storage.row.CityRankRow;
import dev.civitas.storage.row.CityRow;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.EventBus;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Every mutation of a city's identity and membership, SPEC 5.
 *
 * <p>Commands and GUIs call this and never a DAO (SPEC 2.3). Each operation follows the same
 * shape: cheap in-memory checks first so an obvious mistake costs no database round trip, a
 * cancellable event next, then one transaction that both re-checks the conditions the
 * database owns and performs every write, then a cache update back on the server thread.
 *
 * <p>The re-check inside the transaction is not redundant. Two players can pass the same
 * in-memory check in the same tick; only the database can settle who wins, which is exactly
 * what SPEC 17.1 case 6 and SPEC 17.2 cases 14 and 15 describe.
 */
public final class CityService {

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final CityRegistry registry;
    private final ConfigManager configs;
    private final CityNameValidator nameValidator;
    private final Funds funds;
    private final ClaimService claims;
    private final PlayerAccountService accounts;
    private final Scheduler scheduler;
    private final EventBus events;

    /** Pending mayorship transfers awaiting the target's acceptance, SPEC 5.3. */
    private final Map<UUID, PendingTransfer> pendingTransfers = new ConcurrentHashMap<>();

    private record PendingTransfer(int cityId, UUID from, long expiresAt) { }

    public CityService(DatabaseManager db, DaoRegistry daos, CityRegistry registry,
                       ConfigManager configs, CityNameValidator nameValidator, Funds funds,
                       ClaimService claims, PlayerAccountService accounts, Scheduler scheduler,
                       EventBus events) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.nameValidator = Objects.requireNonNull(nameValidator, "nameValidator");
        this.funds = Objects.requireNonNull(funds, "funds");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.events = Objects.requireNonNull(events, "events");
    }

    public CityRegistry registry() {
        return registry;
    }

    // ==================================================================================
    // Creation, SPEC 5.1
    // ==================================================================================

    /**
     * Founds a city.
     *
     * <p>The nine SPEC 5.1 preconditions are checked in the order the specification lists
     * them, so the message a player gets names the first thing actually wrong.
     *
     * @param founder  who is founding it
     * @param rawName  the name exactly as typed
     * @param place    the chunk that becomes the core and the position that becomes spawn
     */
    public CompletableFuture<Result<City>> create(UUID founder, String rawName, Placement place) {
        long now = System.currentTimeMillis();

        // 1. Not already in a city.
        if (registry.isInAnyCity(founder)) {
            return completed(Result.failure("ALREADY_IN_CITY", "city.create.already-in-city"));
        }

        // 4 and 5a. Name shape and blocked list.
        Result<String> nameCheck = nameValidator.validate(rawName);
        if (nameCheck instanceof Result.Failure<String> failure) {
            return completed(Result.propagate(failure));
        }

        // 5b. Name not taken, checked against the cache first for a fast, clear refusal.
        if (registry.isNameTaken(rawName)) {
            return completed(Result.failure("NAME_TAKEN", "city.create.name-taken",
                    Map.of("name", rawName)));
        }

        // 9. World is city-enabled, and 8. not blacklisted.
        Result<Void> worldCheck = checkWorld(place.world());
        if (worldCheck instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }

        if (!events.fire(new CityCreateEvent(founder, rawName))) {
            return completed(Result.failure("CANCELLED", "city.create.cancelled"));
        }

        FileConfiguration cities = configs.get(ConfigFile.CITIES);
        BigDecimal cost = money(cities, "creation.cost", "10000");
        long requiredPlaytime = cities.getLong("creation.min-playtime-hours", 2) * MILLIS_PER_HOUR;
        int minDistance = cities.getInt("creation.min-distance-chunks", 5);
        long disbandCooldown = cities.getLong("creation.disband-cooldown-hours", 24) * MILLIS_PER_HOUR;

        // Captured from inside the transaction so the cache update below can register the
        // core claim only once the whole founding has actually committed.
        java.util.concurrent.atomic.AtomicReference<Claim> coreClaim =
                new java.util.concurrent.atomic.AtomicReference<>();

        return db.transaction(connection -> {
            Optional<PlayerRow> playerRow = daos.players().findByUuid(connection, founder);
            if (playerRow.isEmpty()) {
                return Result.<City>failure("NO_PLAYER_RECORD", "economy.no-account");
            }
            PlayerRow player = playerRow.get();

            // 2. Active playtime.
            long activePlaytime = accounts.effectiveActivePlaytime(player, now);
            if (activePlaytime < requiredPlaytime) {
                return Result.<City>failure("INSUFFICIENT_PLAYTIME", "city.create.playtime",
                        Map.of("required", String.valueOf(requiredPlaytime / MILLIS_PER_HOUR),
                                "have", String.valueOf(activePlaytime / MILLIS_PER_HOUR)));
            }

            // SPEC 17.1 case 7: repeated create-then-disband is throttled.
            if (player.lastCityDisband() > 0 && now - player.lastCityDisband() < disbandCooldown) {
                return Result.<City>failure("DISBAND_COOLDOWN", "city.create.disband-cooldown",
                        Map.of("hours", hoursRemaining(
                                disbandCooldown - (now - player.lastCityDisband()))));
            }

            // 3. Balance. Checked by the withdrawal itself, which reports how short they are.
            // 6. Chunk unclaimed.
            if (daos.claims().findAt(connection, place.world(), place.chunkX(), place.chunkZ())
                    .isPresent()) {
                return Result.<City>failure("CHUNK_CLAIMED", "city.create.chunk-claimed");
            }

            // 7. Far enough from any other city.
            if (minDistance > 0 && daos.claims().existsWithin(connection, place.world(),
                    place.chunkX(), place.chunkZ(), minDistance, null)) {
                return Result.<City>failure("TOO_CLOSE", "city.create.too-close",
                        Map.of("chunks", String.valueOf(minDistance)));
            }

            Result<BigDecimal> payment = funds.withdraw(connection, founder, cost,
                    TransactionType.CITY_CREATE_FEE, null, null);
            if (payment instanceof Result.Failure<BigDecimal> failure) {
                return Result.<City>propagate(failure);
            }

            String tag = uniqueTag(connection, rawName);
            CityRow row = new CityRow(0, rawName, rawName, tag, founder, now,
                    dev.civitas.storage.SqlDialect.zero(),
                    place.world(), place.chunkX(), place.chunkZ(),
                    place.x(), place.y(), place.z(), place.yaw(), place.pitch(),
                    false, "", 0L, null, 0L, false, null);

            int cityId;
            try {
                cityId = daos.cities().insert(connection, row);
            } catch (SQLException e) {
                // SPEC 17.1 case 6: two founders raced for the same name and this one lost.
                // The unique index is the arbiter; surface it as a clean refusal, never SQL.
                if (isUniqueViolation(e)) {
                    return Result.<City>failure("NAME_TAKEN", "city.create.name-taken",
                            Map.of("name", rawName));
                }
                throw e;
            }

            City city = City.fromRow(new CityRow(cityId, row.name(), row.displayName(), row.tag(),
                    row.mayorUuid(), row.foundedAt(), row.treasury(), row.coreWorld(),
                    row.coreChunkX(), row.coreChunkZ(), row.spawnX(), row.spawnY(), row.spawnZ(),
                    row.spawnYaw(), row.spawnPitch(), row.openJoin(), row.motd(), row.upkeepDue(),
                    row.delinquentSince(), row.warProtectionUntil(), row.frozen(), row.deletedAt()));

            List<CityRank> ranks = createDefaultRanks(connection, cityId);
            ranks.forEach(city::putRank);

            CityRank mayorRank = ranks.stream()
                    .max((a, b) -> Integer.compare(a.weight(), b.weight()))
                    .orElseThrow(() -> new IllegalStateException(
                            "cities.yml defines no ranks; a city cannot be founded without one"));

            CityMemberRow memberRow =
                    new CityMemberRow(founder, cityId, mayorRank.id(), now,
                            dev.civitas.storage.SqlDialect.zero());
            daos.cityMembers().insert(connection, memberRow);
            city.putMember(CityMember.fromRow(memberRow));

            daos.players().updateCity(connection, founder, cityId, mayorRank.id());

            // The core chunk, free. Exempt from cost, adjacency and contiguity because it is
            // the seed the other three are measured from (SPEC 6.1).
            Result<Claim> core = claims.writeClaim(connection, founder, city, place.world(),
                    place.chunkX(), place.chunkZ(), dev.civitas.storage.SqlDialect.zero(),
                    ClaimType.CORE, null);
            if (core instanceof Result.Failure<Claim> failure) {
                return Result.<City>propagate(failure);
            }
            coreClaim.set(core.orElseThrow());

            return Result.success(city);
        }).thenApply(result -> applyOnMain(result, city -> {
            registry.register(city);
            Claim core = coreClaim.get();
            if (core != null) {
                claims.register(core);
            }
        }));
    }

    /**
     * Picks a tag that is not already taken.
     *
     * <p>{@code cities.tag} is unique, so two cities called "Roma" and "Roman" would collide
     * on a naive four-letter derivation and the second founder would see a raw constraint
     * error. A numeric suffix is appended until one is free, and if none is, the tag is left
     * null rather than failing the whole founding over a cosmetic prefix.
     */
    private String uniqueTag(Connection connection, String name) throws SQLException {
        String base = CityNameValidator.deriveTag(name);
        if (daos.cities().findByTag(connection, base).isEmpty()) {
            return base;
        }
        for (int suffix = 2; suffix <= 99; suffix++) {
            String candidate = base.substring(0, Math.min(base.length(), 5 - String.valueOf(suffix).length()))
                    + suffix;
            if (daos.cities().findByTag(connection, candidate).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    /** Creates the SPEC 5.4 default ranks from {@code cities.yml}. */
    private List<CityRank> createDefaultRanks(Connection connection, int cityId) throws SQLException {
        ConfigurationSection section = configs.get(ConfigFile.CITIES).getConfigurationSection("ranks");
        if (section == null) {
            throw new IllegalStateException("cities.yml has no 'ranks' section");
        }

        List<CityRank> created = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection rank = section.getConfigurationSection(key);
            if (rank == null) {
                continue;
            }
            PermissionSet permissions = parsePermissions(rank.getStringList("permissions"));
            CityRankRow row = new CityRankRow(0, cityId,
                    rank.getString("name", key),
                    rank.getInt("weight", 0),
                    permissions.bits(),
                    rank.getBoolean("default", false));

            int id = daos.cityRanks().insert(connection, row);
            created.add(CityRank.fromRow(new CityRankRow(id, cityId, row.name(), row.weight(),
                    row.permissions(), row.isDefault())));
        }
        return created;
    }

    /**
     * Reads a configured permission list.
     *
     * <p>Supports {@code [ALL]}, {@code [ALL_EXCEPT, X, Y]} and an explicit list of flags.
     * An unrecognised flag name is ignored rather than throwing, so a typo in the config
     * costs one permission rather than preventing every city from being founded.
     */
    static PermissionSet parsePermissions(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return PermissionSet.NONE;
        }
        String first = entries.get(0).trim().toUpperCase(Locale.ROOT);

        if ("ALL".equals(first)) {
            return PermissionSet.ALL;
        }
        if ("ALL_EXCEPT".equals(first)) {
            PermissionSet set = PermissionSet.ALL;
            for (String entry : entries.subList(1, entries.size())) {
                Optional<CityPermission> permission = CityPermission.parse(entry);
                if (permission.isPresent()) {
                    set = set.without(permission.get());
                }
            }
            return set;
        }

        PermissionSet set = PermissionSet.NONE;
        for (String entry : entries) {
            Optional<CityPermission> permission = CityPermission.parse(entry);
            if (permission.isPresent()) {
                set = set.with(permission.get());
            }
        }
        return set;
    }

    // ==================================================================================
    // Disband, SPEC 5.3 and SPEC 17.1 case 10
    // ==================================================================================

    /**
     * Soft-deletes a city, releases its land and splits what is left of the treasury.
     *
     * <p>Soft, not hard: SPEC 5.3 gives admins a 14-day restore window, so the row stays and
     * only the things that would otherwise keep working, the claims and the memberships,
     * are removed.
     */
    public CompletableFuture<Result<City>> disband(UUID actor, City city) {
        long now = System.currentTimeMillis();

        Result<Void> guard = requirePermission(city, actor, CityPermission.DISBAND);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }
        if (!events.fire(new CityDisbandEvent(city, actor))) {
            return completed(Result.failure("CANCELLED", "city.disband.cancelled"));
        }

        List<UUID> memberUuids = city.members().stream().map(CityMember::uuid).toList();
        BigDecimal treasury = city.treasury();

        // SPEC 5.3: half of what was paid for the land comes back, to the mayor personally.
        // Note the asymmetry with SPEC 6.4, where an ordinary unclaim refunds to the
        // treasury: there is no treasury left to refund into once the city is gone.
        BigDecimal landRefund = claims.registry().claimsOf(city.id()).stream()
                .map(claim -> claims.costs().refundFor(claim.costPaid()))
                .reduce(dev.civitas.storage.SqlDialect.zero(), BigDecimal::add);
        UUID mayor = city.mayorUuid();

        return db.transaction(connection -> {
            // SPEC 17.1 case 10: whatever is in the treasury is split evenly among members.
            if (treasury.signum() > 0 && !memberUuids.isEmpty()) {
                BigDecimal share = treasury.divide(BigDecimal.valueOf(memberUuids.size()),
                        dev.civitas.storage.SqlDialect.MONEY_SCALE, java.math.RoundingMode.DOWN);
                if (share.signum() > 0) {
                    for (UUID member : memberUuids) {
                        funds.deposit(connection, member, share, TransactionType.TREASURY_WITHDRAW,
                                city.id(), "{\"reason\":\"disband\"}");
                    }
                }
            }

            if (landRefund.signum() > 0) {
                funds.deposit(connection, mayor, landRefund,
                        TransactionType.CHUNK_UNCLAIM_REFUND, city.id(),
                        "{\"reason\":\"disband\"}");
            }

            daos.cities().updateTreasury(connection, city.id(),
                    dev.civitas.storage.SqlDialect.zero());
            daos.claims().deleteByCity(connection, city.id());
            daos.cityMembers().deleteByCity(connection, city.id());
            daos.players().clearCity(connection, city.id());
            daos.cityInvites().deleteByCity(connection, city.id());

            for (UUID member : memberUuids) {
                daos.players().updateLastCityLeave(connection, member, now);
            }
            daos.players().updateLastCityDisband(connection, actor, now);

            daos.cities().softDelete(connection, city.id(), now);
            return Result.success(city);
        }).thenApply(result -> applyOnMain(result, disbanded -> {
            disbanded.setDeletedAt(now);
            disbanded.setTreasury(dev.civitas.storage.SqlDialect.zero());
            claims.forgetCity(disbanded.id());
            registry.unregister(disbanded);
        }));
    }

    // ==================================================================================
    // Joining, SPEC 5.2
    // ==================================================================================

    /** Creates or refreshes an invite. */
    public CompletableFuture<Result<Void>> invite(UUID actor, City city, UUID invitee) {
        Result<Void> guard = requirePermission(city, actor, CityPermission.INVITE);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }
        if (city.isMember(invitee)) {
            return completed(Result.failure("ALREADY_MEMBER", "city.invite.already-member"));
        }
        if (city.isBanned(invitee)) {
            return completed(Result.failure("BANNED", "city.join.banned"));
        }

        long expiresAt = System.currentTimeMillis()
                + configs.get(ConfigFile.CITIES).getLong("members.invite-expiry-minutes", 5) * 60_000L;

        return daos.cityInvites()
                .upsert(new CityInviteRow(city.id(), invitee, actor, expiresAt))
                .thenApply(ignored -> Result.ok());
    }

    /** Accepts a pending invite. */
    public CompletableFuture<Result<City>> acceptInvite(UUID player, City city) {
        long now = System.currentTimeMillis();
        if (!events.fire(new CityJoinEvent(city, player, CityJoinEvent.Method.INVITE))) {
            return completed(Result.failure("CANCELLED", "city.join.cancelled"));
        }

        return db.transaction(connection -> {
            if (daos.cityInvites().findPending(connection, city.id(), player, now).isEmpty()) {
                return Result.<CityMemberRow>failure("NO_INVITE", "city.join.no-invite");
            }
            return joinInternal(connection, player, city, now, true);
        }).thenApply(result -> applyMembership(result, city, player));
    }

    /** Declines a pending invite. */
    public CompletableFuture<Result<Void>> denyInvite(UUID player, City city) {
        return daos.cityInvites().delete(city.id(), player)
                .thenApply(removed -> removed > 0
                        ? Result.ok()
                        : Result.failure("NO_INVITE", "city.join.no-invite"));
    }

    /** Joins an open-join city with no invite, SPEC 5.2. */
    public CompletableFuture<Result<City>> joinOpen(UUID player, City city) {
        long now = System.currentTimeMillis();
        if (!city.isOpenJoin()) {
            return completed(Result.failure("NOT_OPEN", "city.join.not-open"));
        }
        if (!events.fire(new CityJoinEvent(city, player, CityJoinEvent.Method.OPEN_JOIN))) {
            return completed(Result.failure("CANCELLED", "city.join.cancelled"));
        }

        return db.transaction(connection -> joinInternal(connection, player, city, now, false))
                .thenApply(result -> applyMembership(result, city, player));
    }

    /**
     * The shared join path. Database only; the cache is updated by the caller once the
     * transaction has committed, so a rolled-back join cannot leave a phantom member behind.
     *
     * @param invited whether the player is arriving on an invite, which exempts them from
     *                the SPEC 5.2 city-switch cooldown when it is the city they just left
     */
    private Result<CityMemberRow> joinInternal(Connection connection, UUID player, City city,
                                               long now, boolean invited) throws SQLException {
        if (city.isFrozen()) {
            return Result.failure("CITY_FROZEN", "city.frozen");
        }
        if (city.isMember(player)) {
            return Result.failure("ALREADY_MEMBER", "city.invite.already-member");
        }
        if (city.isBanned(player)) {
            return Result.failure("BANNED", "city.join.banned");
        }
        if (registry.isInAnyCity(player)) {
            return Result.failure("ALREADY_IN_CITY", "city.join.already-in-city");
        }

        int cap = memberCap(city);
        if (city.memberCount() >= cap) {
            return Result.failure("CITY_FULL", "city.join.full", Map.of("cap", String.valueOf(cap)));
        }

        Optional<PlayerRow> playerRow = daos.players().findByUuid(connection, player);
        if (playerRow.isEmpty()) {
            return Result.failure("NO_PLAYER_RECORD", "economy.no-account");
        }

        // SPEC 5.2: leaving a city locks you out of a *different* one for 24 hours. An
        // invite is the signal that the city wants this player back, which is the case the
        // specification exempts; an open-join walk-in is exactly the mercenary hop it does not.
        long cooldown = configs.get(ConfigFile.CITIES)
                .getLong("members.switch-cooldown-hours", 24) * MILLIS_PER_HOUR;
        long lastLeave = playerRow.get().lastCityLeave();
        if (!invited && lastLeave > 0 && now - lastLeave < cooldown) {
            return Result.failure("SWITCH_COOLDOWN", "city.join.cooldown",
                    Map.of("hours", hoursRemaining(cooldown - (now - lastLeave))));
        }

        CityRank rank = city.defaultRank()
                .or(() -> city.ranks().stream().min((a, b) -> Integer.compare(a.weight(), b.weight())))
                .orElse(null);
        if (rank == null) {
            return Result.failure("NO_DEFAULT_RANK", "city.join.no-rank");
        }

        CityMemberRow memberRow = new CityMemberRow(player, city.id(), rank.id(), now,
                dev.civitas.storage.SqlDialect.zero());
        daos.cityMembers().insert(connection, memberRow);
        daos.players().updateCity(connection, player, city.id(), rank.id());
        daos.cityInvites().delete(connection, city.id(), player);

        return Result.success(memberRow);
    }

    /** Adds the committed member to the cache, on the server thread. */
    private Result<City> applyMembership(Result<CityMemberRow> result, City city, UUID player) {
        if (result instanceof Result.Failure<CityMemberRow> failure) {
            return Result.propagate(failure);
        }
        CityMemberRow row = result.orElseThrow();
        scheduler.runOnMain(() -> {
            city.putMember(CityMember.fromRow(row));
            registry.indexMember(player, city.id());
        });
        return Result.success(city);
    }

    // ==================================================================================
    // Leaving and kicking, SPEC 5.3
    // ==================================================================================

    /** Leaves a city. The mayor cannot; they must transfer or disband (SPEC 17.1 case 4). */
    public CompletableFuture<Result<City>> leave(UUID player, City city) {
        long now = System.currentTimeMillis();

        if (!city.isMember(player)) {
            return completed(Result.failure("NOT_A_MEMBER", "city.not-a-member"));
        }
        if (city.isMayor(player)) {
            return completed(Result.failure("MAYOR_CANNOT_LEAVE", "city.leave.mayor"));
        }
        if (!events.fire(new CityLeaveEvent(city, player))) {
            return completed(Result.failure("CANCELLED", "city.leave.cancelled"));
        }

        return db.transaction(connection -> {
            daos.cityMembers().delete(connection, player);
            daos.players().updateCity(connection, player, null, null);
            daos.players().updateLastCityLeave(connection, player, now);
            return Result.success(city);
        }).thenApply(result -> applyOnMain(result, left -> {
            left.removeMember(player);
            registry.forgetMember(player);
        }));
    }

    /** Removes a member, SPEC 5.3. Requires KICK and outranking the target. */
    public CompletableFuture<Result<City>> kick(UUID actor, City city, UUID target) {
        long now = System.currentTimeMillis();

        Result<Void> guard = requirePermission(city, actor, CityPermission.KICK);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        if (!city.isMember(target)) {
            return completed(Result.failure("NOT_A_MEMBER", "city.not-a-member"));
        }
        if (actor.equals(target)) {
            return completed(Result.failure("CANNOT_KICK_SELF", "city.kick.self"));
        }
        if (city.isMayor(target)) {
            return completed(Result.failure("CANNOT_KICK_MAYOR", "city.kick.mayor"));
        }
        if (city.weightOf(actor) <= city.weightOf(target)) {
            return completed(Result.failure("OUTRANKED", "city.kick.outranked"));
        }
        if (!events.fire(new CityKickEvent(city, actor, target))) {
            return completed(Result.failure("CANCELLED", "city.kick.cancelled"));
        }

        return db.transaction(connection -> {
            daos.cityMembers().delete(connection, target);
            daos.players().updateCity(connection, target, null, null);
            daos.players().updateLastCityLeave(connection, target, now);
            return Result.success(city);
        }).thenApply(result -> applyOnMain(result, kicked -> {
            kicked.removeMember(target);
            registry.forgetMember(target);
        }));
    }

    // ==================================================================================
    // Mayorship transfer, SPEC 5.3
    // ==================================================================================

    /**
     * Offers mayorship to another member.
     *
     * <p>SPEC 5.3 requires the target to be online and to accept within 60 seconds, so this
     * only records an offer; {@link #acceptTransfer} completes it. That is also SPEC 17.1
     * case 9: an offline player cannot accept, so mayorship cannot be dumped on them.
     */
    public Result<Void> offerTransfer(UUID actor, City city, UUID target, boolean targetOnline) {
        if (!city.isMayor(actor)) {
            return Result.failure("NOT_MAYOR", "city.transfer.not-mayor");
        }
        if (city.isFrozen()) {
            return Result.failure("CITY_FROZEN", "city.frozen");
        }
        if (!city.isMember(target)) {
            return Result.failure("NOT_A_MEMBER", "city.not-a-member");
        }
        if (actor.equals(target)) {
            return Result.failure("CANNOT_TRANSFER_SELF", "city.transfer.self");
        }
        if (!targetOnline) {
            return Result.failure("TARGET_OFFLINE", "city.transfer.offline");
        }

        long seconds = configs.get(ConfigFile.CITIES).getLong("members.transfer-accept-seconds", 60);
        pendingTransfers.put(target,
                new PendingTransfer(city.id(), actor, System.currentTimeMillis() + seconds * 1000L));
        return Result.ok();
    }

    /** Completes a pending transfer. */
    public CompletableFuture<Result<City>> acceptTransfer(UUID target) {
        PendingTransfer pending = pendingTransfers.remove(target);
        long now = System.currentTimeMillis();

        if (pending == null || pending.expiresAt() < now) {
            return completed(Result.failure("NO_TRANSFER_OFFER", "city.transfer.none"));
        }

        Optional<City> maybeCity = registry.city(pending.cityId());
        if (maybeCity.isEmpty()) {
            return completed(Result.failure("CITY_GONE", "city.unknown"));
        }
        City city = maybeCity.get();

        if (!city.isMayor(pending.from()) || !city.isMember(target)) {
            return completed(Result.failure("NO_TRANSFER_OFFER", "city.transfer.none"));
        }
        if (!events.fire(new CityTransferEvent(city, pending.from(), target))) {
            return completed(Result.failure("CANCELLED", "city.transfer.cancelled"));
        }

        CityRank mayorRank = city.mayorRank().orElse(null);
        if (mayorRank == null) {
            return completed(Result.failure("NO_MAYOR_RANK", "city.transfer.no-rank"));
        }
        CityRank secondRank = city.ranks().stream()
                .filter(rank -> rank.id() != mayorRank.id())
                .max((a, b) -> Integer.compare(a.weight(), b.weight()))
                .orElse(mayorRank);

        UUID previousMayor = pending.from();
        return db.transaction(connection -> {
            CityRow updated = city.toRow();
            daos.cities().update(connection, new CityRow(updated.id(), updated.name(),
                    updated.displayName(), updated.tag(), target, updated.foundedAt(),
                    updated.treasury(), updated.coreWorld(), updated.coreChunkX(),
                    updated.coreChunkZ(), updated.spawnX(), updated.spawnY(), updated.spawnZ(),
                    updated.spawnYaw(), updated.spawnPitch(), updated.openJoin(), updated.motd(),
                    updated.upkeepDue(), updated.delinquentSince(), updated.warProtectionUntil(),
                    updated.frozen(), updated.deletedAt()));

            daos.cityMembers().updateRank(connection, target, mayorRank.id());
            daos.players().updateCity(connection, target, city.id(), mayorRank.id());

            // The outgoing mayor keeps a seat rather than being cast out: SPEC 17.1 case 1
            // demotes an auto-transferred mayor to the next rank down, and a voluntary
            // transfer should not be harsher than an involuntary one.
            daos.cityMembers().updateRank(connection, previousMayor, secondRank.id());
            daos.players().updateCity(connection, previousMayor, city.id(), secondRank.id());

            daos.auditLog().insert(connection, new dev.civitas.storage.row.AuditLogRow(0, now,
                    previousMayor, "CITY_TRANSFER", city.name(),
                    "transferred to " + target, null));

            return Result.success(city);
        }).thenApply(result -> applyOnMain(result, transferred -> {
            transferred.setMayorUuid(target);
            transferred.member(target).ifPresent(member -> member.setRankId(mayorRank.id()));
            transferred.member(previousMayor).ifPresent(member -> member.setRankId(secondRank.id()));
        }));
    }

    /** Discards a pending offer, for use when the offerer cancels or logs out. */
    public void cancelTransfer(UUID target) {
        pendingTransfers.remove(target);
    }

    public boolean hasPendingTransfer(UUID target) {
        PendingTransfer pending = pendingTransfers.get(target);
        return pending != null && pending.expiresAt() >= System.currentTimeMillis();
    }

    // ==================================================================================
    // Settings, SPEC 5.7 and 8.10
    // ==================================================================================

    /** Renames a city, charging the SPEC 4.3 fee to the treasury. */
    public CompletableFuture<Result<City>> rename(UUID actor, City city, String newName) {
        Result<Void> guard = requirePermission(city, actor, CityPermission.EDIT_SETTINGS);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }

        Result<String> nameCheck = nameValidator.validate(newName);
        if (nameCheck instanceof Result.Failure<String> failure) {
            return completed(Result.propagate(failure));
        }
        if (!newName.equalsIgnoreCase(city.name()) && registry.isNameTaken(newName)) {
            return completed(Result.failure("NAME_TAKEN", "city.create.name-taken",
                    Map.of("name", newName)));
        }

        BigDecimal cost = money(configs.get(ConfigFile.CITIES), "creation.rename-cost", "15000");
        String oldName = city.name();
        long now = System.currentTimeMillis();

        return db.transaction(connection -> {
            Optional<CityRow> current = daos.cities().findById(connection, city.id());
            if (current.isEmpty()) {
                return Result.<City>failure("CITY_GONE", "city.unknown");
            }
            BigDecimal treasury = current.get().treasury();
            if (treasury.compareTo(cost) < 0) {
                return Result.<City>failure("TREASURY_SHORT", "city.treasury.insufficient",
                        Map.of("required", cost.toPlainString(),
                                "balance", treasury.toPlainString()));
            }

            BigDecimal after = treasury.subtract(cost);
            daos.cities().updateTreasury(connection, city.id(), after);
            daos.ledger().insert(connection, new LedgerRow(0, now,
                    TransactionType.CITY_RENAME_FEE.name(), actor, null, city.id(),
                    cost.negate(), after, null));

            CityRow row = current.get();
            try {
                daos.cities().update(connection, new CityRow(row.id(), newName, row.displayName(),
                        row.tag(), row.mayorUuid(), row.foundedAt(), after, row.coreWorld(),
                        row.coreChunkX(), row.coreChunkZ(), row.spawnX(), row.spawnY(), row.spawnZ(),
                        row.spawnYaw(), row.spawnPitch(), row.openJoin(), row.motd(), row.upkeepDue(),
                        row.delinquentSince(), row.warProtectionUntil(), row.frozen(), row.deletedAt()));
            } catch (SQLException e) {
                if (isUniqueViolation(e)) {
                    return Result.<City>failure("NAME_TAKEN", "city.create.name-taken",
                            Map.of("name", newName));
                }
                throw e;
            }
            return Result.success(city);
        }).thenApply(result -> applyOnMain(result, renamed -> {
            renamed.setName(newName);
            renamed.setTreasury(renamed.treasury().subtract(cost));
            registry.reindexName(renamed, oldName);
        }));
    }

    public CompletableFuture<Result<City>> setMotd(UUID actor, City city, String motd) {
        return updateSettings(actor, city, row -> new CityRow(row.id(), row.name(),
                row.displayName(), row.tag(), row.mayorUuid(), row.foundedAt(), row.treasury(),
                row.coreWorld(), row.coreChunkX(), row.coreChunkZ(), row.spawnX(), row.spawnY(),
                row.spawnZ(), row.spawnYaw(), row.spawnPitch(), row.openJoin(), motd,
                row.upkeepDue(), row.delinquentSince(), row.warProtectionUntil(), row.frozen(),
                row.deletedAt()), updated -> updated.setMotd(motd));
    }

    public CompletableFuture<Result<City>> setOpenJoin(UUID actor, City city, boolean openJoin) {
        return updateSettings(actor, city, row -> new CityRow(row.id(), row.name(),
                row.displayName(), row.tag(), row.mayorUuid(), row.foundedAt(), row.treasury(),
                row.coreWorld(), row.coreChunkX(), row.coreChunkZ(), row.spawnX(), row.spawnY(),
                row.spawnZ(), row.spawnYaw(), row.spawnPitch(), openJoin, row.motd(),
                row.upkeepDue(), row.delinquentSince(), row.warProtectionUntil(), row.frozen(),
                row.deletedAt()), updated -> updated.setOpenJoin(openJoin));
    }

    /**
     * Moves the city spawn, SPEC 5.6 and SPEC 8.10 slot 16.
     *
     * <p>Gated on {@code SET_SPAWN} rather than {@code EDIT_SETTINGS}, because SPEC 5.4 gives
     * the Architect rank the first and not the second: moving where everyone arrives is a
     * building decision, not an administrative one.
     *
     * <p>The position must be inside a claim of this city. SPEC 5.6 requires it, and the
     * alternative is a spawn that the SPEC 17.2 case 22 sweep would immediately move back.
     */
    public CompletableFuture<Result<City>> setSpawn(UUID actor, City city, String world,
                                                    double x, double y, double z,
                                                    float yaw, float pitch) {
        Result<Void> guard = requirePermission(city, actor, CityPermission.SET_SPAWN);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }

        if (claims.registry().atBlock(world, (int) Math.floor(x), (int) Math.floor(z))
                .filter(claim -> claim.cityId() == city.id())
                .isEmpty()) {
            return completed(Result.failure("SPAWN_OUTSIDE_CLAIMS", "city.spawn.outside-claims"));
        }

        return db.transaction(connection -> {
            Optional<CityRow> current = daos.cities().findById(connection, city.id());
            if (current.isEmpty()) {
                return Result.<City>failure("CITY_GONE", "city.unknown");
            }
            CityRow row = current.get();
            daos.cities().update(connection, new CityRow(row.id(), row.name(), row.displayName(),
                    row.tag(), row.mayorUuid(), row.foundedAt(), row.treasury(), row.coreWorld(),
                    row.coreChunkX(), row.coreChunkZ(), x, y, z, yaw, pitch, row.openJoin(),
                    row.motd(), row.upkeepDue(), row.delinquentSince(), row.warProtectionUntil(),
                    row.frozen(), row.deletedAt()));
            return Result.success(city);
        }).thenApply(result -> applyOnMain(result,
                updated -> updated.setSpawn(x, y, z, yaw, pitch)));
    }

    private CompletableFuture<Result<City>> updateSettings(
            UUID actor, City city,
            java.util.function.UnaryOperator<CityRow> change,
            java.util.function.Consumer<City> applyToCache) {

        Result<Void> guard = requirePermission(city, actor, CityPermission.EDIT_SETTINGS);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }

        return db.transaction(connection -> {
            Optional<CityRow> current = daos.cities().findById(connection, city.id());
            if (current.isEmpty()) {
                return Result.<City>failure("CITY_GONE", "city.unknown");
            }
            daos.cities().update(connection, change.apply(current.get()));
            return Result.success(city);
        }).thenApply(result -> applyOnMain(result, applyToCache));
    }

    // ==================================================================================
    // Bans, SPEC 5.2 and 8.6
    // ==================================================================================

    public CompletableFuture<Result<Void>> ban(UUID actor, City city, UUID target, String reason) {
        Result<Void> guard = requirePermission(city, actor, CityPermission.KICK);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        if (city.isMayor(target)) {
            return completed(Result.failure("CANNOT_BAN_MAYOR", "city.ban.mayor"));
        }
        if (city.isMember(target) && city.weightOf(actor) <= city.weightOf(target)) {
            return completed(Result.failure("OUTRANKED", "city.kick.outranked"));
        }

        long now = System.currentTimeMillis();
        return db.transaction(connection -> {
            daos.cityBans().upsert(connection,
                    new CityBanRow(city.id(), target, actor, reason, now));
            // Banning a current member removes them, or the ban would have no effect.
            if (city.isMember(target)) {
                daos.cityMembers().delete(connection, target);
                daos.players().updateCity(connection, target, null, null);
                daos.players().updateLastCityLeave(connection, target, now);
            }
            return Result.ok();
        }).thenApply(result -> {
            if (result.isSuccess()) {
                scheduler.runOnMain(() -> {
                    city.putBan(target, reason);
                    if (city.isMember(target)) {
                        city.removeMember(target);
                        registry.forgetMember(target);
                    }
                });
            }
            return result;
        });
    }

    public CompletableFuture<Result<Void>> unban(UUID actor, City city, UUID target) {
        Result<Void> guard = requirePermission(city, actor, CityPermission.KICK);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        return daos.cityBans().delete(city.id(), target).thenApply(removed -> {
            if (removed == 0) {
                return Result.<Void>failure("NOT_BANNED", "city.ban.not-banned");
            }
            scheduler.runOnMain(() -> city.removeBan(target));
            return Result.ok();
        });
    }

    // ==================================================================================
    // Shared helpers
    // ==================================================================================

    /** The member cap, base plus whatever the Population upgrade has bought (SPEC 5.7). */
    public int memberCap(City city) {
        FileConfiguration cities = configs.get(ConfigFile.CITIES);
        int base = cities.getInt("members.base-cap", 10);
        if (upgrades == null) {
            return base;
        }
        int perLevel = (int) Math.round(upgrades.effectPerLevel(
                dev.civitas.core.upgrade.UpgradeType.POPULATION, 5));
        return base + perLevel * upgrades.levelOf(city,
                dev.civitas.core.upgrade.UpgradeType.POPULATION);
    }

    /**
     * Told about upgrades once they exist, so SPEC 5.7's Population track raises the cap.
     *
     * <p>Set rather than injected because a city has to be creatable before upgrades are
     * loaded, and a city with no upgrades sits at the base cap either way.
     */
    public void useUpgrades(dev.civitas.core.upgrade.UpgradeService service) {
        this.upgrades = service;
    }

    private dev.civitas.core.upgrade.UpgradeService upgrades;


    /** Checks a city permission, treating the mayor as holding everything. */
    public Result<Void> requirePermission(City city, UUID actor, CityPermission permission) {
        if (!city.isMember(actor)) {
            return Result.failure("NOT_A_MEMBER", "city.not-a-member");
        }
        if (!city.hasPermission(actor, permission)) {
            return Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", permission.name()));
        }
        return Result.ok();
    }

    private Result<Void> checkWorld(String world) {
        FileConfiguration config = configs.get(ConfigFile.CONFIG);
        List<String> blacklisted = config.getStringList("worlds.blacklisted");
        if (blacklisted.stream().anyMatch(name -> name.equalsIgnoreCase(world))) {
            return Result.failure("WORLD_BLACKLISTED", "city.create.world-blocked");
        }
        List<String> enabled = config.getStringList("worlds.city-enabled");
        if (enabled.stream().noneMatch(name -> name.equalsIgnoreCase(world))) {
            return Result.failure("WORLD_NOT_ENABLED", "city.create.world-disabled");
        }
        return Result.ok();
    }

    /**
     * Runs the cache update on the server thread, then hands the result back unchanged.
     *
     * <p>Cache mutation has to be on the main thread because everything that reads it, chat,
     * block events, GUI clicks, runs there.
     */
    private <T> Result<T> applyOnMain(Result<T> result, java.util.function.Consumer<T> apply) {
        if (result instanceof Result.Success<T>(T value) && value != null) {
            scheduler.runOnMain(() -> apply.accept(value));
        }
        return result;
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /**
     * Rounds a remaining duration up to whole hours.
     *
     * <p>Up, not down: telling a player to come back in 23 hours when 23 hours 59 minutes
     * remain sends them away and then refuses them again.
     */
    static String hoursRemaining(long millis) {
        long hours = (millis + MILLIS_PER_HOUR - 1) / MILLIS_PER_HOUR;
        return String.valueOf(Math.max(1L, hours));
    }

    private static BigDecimal money(FileConfiguration config, String path, String fallback) {
        return new BigDecimal(config.getString(path, fallback));
    }

    /**
     * Whether a failure is a unique-constraint violation.
     *
     * <p>SQLState class 23 is "integrity constraint violation" in the SQL standard and both
     * backends report it, which is more portable than matching driver-specific messages.
     */
    static boolean isUniqueViolation(SQLException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("23")) {
                    return true;
                }
                // SQLite reports constraint failures through its own result codes rather
                // than a SQLState, so fall back to the documented message prefix.
                String message = sql.getMessage();
                if (message != null && message.contains("UNIQUE constraint failed")) {
                    return true;
                }
            }
        }
        return false;
    }
}
