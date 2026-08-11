package dev.civitas.core.war;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.diplomacy.DiplomacyRegistry;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.WarRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Declaring, declining and resolving a war, SPEC 11.3 and 11.9.
 *
 * <h2>The preconditions are the anti-toxicity system</h2>
 * SPEC 11.3 lists twelve, and SPEC 15.2 explains that most of them exist to stop one player
 * ruining another's game rather than to model anything: the three-member minimum stops
 * alt-account war spam, the 21-day rematch cooldown stops targeted bullying, the wager cap at
 * 25% of the <em>smaller</em> treasury stops a rich city bankrupting a poor one by force, and
 * the decline option means nobody is made to fight. They are checked in SPEC's order so the
 * message a player gets names the first thing actually wrong.
 *
 * <h2>Money moves once, at declaration</h2>
 * Both wagers are escrowed the moment war is declared, which SPEC 17.4 cases 40 and 54 both
 * lean on: a city that goes bankrupt mid-war, or whose treasury shrinks before PREP ends,
 * changes nothing, because the money already left.
 */
public final class WarService {

    private static final long MILLIS_PER_HOUR = TimeUnit.HOURS.toMillis(1);
    private static final long MILLIS_PER_DAY = TimeUnit.DAYS.toMillis(1);

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final CityRegistry cities;
    private final ClaimRegistry claims;
    private final DiplomacyRegistry diplomacy;
    private final WarRegistry registry;
    private final TreasuryService treasury;
    private final ConfigManager configs;
    private final Scheduler scheduler;

    public WarService(DatabaseManager db, DaoRegistry daos, CityRegistry cities,
                      ClaimRegistry claims, DiplomacyRegistry diplomacy, WarRegistry registry,
                      TreasuryService treasury, ConfigManager configs, Scheduler scheduler) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.diplomacy = Objects.requireNonNull(diplomacy, "diplomacy");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public WarRegistry registry() {
        return registry;
    }

    // ==================================================================================
    // Declaration, SPEC 11.3
    // ==================================================================================

    /**
     * Declares war, checking every SPEC 11.3 precondition in the order SPEC lists them.
     *
     * <p>The wagers are escrowed inside the same transaction as the war row, so a declaration
     * either takes both cities' money and exists, or takes neither and does not.
     */
    public CompletableFuture<Result<War>> declare(UUID actor, City attacker, City defender,
                                                   BigDecimal wager, long now) {
        Result<Void> checked = checkDeclaration(actor, attacker, defender, wager, now);
        if (checked instanceof Result.Failure<Void> failure) {
            return completed(Result.propagate(failure));
        }

        BigDecimal amount = wager.setScale(2, RoundingMode.DOWN);
        long prepEnds = now + prepHours() * MILLIS_PER_HOUR;
        long warEnds = prepEnds + activeDays() * MILLIS_PER_DAY;

        return db.transaction(connection -> {
            // SPEC 11.3: "Both wagers are escrowed immediately. The money leaves both
            // treasuries and is held by the war."
            Result<BigDecimal> fromAttacker = treasury.adjust(connection, attacker,
                    amount.negate(), TransactionType.WAR_WAGER_ESCROW, actor, null);
            if (fromAttacker instanceof Result.Failure<BigDecimal> failure) {
                return Result.<War>propagate(failure);
            }
            Result<BigDecimal> fromDefender = treasury.adjust(connection, defender,
                    amount.negate(), TransactionType.WAR_WAGER_ESCROW, null, null);
            if (fromDefender instanceof Result.Failure<BigDecimal> failure) {
                return Result.<War>propagate(failure);
            }

            int id = daos.wars().insert(connection, new WarRow(0, attacker.id(), defender.id(),
                    now, prepEnds, warEnds, WarState.DECLARED.key(), 0, 0, null, amount,
                    null, null, siegeCapacityFor(defender)));

            return Result.success(new War(id, attacker.id(), defender.id(), now, prepEnds,
                    warEnds, WarState.DECLARED, amount));
        }).thenApply(result -> {
            if (result instanceof Result.Success<War>(War war)) {
                scheduler.runOnMain(() -> {
                    attacker.setTreasury(attacker.treasury().subtract(amount));
                    defender.setTreasury(defender.treasury().subtract(amount));
                    registry.remember(war);
                });
            }
            return result;
        });
    }

    /**
     * Whether this city could declare war on that one right now, without declaring.
     *
     * <p>The same twelve SPEC 11.3 preconditions the declaration runs, asked ahead of time.
     * SPEC 8.8's Wars screen wants this to explain why its button will refuse before a player
     * spends 50,000 C finding out, and an audit of SPEC 15's protections needs to ask the
     * question without starting a war to hear the answer.
     */
    public Result<Void> canDeclare(UUID actor, City attacker, City defender, BigDecimal wager,
                                    long now) {
        return checkDeclaration(actor, attacker, defender, wager, now);
    }

    /**
     * SPEC 11.3's twelve preconditions, in order.
     *
     * <p>Order matters for the message rather than the outcome: a player told "that city is
     * under war immunity" learns something, where one told the first failure alphabetically
     * learns nothing.
     */
    Result<Void> checkDeclaration(UUID actor, City attacker, City defender, BigDecimal wager,
                                   long now) {
        FileConfiguration war = configs.get(ConfigFile.WAR);

        // 1. The declarer holds DECLARE_WAR.
        if (!attacker.hasPermission(actor, CityPermission.DECLARE_WAR)) {
            return Result.failure("NO_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.DECLARE_WAR.name()));
        }
        if (attacker.id() == defender.id()) {
            return Result.failure("SELF_WAR", "war.declare.self");
        }

        // SPEC 32.8's disk guard: "Refuse to start a war if free disk is under
        // world.backup.min-free-gb." Here rather than at the PREP -> ACTIVE transition where the
        // snapshot is actually taken, because by then both wagers are escrowed and there is
        // nothing useful left to refuse. A declaration blocked here has cost nobody anything.
        if (!diskHeadroom.getAsBoolean()) {
            return Result.failure("NO_DISK_HEADROOM", "war.declare.no-disk");
        }

        // 2. Both cities have at least three members: SPEC 15.2, against alt-account war spam.
        int minMembers = war.getInt("declaration.min-members", 3);
        if (attacker.members().size() < minMembers || defender.members().size() < minMembers) {
            return Result.failure("TOO_FEW_MEMBERS", "war.declare.too-few-members",
                    Map.of("required", String.valueOf(minMembers)));
        }

        // 3. Both hold at least ten claims.
        int minClaims = war.getInt("declaration.min-claims", 10);
        if (claims.countOf(attacker.id()) < minClaims || claims.countOf(defender.id()) < minClaims) {
            return Result.failure("TOO_FEW_CLAIMS", "war.declare.too-few-claims",
                    Map.of("required", String.valueOf(minClaims)));
        }

        // 4. The attacker is old enough to have something to lose.
        long minAge = war.getLong("declaration.min-city-age-days", 14) * MILLIS_PER_DAY;
        if (attacker.ageMillis(now) < minAge) {
            return Result.failure("CITY_TOO_YOUNG", "war.declare.city-too-young",
                    Map.of("days", String.valueOf(war.getLong("declaration.min-city-age-days", 14))));
        }

        // 5. The defender is not under SPEC 11.9 immunity.
        if (defender.warProtectionUntil() > now) {
            return Result.failure("DEFENDER_IMMUNE", "war.declare.immune",
                    Map.of("hours", String.valueOf(
                            Math.max(1, (defender.warProtectionUntil() - now) / MILLIS_PER_HOUR))));
        }

        // 6. Neither is already engaged. SPEC 11.3: a city fights at most one war at a time.
        if (registry.engagedWarOf(attacker.id()).isPresent()) {
            return Result.failure("ATTACKER_AT_WAR", "war.declare.already-at-war");
        }
        if (registry.engagedWarOf(defender.id()).isPresent()) {
            return Result.failure("DEFENDER_AT_WAR", "war.declare.defender-at-war");
        }

        // 7 and 8. A truce or an alliance both forbid it.
        if (diplomacy.hasTruce(attacker.id(), defender.id(), now)) {
            return Result.failure("TRUCE", "war.declare.truce");
        }
        if (diplomacy.areAllied(attacker.id(), defender.id())) {
            return Result.failure("ALLIED", "war.declare.allied");
        }

        // 9. The wager floor, and the cap at a quarter of the SMALLER treasury: SPEC 15.2's
        // guard against wealth-based coercion.
        BigDecimal minWager = new BigDecimal(war.getString("declaration.min-wager", "50000"));
        if (wager.compareTo(minWager) < 0) {
            return Result.failure("WAGER_TOO_SMALL", "war.declare.wager-too-small",
                    Map.of("min", minWager.toPlainString()));
        }
        BigDecimal smaller = attacker.treasury().min(defender.treasury());
        BigDecimal cap = smaller
                .multiply(BigDecimal.valueOf(
                        war.getDouble("declaration.max-wager-percent-of-smaller-treasury", 25)))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
        if (wager.compareTo(cap) > 0) {
            return Result.failure("WAGER_TOO_LARGE", "war.declare.wager-too-large",
                    Map.of("max", cap.toPlainString()));
        }

        // 10. Both can actually pay it.
        if (attacker.treasury().compareTo(wager) < 0 || defender.treasury().compareTo(wager) < 0) {
            return Result.failure("TREASURY_SHORT", "war.declare.treasury-short",
                    Map.of("required", wager.toPlainString()));
        }

        // 11. Neither is frozen or in debt.
        if (attacker.isFrozen() || defender.isFrozen()) {
            return Result.failure("CITY_FROZEN", "city.frozen");
        }
        if (attacker.delinquentSince() != null || defender.delinquentSince() != null) {
            return Result.failure("DELINQUENT", "war.declare.delinquent");
        }

        // 12. The SPEC 15.2 anti-harassment cooldown on the same opponent.
        Result<Void> rematch = checkRematchCooldown(attacker.id(), defender.id(), now);
        if (rematch instanceof Result.Failure<Void> failure) {
            return Result.propagate(failure);
        }

        // SPEC 15.1: a large city may not declare on a small one.
        return checkSizeMismatch(attacker, defender);
    }

    /**
     * SPEC 15.1: "Cities with fewer than 5 members: exempt from being declared upon by cities
     * with more than 20 members (large cities cannot farm small ones)."
     */
    private Result<Void> checkSizeMismatch(City attacker, City defender) {
        FileConfiguration war = configs.get(ConfigFile.WAR);
        if (!war.getBoolean("declaration.large-vs-small-block", true)) {
            return Result.ok();
        }
        int large = war.getInt("declaration.large-city-member-threshold", 20);
        int small = war.getInt("declaration.small-city-member-threshold", 5);
        if (attacker.members().size() > large && defender.members().size() < small) {
            return Result.failure("SIZE_MISMATCH", "war.declare.size-mismatch",
                    Map.of("large", String.valueOf(large), "small", String.valueOf(small)));
        }
        return Result.ok();
    }

    /** SPEC 11.3 precondition 12, read from the war history rather than a counter. */
    private Result<Void> checkRematchCooldown(int attackerId, int defenderId, long now) {
        long cooldown = configs.get(ConfigFile.WAR)
                .getLong("declaration.same-opponent-cooldown-days", 21) * MILLIS_PER_DAY;
        if (cooldown <= 0) {
            return Result.ok();
        }
        // Read synchronously from the cache of recent wars the registry holds; a finished war
        // is dropped from it, so this is checked against storage by the caller in M20's
        // hardening pass. Kept here so the rule has one home.
        for (War past : registry.all()) {
            boolean samePair = (past.attackerCityId() == attackerId
                    && past.defenderCityId() == defenderId)
                    || (past.attackerCityId() == defenderId
                    && past.defenderCityId() == attackerId);
            if (samePair && now - past.declaredAt() < cooldown) {
                return Result.failure("REMATCH_COOLDOWN", "war.declare.rematch-cooldown",
                        Map.of("days", String.valueOf(cooldown / MILLIS_PER_DAY)));
            }
        }
        return Result.ok();
    }

    // ==================================================================================
    // Declining, SPEC 11.3
    // ==================================================================================

    /**
     * The defender's way out, SPEC 11.3.
     *
     * <p>"Declining costs the defender 30% of the wager, paid to the attacker, and grants the
     * attacker no score. This gives smaller cities an exit and prevents forced participation."
     * It is one of SPEC 15.2's named anti-toxicity mechanisms and the reason a declaration is
     * an offer rather than an ambush.
     */
    public CompletableFuture<Result<War>> decline(UUID actor, War war, long now) {
        Optional<City> defender = cities.city(war.defenderCityId());
        Optional<City> attacker = cities.city(war.attackerCityId());
        if (defender.isEmpty() || attacker.isEmpty()) {
            return completed(Result.failure("CITY_GONE", "city.unknown"));
        }
        if (!defender.get().hasPermission(actor, CityPermission.DECLARE_WAR)) {
            return completed(Result.failure("NO_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.DECLARE_WAR.name())));
        }
        if (war.state() != WarState.DECLARED) {
            return completed(Result.failure("TOO_LATE", "war.decline.too-late"));
        }
        long window = configs.get(ConfigFile.WAR)
                .getLong("declaration.decline-window-hours", 6) * MILLIS_PER_HOUR;
        if (now - war.declaredAt() > window) {
            return completed(Result.failure("WINDOW_CLOSED", "war.decline.too-late"));
        }

        BigDecimal penaltyPercent = BigDecimal.valueOf(configs.get(ConfigFile.WAR)
                .getDouble("declaration.decline-penalty-percent", 30));
        BigDecimal penalty = war.wager().multiply(penaltyPercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);

        return db.transaction(connection -> {
            // The defender gets their wager back less the penalty; the attacker gets theirs
            // back plus it. Nothing is burned: SPEC 11.9 burns only on a fought war.
            Result<BigDecimal> toDefender = treasury.adjust(connection, defender.get(),
                    war.wager().subtract(penalty), TransactionType.WAR_WAGER_REFUND, actor, null);
            if (toDefender instanceof Result.Failure<BigDecimal> failure) {
                return Result.<War>propagate(failure);
            }
            Result<BigDecimal> toAttacker = treasury.adjust(connection, attacker.get(),
                    war.wager().add(penalty), TransactionType.WAR_WAGER_PAYOUT, null, null);
            if (toAttacker instanceof Result.Failure<BigDecimal> failure) {
                return Result.<War>propagate(failure);
            }

            war.state(WarState.CANCELLED);
            daos.wars().updateState(connection, war.id(), WarState.CANCELLED.key());
            return Result.success(war);
        }).thenApply(result -> {
            if (result instanceof Result.Success<War>) {
                scheduler.runOnMain(() -> registry.forget(war.id()));
            }
            return result;
        });
    }

    // ==================================================================================
    // Configuration, SPEC 16.3
    // ==================================================================================

    public long prepHours() {
        return configs.get(ConfigFile.WAR).getLong("phases.prep-hours", 48);
    }

    public long activeDays() {
        return configs.get(ConfigFile.WAR).getLong("phases.active-days", 7);
    }

    public int perimeterChunks() {
        return configs.get(ConfigFile.WAR).getInt("zone.perimeter-chunks", 1);
    }

    public long immunityDays() {
        return configs.get(ConfigFile.WAR).getLong("rewards.immunity-days", 7);
    }

    /** Past wars a city fought, for {@code /war history}. */
    public java.util.concurrent.CompletableFuture<java.util.List<dev.civitas.storage.row.WarRow>>
            historyOf(int cityId, int limit) {
        return daos.wars().findByCity(cityId, limit);
    }

    // ==================================================================================
    // SPEC 9.4.5, the admin paths
    // ==================================================================================

    /**
     * SPEC 9.4.5: "Cancels a war, refunds both wagers in full, triggers immediate rollback."
     *
     * <p>Deliberately its own method rather than a flag on the player path. Every precondition
     * in SPEC 11.3 exists to stop a player doing something; an admin is expected to be able to
     * do it anyway, and expressing that as a bypass flag would put the bypass inside the same
     * branch a player takes. A separate method cannot leak.
     *
     * <p>Both wagers come back in full, which is SPEC 11.9's "Admin cancelled" row: the war did
     * not happen, so nobody should be poorer for it. The rollback still runs, because the
     * damage did happen.
     */
    public CompletableFuture<Result<War>> adminCancel(UUID admin, int warId, String reason,
                                                      long now) {
        War war = registry.war(warId).orElse(null);
        if (war == null) {
            return completed(Result.failure("NO_SUCH_WAR", "war.unknown"));
        }
        if (war.state().isFinished()) {
            return completed(Result.failure("ALREADY_FINISHED", "admin.war.already-finished"));
        }

        WarPayouts payouts = new WarPayouts(configs);
        BigDecimal each = payouts.cancelled(war.wager()).winnerReturn();

        return db.transaction(connection -> {
            for (int cityId : war.side(true)) {
                Result<BigDecimal> back = refund(connection, cityId, each, warId, "cancelled");
                if (back instanceof Result.Failure<BigDecimal> failure) {
                    return Result.<War>propagate(failure);
                }
            }
            for (int cityId : war.side(false)) {
                Result<BigDecimal> back = refund(connection, cityId, each, warId, "cancelled");
                if (back instanceof Result.Failure<BigDecimal> failure) {
                    return Result.<War>propagate(failure);
                }
            }
            return Result.success(war);
        }).thenApply(result -> {
            if (result instanceof Result.Success<War>) {
                // No winner: a cancelled war goes on nobody's record. SPEC 11.9 pays it out
                // as a non-event, and a W or an L for a war an admin stopped would be a lie.
                //
                // A war that was ACTIVE still has damage logged against it, and SPEC 1.2 does
                // not care why the war ended, so it goes to ROLLING_BACK. One still in PREP
                // destroyed nothing and simply cancels.
                war.winnerCityId(null);
                war.state(war.state() == WarState.ACTIVE
                        ? WarState.ROLLING_BACK
                        : WarState.CANCELLED);
            }
            return result;
        });
    }

    /**
     * SPEC 9.4.5: "Ends a war early with a specified result, runs rollback."
     *
     * <p>The result is imposed rather than computed, which is the whole point of the command:
     * an admin uses it when the scores do not reflect what happened. The payout follows SPEC
     * 11.9 exactly as a natural ending would, so a war an admin ended pays what a war that ran
     * its course would have paid.
     */
    public CompletableFuture<Result<War>> adminForceEnd(UUID admin, int warId, String result,
                                                        String reason, long now) {
        War war = registry.war(warId).orElse(null);
        if (war == null) {
            return completed(Result.failure("NO_SUCH_WAR", "war.unknown"));
        }
        if (war.state().isFinished()) {
            return completed(Result.failure("ALREADY_FINISHED", "admin.war.already-finished"));
        }

        // The clock is moved to now so the phase task treats the war as over rather than
        // fighting the admin over it on the next sweep.
        war.winnerCityId(switch (result) {
            case "attacker" -> war.attackerCityId();
            case "defender" -> war.defenderCityId();
            default -> null;
        });
        war.state(WarState.ROLLING_BACK);

        return daos.wars().findById(warId)
                .thenCompose(row -> row.map(existing -> daos.wars().update(
                                new dev.civitas.storage.row.WarRow(existing.id(),
                                        existing.attackerCityId(), existing.defenderCityId(),
                                        existing.declaredAt(), existing.prepEndsAt(), now,
                                        WarState.ROLLING_BACK.key(), existing.attackerScore(),
                                        existing.defenderScore(), war.winnerCityId(),
                                        existing.wager(), null,
                                        existing.rollbackCheckpointSequence(),
                                        existing.siegeCapacity()))
                                .thenApply(updated -> Result.success(war)))
                        .orElseGet(() -> completed(Result.failure("NO_SUCH_WAR", "war.unknown"))));
    }

    /** SPEC 9.4.5: "Extends the war window." */
    public CompletableFuture<Result<War>> adminExtend(int warId, long extraMillis) {
        War war = registry.war(warId).orElse(null);
        if (war == null) {
            return completed(Result.failure("NO_SUCH_WAR", "war.unknown"));
        }
        if (war.state().isFinished()) {
            return completed(Result.failure("ALREADY_FINISHED", "admin.war.already-finished"));
        }

        return daos.wars().findById(warId)
                .thenCompose(row -> row.map(existing -> daos.wars().update(
                                new dev.civitas.storage.row.WarRow(existing.id(),
                                        existing.attackerCityId(), existing.defenderCityId(),
                                        existing.declaredAt(), existing.prepEndsAt(),
                                        existing.warEndsAt() + extraMillis, existing.state(),
                                        existing.attackerScore(), existing.defenderScore(),
                                        existing.winnerCityId(), existing.wager(), null,
                                        existing.rollbackCheckpointSequence(),
                                        existing.siegeCapacity()))
                                .thenApply(updated -> {
                                    // War.warEndsAt is final, so extending means replacing the
                                    // cached object. Everything else about it is carried over,
                                    // including the zone, which SPEC 11.4 forbids recomputing.
                                    War extended = new War(war.id(), war.attackerCityId(),
                                            war.defenderCityId(), war.declaredAt(),
                                            war.prepEndsAt(), war.warEndsAt() + extraMillis,
                                            war.state(), war.wager());
                                    extended.zone(war.zone());
                                    extended.winnerCityId(war.winnerCityId());
                                    extended.addScore(true, war.attackerScore());
                                    extended.addScore(false, war.defenderScore());
                                    for (int ally : war.attackerAllies()) {
                                        extended.addAlly(ally, true);
                                    }
                                    for (int ally : war.defenderAllies()) {
                                        extended.addAlly(ally, false);
                                    }
                                    registry.remember(extended);
                                    return Result.success(extended);
                                }))
                        .orElseGet(() -> completed(
                                Result.<War>failure("NO_SUCH_WAR", "war.unknown"))));
    }

    /** SPEC 9.4.5: "Grants war immunity." */
    public CompletableFuture<Result<City>> adminGrantImmunity(City city, long until) {
        return daos.cities().updateWarProtection(city.id(), until)
                .thenApply(updated -> {
                    city.setWarProtectionUntil(until);
                    return Result.success(city);
                });
    }

    /** Puts a wager back into one city's treasury, inside the caller's transaction. */
    private Result<BigDecimal> refund(java.sql.Connection connection, int cityId,
                                      BigDecimal amount, int warId, String why)
            throws java.sql.SQLException {
        if (amount.signum() <= 0) {
            return Result.success(BigDecimal.ZERO);
        }
        City city = cities.city(cityId).orElse(null);
        if (city == null) {
            // A city that no longer exists cannot be paid. The war still ends.
            return Result.success(BigDecimal.ZERO);
        }
        return treasury.adjust(connection, city, amount,
                dev.civitas.core.economy.TransactionType.WAR_WAGER_REFUND, null,
                "{\"war\":" + warId + ",\"reason\":\"" + why + "\"}");
    }

    /** The last kills of a war, for SPEC 8.8's kill feed. */
    public java.util.concurrent.CompletableFuture<java.util.List<dev.civitas.storage.row.WarKillRow>>
            recentKills(int warId, int limit) {
        return daos.warKills().findRecent(warId, limit);
    }

    /** A city's claims, for the capture points SPEC 11.6 places on the defender's land. */
    public java.util.Collection<dev.civitas.core.claim.Claim> claimsOf(int cityId) {
        return claims.claimsOf(cityId);
    }

    /** Builds the SPEC 11.4 zone for a war, from the claims of everyone in it. */
    public WarZone computeZone(War war) {
        java.util.List<dev.civitas.core.claim.Claim> all = new java.util.ArrayList<>();
        for (int cityId : war.side(true)) {
            all.addAll(claims.claimsOf(cityId));
        }
        for (int cityId : war.side(false)) {
            all.addAll(claims.claimsOf(cityId));
        }
        return WarZone.of(all, perimeterChunks());
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }

    /**
     * SPEC 29.2's budget, computed once here and never again.
     *
     * <p>Zero until the siege milestone wires the calculator, which is the conservative
     * direction: an attacker with no budget fields no siege, where a wrong default would field
     * an army nobody sized.
     */
    private int siegeCapacityFor(City defender) {
        return siege == null ? 0 : siege.applyAsInt(defender.id());
    }

    private java.util.function.IntUnaryOperator siege;

    /** Hands the service SPEC 29.2's calculator. M19a's only touchpoint here. */
    public void useSiegeCapacity(java.util.function.IntUnaryOperator calculator) {
        this.siege = java.util.Objects.requireNonNull(calculator, "calculator");
    }

    /**
     * SPEC 32.8's disk guard.
     *
     * <p>Answers yes until wired, which is the conservative direction here and not the usual one:
     * a seam that refused by default would block every war on a server whose backups are switched
     * off entirely, and the guard exists to protect a snapshot that would not be taken.
     */
    private java.util.function.BooleanSupplier diskHeadroom = () -> true;

    public void useDiskGuard(java.util.function.BooleanSupplier headroom) {
        this.diskHeadroom = java.util.Objects.requireNonNull(headroom, "headroom");
    }
}
