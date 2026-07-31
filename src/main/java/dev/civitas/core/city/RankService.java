package dev.civitas.core.city;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.api.event.CityRankChangeEvent;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.CityRankRow;
import dev.civitas.util.EventBus;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;

/**
 * Rank management, SPEC 5.4 and SPEC 8.7.
 *
 * <p>Two rules run through everything here and are the reason this is a service rather than
 * a handful of DAO calls:
 *
 * <ul>
 *   <li><b>You cannot grant what you do not hold.</b> Otherwise a Co-Mayor could invent a
 *       rank with DISBAND and hand it to themselves.</li>
 *   <li><b>You cannot edit a rank at or above your own weight.</b> Equal is not enough:
 *       two members of the same rank editing each other is a demotion war, and a member
 *       editing their own rank is a self-promotion.</li>
 * </ul>
 *
 * <p>Both are checked against the actor's <em>effective</em> permissions, so the mayor is
 * exempt from the first (they hold everything) and from the second (nothing reaches their
 * weight), which is what makes a city recoverable from any rank configuration.
 */
public final class RankService {

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final Scheduler scheduler;
    private final EventBus events;

    public RankService(DatabaseManager db, DaoRegistry daos, Scheduler scheduler, EventBus events) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.events = Objects.requireNonNull(events, "events");
    }

    // ==================================================================================
    // The two invariants
    // ==================================================================================

    /**
     * Whether {@code actor} may modify {@code rank}.
     *
     * @return a failure naming the rule broken, or success
     */
    public Result<Void> canEdit(City city, UUID actor, CityRank rank) {
        if (!city.hasPermission(actor, CityPermission.MANAGE_RANKS)) {
            return Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.MANAGE_RANKS.name()));
        }
        if (city.weightOf(actor) <= rank.weight()) {
            return Result.failure("RANK_OUTRANKS_ACTOR", "city.rank.outranked",
                    Map.of("rank", rank.name(), "weight", String.valueOf(rank.weight())));
        }
        return Result.ok();
    }

    /**
     * Whether {@code actor} may put exactly {@code requested} on a rank.
     *
     * @return a failure listing the flags the actor lacks, or success
     */
    public Result<Void> canGrant(City city, UUID actor, PermissionSet requested) {
        PermissionSet held = city.permissionsOf(actor);
        PermissionSet missing = held.missingFrom(requested);
        if (!missing.isEmpty()) {
            return Result.failure("CANNOT_GRANT_UNHELD", "city.rank.cannot-grant",
                    Map.of("permissions", missing.toSet().stream()
                            .map(Enum::name)
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("")));
        }
        return Result.ok();
    }

    /**
     * A weight a rank may be created or moved to: strictly below the actor's own, and never
     * at or above the mayor's.
     *
     * <p>The reserved-weight rule is checked first because it holds for everyone including
     * the mayor, whose own weight would otherwise make the message read "at most 99, below
     * your own rank" when the real reason is that 100 belongs to the mayor alone.
     */
    public Result<Void> canUseWeight(City city, UUID actor, int weight) {
        if (weight >= CityRank.MAYOR_WEIGHT) {
            return Result.failure("WEIGHT_RESERVED", "city.rank.weight-reserved",
                    Map.of("max", String.valueOf(CityRank.MAYOR_WEIGHT - 1)));
        }
        if (weight < 0) {
            return Result.failure("WEIGHT_NEGATIVE", "city.rank.weight-negative");
        }
        if (weight >= city.weightOf(actor)) {
            return Result.failure("WEIGHT_TOO_HIGH", "city.rank.weight-too-high",
                    Map.of("max", String.valueOf(city.weightOf(actor) - 1)));
        }
        return Result.ok();
    }

    // ==================================================================================
    // Rank lifecycle
    // ==================================================================================

    /** Creates a rank with no permissions, which the actor then grants one at a time. */
    public CompletableFuture<Result<CityRank>> create(UUID actor, City city, String name, int weight) {
        if (!city.hasPermission(actor, CityPermission.MANAGE_RANKS)) {
            return completed(Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.MANAGE_RANKS.name())));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }
        if (name == null || name.isBlank() || name.length() > 16) {
            return completed(Result.failure("RANK_NAME_INVALID", "city.rank.name-invalid"));
        }
        if (city.rankByName(name).isPresent()) {
            return completed(Result.failure("RANK_EXISTS", "city.rank.exists",
                    Map.of("rank", name)));
        }

        Result<Void> weightCheck = canUseWeight(city, actor, weight);
        if (weightCheck instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }

        CityRankRow row = new CityRankRow(0, city.id(), name, weight, 0L, false);
        return db.call(connection -> daos.cityRanks().insert(connection, row))
                .thenApply(id -> {
                    CityRank rank = CityRank.fromRow(
                            new CityRankRow(id, city.id(), name, weight, 0L, false));
                    scheduler.runOnMain(() -> city.putRank(rank));
                    return Result.success(rank);
                });
    }

    /**
     * Deletes a rank, moving anyone holding it onto the default rank.
     *
     * <p>Members are moved rather than removed from the city: deleting a rank is an
     * administrative tidy-up, not a mass kick.
     */
    public CompletableFuture<Result<CityRank>> delete(UUID actor, City city, CityRank rank) {
        // Checked before the weight rule so the refusal says why the mayor rank is special,
        // rather than "its weight is not below yours", which is true of the mayor's own rank
        // for the mayor themselves and reads as a bug.
        if (rank.isMayorRank()) {
            return completed(Result.failure("CANNOT_DELETE_MAYOR_RANK", "city.rank.cannot-delete-mayor"));
        }
        Result<Void> guard = canEdit(city, actor, rank);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }
        if (city.ranks().size() <= 1) {
            return completed(Result.failure("LAST_RANK", "city.rank.last"));
        }

        CityRank fallback = city.defaultRank()
                .filter(candidate -> candidate.id() != rank.id())
                .or(() -> city.ranks().stream()
                        .filter(candidate -> candidate.id() != rank.id())
                        .min(Comparator.comparingInt(CityRank::weight)))
                .orElse(null);
        if (fallback == null) {
            return completed(Result.failure("NO_FALLBACK_RANK", "city.rank.no-fallback"));
        }

        List<UUID> affected = city.members().stream()
                .filter(member -> member.rankId() == rank.id())
                .map(CityMember::uuid)
                .toList();

        return db.transaction(connection -> {
            for (UUID member : affected) {
                daos.cityMembers().updateRank(connection, member, fallback.id());
                daos.players().updateCity(connection, member, city.id(), fallback.id());
            }
            daos.cityRanks().delete(connection, rank.id());
            return Result.success(rank);
        }).thenApply(result -> {
            if (result.isSuccess()) {
                scheduler.runOnMain(() -> {
                    affected.forEach(member ->
                            city.member(member).ifPresent(m -> m.setRankId(fallback.id())));
                    city.removeRank(rank.id());
                });
            }
            return result;
        });
    }

    /** Renames a rank. */
    public CompletableFuture<Result<CityRank>> rename(UUID actor, City city, CityRank rank,
                                                      String newName) {
        Result<Void> guard = canEdit(city, actor, rank);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        if (newName == null || newName.isBlank() || newName.length() > 16) {
            return completed(Result.failure("RANK_NAME_INVALID", "city.rank.name-invalid"));
        }
        Optional<CityRank> clash = city.rankByName(newName);
        if (clash.isPresent() && clash.get().id() != rank.id()) {
            return completed(Result.failure("RANK_EXISTS", "city.rank.exists",
                    Map.of("rank", newName)));
        }

        CityRankRow row = new CityRankRow(rank.id(), city.id(), newName, rank.weight(),
                rank.permissions().bits(), rank.isDefault());
        return db.call(connection -> daos.cityRanks().update(connection, row))
                .thenApply(ignored -> {
                    scheduler.runOnMain(() -> rank.setName(newName));
                    return Result.success(rank);
                });
    }

    /**
     * Grants or revokes one flag.
     *
     * <p>Only one flag at a time by design: SPEC 8.7's permission editor is a grid of
     * toggles, and a per-flag operation is what lets the "cannot grant what you lack" check
     * name the exact flag it refused.
     */
    public CompletableFuture<Result<CityRank>> setPermission(UUID actor, City city, CityRank rank,
                                                             CityPermission permission,
                                                             boolean granted) {
        Result<Void> guard = canEdit(city, actor, rank);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }
        if (granted) {
            Result<Void> grantCheck = canGrant(city, actor, PermissionSet.of(permission));
            if (grantCheck instanceof Result.Failure<Void> failure) {
                return completed(Result.propagate(failure));
            }
        }

        PermissionSet updated = rank.permissions().set(permission, granted);
        return db.call(connection -> daos.cityRanks().updatePermissions(connection, rank.id(),
                updated.bits()))
                .thenApply(ignored -> {
                    scheduler.runOnMain(() -> rank.setPermissions(updated));
                    return Result.success(rank);
                });
    }

    /** Changes a rank's weight, which is how promotion order is edited. */
    public CompletableFuture<Result<CityRank>> setWeight(UUID actor, City city, CityRank rank,
                                                         int weight) {
        Result<Void> guard = canEdit(city, actor, rank);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        Result<Void> weightCheck = canUseWeight(city, actor, weight);
        if (weightCheck instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }

        CityRankRow row = new CityRankRow(rank.id(), city.id(), rank.name(), weight,
                rank.permissions().bits(), rank.isDefault());
        return db.call(connection -> daos.cityRanks().update(connection, row))
                .thenApply(ignored -> {
                    scheduler.runOnMain(() -> rank.setWeight(weight));
                    return Result.success(rank);
                });
    }

    /** Makes a rank the one new joiners receive, clearing the flag from whichever held it. */
    public CompletableFuture<Result<CityRank>> setDefault(UUID actor, City city, CityRank rank) {
        Result<Void> guard = canEdit(city, actor, rank);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        if (rank.isMayorRank()) {
            return completed(Result.failure("MAYOR_RANK_NOT_DEFAULT", "city.rank.mayor-not-default"));
        }

        Optional<CityRank> previous = city.defaultRank().filter(old -> old.id() != rank.id());

        return db.transaction(connection -> {
            if (previous.isPresent()) {
                CityRank old = previous.get();
                daos.cityRanks().update(connection, new CityRankRow(old.id(), city.id(), old.name(),
                        old.weight(), old.permissions().bits(), false));
            }
            daos.cityRanks().update(connection, new CityRankRow(rank.id(), city.id(), rank.name(),
                    rank.weight(), rank.permissions().bits(), true));
            return Result.success(rank);
        }).thenApply(result -> {
            if (result.isSuccess()) {
                scheduler.runOnMain(() -> {
                    previous.ifPresent(old -> old.setDefault(false));
                    rank.setDefault(true);
                });
            }
            return result;
        });
    }

    // ==================================================================================
    // Assignment
    // ==================================================================================

    /** Puts a member on a rank. */
    public CompletableFuture<Result<CityRank>> assign(UUID actor, City city, UUID target,
                                                      CityRank rank) {
        if (!city.hasPermission(actor, CityPermission.MANAGE_RANKS)) {
            return completed(Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.MANAGE_RANKS.name())));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }
        if (!city.isMember(target)) {
            return completed(Result.failure("NOT_A_MEMBER", "city.not-a-member"));
        }
        if (city.isMayor(target)) {
            return completed(Result.failure("CANNOT_RANK_MAYOR", "city.rank.mayor"));
        }
        // Both the rank being handed out and the target's current rank must be below the
        // actor, or a Co-Mayor could promote a Recruit to Co-Mayor, or demote a peer.
        if (city.weightOf(actor) <= rank.weight()) {
            return completed(Result.failure("RANK_OUTRANKS_ACTOR", "city.rank.outranked",
                    Map.of("rank", rank.name(), "weight", String.valueOf(rank.weight()))));
        }
        if (city.weightOf(actor) <= city.weightOf(target)) {
            return completed(Result.failure("OUTRANKED", "city.kick.outranked"));
        }

        CityRank from = city.rankOf(target).orElse(null);
        if (!events.fire(new CityRankChangeEvent(city, actor, target, from, rank))) {
            return completed(Result.failure("CANCELLED", "city.rank.cancelled"));
        }

        return db.transaction(connection -> {
            daos.cityMembers().updateRank(connection, target, rank.id());
            daos.players().updateCity(connection, target, city.id(), rank.id());
            return Result.success(rank);
        }).thenApply(result -> {
            if (result.isSuccess()) {
                scheduler.runOnMain(() ->
                        city.member(target).ifPresent(member -> member.setRankId(rank.id())));
            }
            return result;
        });
    }

    /** Moves a member up to the next rank by weight. */
    public CompletableFuture<Result<CityRank>> promote(UUID actor, City city, UUID target) {
        return step(actor, city, target, true);
    }

    /** Moves a member down to the next rank by weight. */
    public CompletableFuture<Result<CityRank>> demote(UUID actor, City city, UUID target) {
        return step(actor, city, target, false);
    }

    private CompletableFuture<Result<CityRank>> step(UUID actor, City city, UUID target, boolean up) {
        Optional<CityRank> current = city.rankOf(target);
        if (current.isEmpty()) {
            return completed(Result.failure("NOT_A_MEMBER", "city.not-a-member"));
        }
        int currentWeight = current.get().weight();

        Optional<CityRank> next = city.ranks().stream()
                .filter(rank -> up ? rank.weight() > currentWeight : rank.weight() < currentWeight)
                .min(up
                        ? Comparator.comparingInt(CityRank::weight)
                        : Comparator.comparingInt(CityRank::weight).reversed());

        if (next.isEmpty()) {
            return completed(Result.failure(up ? "ALREADY_HIGHEST" : "ALREADY_LOWEST",
                    up ? "city.rank.already-highest" : "city.rank.already-lowest"));
        }
        return assign(actor, city, target, next.get());
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }
}
