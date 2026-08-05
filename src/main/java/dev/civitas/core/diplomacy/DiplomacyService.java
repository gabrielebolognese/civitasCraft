package dev.civitas.core.diplomacy;

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
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.AllianceRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;

/**
 * Alliances and truces, SPEC 14.
 *
 * <h2>Why breaking takes a day</h2>
 * SPEC 14.2's 24-hour notice is the most important rule here, and it is worth being explicit
 * about what it defends. Without it, an alliance is worth nothing the moment it matters: a
 * city declares war, breaks with its ally in the same breath, and attacks them that evening.
 * With it, the ally has a day of warning during which the alliance still holds, so betrayal
 * is something you announce rather than something you do.
 *
 * <h2>Why a truce cannot be cancelled</h2>
 * SPEC 14.3 says so directly, and calls it "the whole point". A non-aggression pact either
 * one side can end at will is not a pact, it is a preference.
 */
public final class DiplomacyService {

    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final CityRegistry cities;
    private final DiplomacyRegistry registry;
    private final ConfigManager configs;
    private final Scheduler scheduler;

    public DiplomacyService(DatabaseManager db, DaoRegistry daos, CityRegistry cities,
                            DiplomacyRegistry registry, ConfigManager configs,
                            Scheduler scheduler) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public DiplomacyRegistry registry() {
        return registry;
    }

    // ==================================================================================
    // Relations, SPEC 14.1
    // ==================================================================================

    /**
     * What one city is to another right now.
     *
     * <p>Precedence matters where two could apply: a war outranks everything, then a truce,
     * then an alliance. A pair that is allied <em>and</em> under truce reads as under truce,
     * because the truce is the one with an end date.
     */
    public Relation relationBetween(int cityId, int otherCityId, long now) {
        if (cityId == otherCityId) {
            return Relation.NEUTRAL;
        }
        if (isAtWar(cityId, otherCityId)) {
            return Relation.AT_WAR;
        }
        if (registry.hasTruce(cityId, otherCityId, now)) {
            return Relation.TRUCE;
        }
        if (registry.areAllied(cityId, otherCityId)) {
            return Relation.ALLY;
        }
        if (isRecentEnemy(cityId, otherCityId, now)) {
            return Relation.ENEMY;
        }
        return Relation.NEUTRAL;
    }

    // ==================================================================================
    // Alliances, SPEC 14.2
    // ==================================================================================

    /** Proposes an alliance. The other city has to accept before anything holds. */
    public CompletableFuture<Result<Alliance>> invite(UUID actor, City city, City other) {
        Result<Void> guard = check(actor, city, other);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        long now = System.currentTimeMillis();

        Optional<Alliance> existing = registry.alliance(city.id(), other.id());
        if (existing.filter(Alliance::isAllied).isPresent()) {
            return completed(Result.failure("ALREADY_ALLIED", "diplomacy.already-allied",
                    Map.of("city", other.name())));
        }
        if (existing.filter(alliance -> alliance.state() == AllianceState.PENDING).isPresent()) {
            return completed(Result.failure("ALREADY_PENDING", "diplomacy.already-pending",
                    Map.of("city", other.name())));
        }

        Result<Void> cooldown = checkReAllyCooldown(existing.orElse(null), now);
        if (cooldown instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }

        Result<Void> caps = checkAllyCaps(city, other);
        if (caps instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }

        Alliance proposed = new Alliance(city.id(), other.id(), AllianceState.PENDING,
                now, now, false, city.id());
        return write(proposed, existing.isPresent());
    }

    /** Accepts a proposal. Only the city that did not propose can. */
    public CompletableFuture<Result<Alliance>> accept(UUID actor, City city, City other) {
        Result<Void> guard = check(actor, city, other);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }

        Optional<Alliance> pending = registry.alliance(city.id(), other.id())
                .filter(alliance -> alliance.state() == AllianceState.PENDING)
                .filter(alliance -> alliance.proposedBy() != city.id());
        if (pending.isEmpty()) {
            return completed(Result.failure("NO_PROPOSAL", "diplomacy.no-proposal",
                    Map.of("city", other.name())));
        }

        // Re-checked at acceptance, not only at invitation: either city may have filled its
        // three slots in the meantime.
        Result<Void> caps = checkAllyCaps(city, other);
        if (caps instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }

        long now = System.currentTimeMillis();
        Alliance active = new Alliance(pending.get().cityAId(), pending.get().cityBId(),
                AllianceState.ACTIVE, now, now, false, pending.get().proposedBy());

        return db.transaction(connection -> {
            daos.alliances().updateState(connection, city.id(), other.id(),
                    AllianceState.ACTIVE.name(), now);
            return Result.success(active);
        }).thenApply(result -> apply(result, active));
    }

    /**
     * Gives notice, SPEC 14.2.
     *
     * <p>The alliance moves to {@code BREAKING} and stays in force until the notice expires.
     * There is no way to shorten it: an escape hatch here would return the rule to being a
     * formality.
     */
    public CompletableFuture<Result<Alliance>> breakAlliance(UUID actor, City city, City other) {
        Result<Void> guard = check(actor, city, other);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }

        Optional<Alliance> alliance = registry.alliance(city.id(), other.id());
        if (alliance.isEmpty() || !alliance.get().isAllied()) {
            return completed(Result.failure("NOT_ALLIED", "diplomacy.not-allied",
                    Map.of("city", other.name())));
        }
        if (alliance.get().state() == AllianceState.BREAKING) {
            return completed(Result.failure("ALREADY_BREAKING", "diplomacy.already-breaking",
                    Map.of("city", other.name(),
                            "hours", String.valueOf(hoursLeftOfNotice(alliance.get())))));
        }

        long now = System.currentTimeMillis();
        Alliance breaking = alliance.get().withState(AllianceState.BREAKING, now);

        return db.transaction(connection -> {
            daos.alliances().updateState(connection, city.id(), other.id(),
                    AllianceState.BREAKING.name(), now);
            return Result.success(breaking);
        }).thenApply(result -> apply(result, breaking));
    }

    /**
     * Turns SPEC 14.2's reciprocal build access on or off.
     *
     * <p>Grants {@code BUILD} and {@code INTERACT}, and never {@code CONTAINER}. That
     * exclusion is SPEC's, and it is what makes trust safe to give: an ally can help you
     * build and cannot empty your chests.
     */
    public CompletableFuture<Result<Alliance>> setTrusted(UUID actor, City city, City other,
                                                          boolean trusted) {
        Result<Void> guard = check(actor, city, other);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }
        Optional<Alliance> alliance = registry.alliance(city.id(), other.id())
                .filter(Alliance::isAllied);
        if (alliance.isEmpty()) {
            return completed(Result.failure("NOT_ALLIED", "diplomacy.not-allied",
                    Map.of("city", other.name())));
        }

        Alliance updated = alliance.get().withTrusted(trusted);
        return daos.alliances().setTrusted(city.id(), other.id(), trusted)
                .thenApply(changed -> {
                    scheduler.runOnMain(() -> registry.put(updated));
                    return Result.success(updated);
                });
    }

    // ==================================================================================
    // Truces, SPEC 14.3
    // ==================================================================================

    /**
     * Offers a truce of a given length.
     *
     * <p>Modelled as an immediate agreement rather than an offer-and-accept, because SPEC
     * 14.3 gives it no acceptance step and no way to refuse. A truce only ever restricts the
     * city that offers it as much as the city that receives it, so there is nothing to
     * protect a recipient from.
     *
     * @param days 1 to 30
     */
    public CompletableFuture<Result<Long>> offerTruce(UUID actor, City city, City other,
                                                      int days) {
        Result<Void> guard = check(actor, city, other);
        if (guard instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }

        int minimum = configs.get(ConfigFile.CITIES).getInt("diplomacy.truce-min-days", 1);
        int maximum = configs.get(ConfigFile.CITIES).getInt("diplomacy.truce-max-days", 30);
        if (days < minimum || days > maximum) {
            return completed(Result.failure("BAD_LENGTH", "diplomacy.truce-length",
                    Map.of("min", String.valueOf(minimum), "max", String.valueOf(maximum))));
        }

        long now = System.currentTimeMillis();
        if (registry.hasTruce(city.id(), other.id(), now)) {
            return completed(Result.failure("ALREADY_TRUCED", "diplomacy.already-truced",
                    Map.of("city", other.name())));
        }

        long expiresAt = now + days * MILLIS_PER_DAY;
        return daos.truces().upsert(city.id(), other.id(), expiresAt).thenApply(written -> {
            scheduler.runOnMain(() -> registry.putTruce(city.id(), other.id(), expiresAt));
            return Result.success(expiresAt);
        });
    }

    /**
     * The automatic truce SPEC 14.3 creates when a war ends.
     *
     * <p>Takes no actor and checks no permission, because nobody agreed to it: it is imposed
     * by the war ending. M19 calls this.
     */
    public CompletableFuture<Long> imposePostWarTruce(int cityId, int otherCityId, long now) {
        long days = configs.get(ConfigFile.WAR).getLong("rewards.immunity-days", 7);
        long expiresAt = now + days * MILLIS_PER_DAY;

        return daos.truces().upsert(cityId, otherCityId, expiresAt).thenApply(written -> {
            scheduler.runOnMain(() -> registry.putTruce(cityId, otherCityId, expiresAt));
            return expiresAt;
        });
    }

    // ==================================================================================
    // The SPEC 14.2 rules
    // ==================================================================================

    /** SPEC 14.2: at most three allies, so no server-wide bloc can form. */
    private Result<Void> checkAllyCaps(City city, City other) {
        int max = maxAllies();
        if (registry.allyCount(city.id()) >= max) {
            return Result.failure("ALLY_LIMIT", "diplomacy.ally-limit",
                    Map.of("limit", String.valueOf(max)));
        }
        if (registry.allyCount(other.id()) >= max) {
            return Result.failure("THEIR_ALLY_LIMIT", "diplomacy.their-ally-limit",
                    Map.of("city", other.name(), "limit", String.valueOf(max)));
        }
        return Result.ok();
    }

    /** SPEC 14.2: seven days after breaking before the same two may ally again. */
    private Result<Void> checkReAllyCooldown(Alliance previous, long now) {
        if (previous == null || previous.state() != AllianceState.BROKEN) {
            return Result.ok();
        }
        long cooldown = reAllyCooldownDays() * MILLIS_PER_DAY;
        long elapsed = now - previous.stateChangedAt();
        if (elapsed >= cooldown) {
            return Result.ok();
        }
        long daysLeft = Math.max(1, (cooldown - elapsed + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY);
        return Result.failure("REALLY_COOLDOWN", "diplomacy.re-ally-cooldown",
                Map.of("days", String.valueOf(daysLeft)));
    }

    /** How long is left of a notice period, rounded up. */
    public long hoursLeftOfNotice(Alliance alliance) {
        long ends = alliance.stateChangedAt() + noticeHours() * MILLIS_PER_HOUR;
        long remaining = ends - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (remaining + MILLIS_PER_HOUR - 1) / MILLIS_PER_HOUR;
    }

    /** Whether a notice period has run out and the alliance should now end. */
    public boolean noticeExpired(Alliance alliance, long now) {
        return alliance.state() == AllianceState.BREAKING
                && now >= alliance.stateChangedAt() + noticeHours() * MILLIS_PER_HOUR;
    }

    /** Completes a break whose notice has run out. Called by the sweep. */
    public CompletableFuture<Alliance> completeBreak(Alliance alliance, long now) {
        Alliance broken = alliance.withState(AllianceState.BROKEN, now).withTrusted(false);

        return db.transaction(connection -> {
            daos.alliances().updateState(connection, alliance.cityAId(), alliance.cityBId(),
                    AllianceState.BROKEN.name(), now);
            return broken;
        }).thenApply(result -> {
            scheduler.runOnMain(() -> registry.put(broken));
            return result;
        });
    }

    /** Everything a disbanding city was party to. */
    public CompletableFuture<Void> forgetCity(int cityId) {
        registry.forgetCity(cityId);
        return daos.alliances().deleteByCity(cityId)
                .thenCompose(ignored -> daos.truces().deleteByCity(cityId))
                .thenApply(ignored -> (Void) null);
    }

    // ==================================================================================
    // Config
    // ==================================================================================

    public int maxAllies() {
        return configs.get(ConfigFile.CITIES).getInt("diplomacy.max-allies", 3);
    }

    public long noticeHours() {
        return configs.get(ConfigFile.CITIES).getLong("diplomacy.break-notice-hours", 24);
    }

    public long reAllyCooldownDays() {
        return configs.get(ConfigFile.CITIES).getLong("diplomacy.re-ally-cooldown-days", 7);
    }

    // ==================================================================================
    // What M19 will answer
    // ==================================================================================

    /** SPEC 14.1, once wars exist in M19. */
    private boolean isAtWar(int cityId, int otherCityId) {
        return false;
    }

    /** SPEC 14.1's post-war marker, which decays after 30 days. M19's to record. */
    private boolean isRecentEnemy(int cityId, int otherCityId, long now) {
        return false;
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    /** The checks every diplomatic action shares. */
    private Result<Void> check(UUID actor, City city, City other) {
        if (city.id() == other.id()) {
            return Result.failure("SAME_CITY", "diplomacy.same-city");
        }
        if (!city.hasPermission(actor, CityPermission.MANAGE_DIPLOMACY)) {
            return Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.MANAGE_DIPLOMACY.name()));
        }
        if (city.isFrozen() || other.isFrozen()) {
            return Result.failure("CITY_FROZEN", "city.frozen");
        }
        return Result.ok();
    }

    private CompletableFuture<Result<Alliance>> write(Alliance alliance, boolean exists) {
        return db.transaction(connection -> {
            if (exists) {
                // Replaced rather than updated, because a re-proposal after a break changes
                // proposed_by and formed_at as well as the state. Updating only the state
                // would leave the old proposer recorded, and then the city that actually
                // proposed would be the only one unable to accept.
                daos.alliances().delete(connection, alliance.cityAId(), alliance.cityBId());
            }
            daos.alliances().insert(connection, alliance.toRow());
            return Result.success(alliance);
        }).thenApply(result -> apply(result, alliance));
    }

    private Result<Alliance> apply(Result<Alliance> result, Alliance alliance) {
        if (result instanceof Result.Success<Alliance>) {
            scheduler.runOnMain(() -> registry.put(alliance));
        }
        return result;
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }
}
