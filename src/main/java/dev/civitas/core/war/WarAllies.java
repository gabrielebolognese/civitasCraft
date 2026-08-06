package dev.civitas.core.war;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import dev.civitas.core.diplomacy.DiplomacyRegistry;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;

/**
 * Allies joining a war, SPEC 11.10.
 *
 * <p>"An allied city may join a war on either side via {@code /war join} during PREP only."
 * PREP only matters more than it looks: SPEC 11.4 fixes the war zone when ACTIVE begins, so an
 * ally that joined later would have its land inside nobody's zone, meaning damage to it would
 * be neither logged nor rolled back. The window closing is what keeps that from happening.
 *
 * <p>Joining costs 25% of the primary wager, which is the same bargain the primaries made:
 * money at risk before a single blow is struck, so helping an ally is a decision rather than a
 * favour.
 */
public final class WarAllies {

    private final DatabaseManager db;
    private final DaoRegistry daos;
    private final CityRegistry cities;
    private final DiplomacyRegistry diplomacy;
    private final WarRegistry registry;
    private final TreasuryService treasury;
    private final ConfigManager configs;
    private final Scheduler scheduler;

    public WarAllies(DatabaseManager db, DaoRegistry daos, CityRegistry cities,
                     DiplomacyRegistry diplomacy, WarRegistry registry, TreasuryService treasury,
                     ConfigManager configs, Scheduler scheduler) {
        this.db = Objects.requireNonNull(db, "db");
        this.daos = Objects.requireNonNull(daos, "daos");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.diplomacy = Objects.requireNonNull(diplomacy, "diplomacy");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Brings an allied city into a war.
     *
     * @param attackerSide which side they are joining
     */
    public CompletableFuture<Result<War>> join(UUID actor, City ally, War war,
                                                boolean attackerSide, long now) {
        Result<Void> checked = checkJoin(actor, ally, war, attackerSide);
        if (checked instanceof Result.Failure<Void> failure) {
            return CompletableFuture.completedFuture(Result.propagate(failure));
        }

        BigDecimal stake = allyStake(war);

        return db.transaction(connection -> {
            Result<BigDecimal> escrowed = treasury.adjust(connection, ally, stake.negate(),
                    TransactionType.WAR_WAGER_ESCROW, actor,
                    "{\"war\":" + war.id() + ",\"ally\":true}");
            if (escrowed instanceof Result.Failure<BigDecimal> failure) {
                return Result.<War>propagate(failure);
            }
            daos.warParticipants().insert(connection,
                    new dev.civitas.storage.row.WarParticipantRow(war.id(), ally.id(),
                            attackerSide ? "ATTACKER" : "DEFENDER", true));
            return Result.success(war);
        }).thenApply(result -> {
            if (result instanceof Result.Success<War>) {
                scheduler.runOnMain(() -> {
                    ally.setTreasury(ally.treasury().subtract(stake));
                    war.addAlly(ally.id(), attackerSide);
                    registry.reindex(war);
                });
            }
            return result;
        });
    }

    /** SPEC 11.10's rules for joining, checked in a readable order. */
    Result<Void> checkJoin(UUID actor, City ally, War war, boolean attackerSide) {
        if (!ally.hasPermission(actor, CityPermission.MANAGE_DIPLOMACY)) {
            return Result.failure("NO_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.MANAGE_DIPLOMACY.name()));
        }
        if (war.state() != WarState.PREP) {
            // SPEC 11.10: PREP only. See the class note for why the window matters.
            return Result.failure("NOT_PREP", "war.join.not-prep");
        }
        if (war.involves(ally.id())) {
            return Result.failure("ALREADY_IN", "war.join.already-in");
        }
        if (registry.engagedWarOf(ally.id()).isPresent()) {
            // SPEC 17.4 case 52 permits an ally to be in a different war, but not two at once:
            // SPEC 11.3 gives every city one war at a time and an ally is no exception.
            return Result.failure("ALREADY_AT_WAR", "war.join.already-at-war");
        }

        // Allied with the side they are joining: SPEC 11.10 is about helping an ally, not
        // about hiring out.
        int primary = attackerSide ? war.attackerCityId() : war.defenderCityId();
        if (!diplomacy.areAllied(ally.id(), primary)) {
            return Result.failure("NOT_ALLIED", "war.join.not-allied");
        }

        // SPEC 11.10: "A city may not join a war against its own ally."
        for (int enemy : war.side(!attackerSide)) {
            if (diplomacy.areAllied(ally.id(), enemy)) {
                return Result.failure("ALLIED_WITH_ENEMY", "war.join.allied-with-enemy");
            }
        }

        if (ally.isFrozen()) {
            return Result.failure("CITY_FROZEN", "city.frozen");
        }
        if (ally.treasury().compareTo(allyStake(war)) < 0) {
            return Result.failure("TREASURY_SHORT", "war.join.treasury-short",
                    Map.of("required", allyStake(war).toPlainString()));
        }
        return Result.ok();
    }

    /**
     * SPEC 11.10's second sentence: "If two allies end up on opposite sides, the alliance is
     * automatically broken and both are notified."
     *
     * <p>{@link #checkJoin} stops that happening at the moment of joining, but nothing forbids
     * two cities allying <em>after</em> they are already on opposite sides of a war, so this
     * runs when the fighting starts and settles any that slipped through.
     *
     * @return the pairs whose alliance was broken
     */
    public java.util.List<int[]> breakCrossSideAlliances(War war) {
        java.util.List<int[]> broken = new java.util.ArrayList<>();
        for (int attacker : war.side(true)) {
            for (int defender : war.side(false)) {
                if (diplomacy.areAllied(attacker, defender)) {
                    broken.add(new int[] {attacker, defender});
                }
            }
        }
        return broken;
    }

    /** 25% of the primary wager, per SPEC 11.10. */
    public BigDecimal allyStake(War war) {
        double percent = configs.get(ConfigFile.WAR)
                .getDouble("rewards.ally-wager-percent", 25);
        return war.wager().multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
    }

    /** The city, if it is in this war as an ally rather than a primary. */
    public Optional<City> allyIn(War war, int cityId) {
        if (cityId == war.attackerCityId() || cityId == war.defenderCityId()) {
            return Optional.empty();
        }
        return war.involves(cityId) ? cities.city(cityId) : Optional.empty();
    }
}
