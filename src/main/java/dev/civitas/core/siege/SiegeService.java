package dev.civitas.core.siege;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.core.war.War;
import dev.civitas.core.war.WarState;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.dao.SiegeCampDao;
import dev.civitas.storage.dao.SiegeUnitDao;
import dev.civitas.storage.row.SiegeCampRow;
import dev.civitas.storage.row.SiegeUnitRow;
import dev.civitas.util.Result;

/**
 * Siege camps and siege units, SPEC 29.
 *
 * <h2>Why an attacker needs any of this</h2>
 *
 * <p>SPEC 29.1: "With defense units on one side and nothing on the other, war math tilts toward
 * turtling. Fewer declarations, fewer wars, and the rollback engine goes unused." Siege exists so
 * that a fortified city is expensive to take rather than impossible, and so that a war costs the
 * attacker money too — which the economy in Part II needs as a sink.
 *
 * <h2>Everything here is consumed</h2>
 *
 * <p>SPEC 29.4: units "Despawn at war end. <b>No refund, ever.</b>" and are not restored by
 * rollback. A dead unit's row is kept and still counts against the budget, so an attacker cannot
 * replace losses inside one war — the cap is a commitment, not a rate limit.
 */
public final class SiegeService {

    private final DatabaseManager db;
    private final SiegeCampDao camps;
    private final SiegeUnitDao units;
    private final SiegeCatalogue catalogue;
    private final SiegeCapacity capacity;
    private final CityRegistry cities;
    private final ClaimRegistry claims;
    private final TreasuryService treasury;
    private final ConfigManager configs;

    /** Camps by war id. Small — one per attacking city — and read on every block hit. */
    private final Map<Integer, List<SiegeCamp>> byWar = new ConcurrentHashMap<>();

    public SiegeService(DatabaseManager db, SiegeCampDao camps, SiegeUnitDao units,
                        SiegeCatalogue catalogue, SiegeCapacity capacity, CityRegistry cities,
                        ClaimRegistry claims, TreasuryService treasury, ConfigManager configs) {
        this.db = Objects.requireNonNull(db, "db");
        this.camps = Objects.requireNonNull(camps, "camps");
        this.units = Objects.requireNonNull(units, "units");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /** Rebuilds the camp cache at startup. */
    public CompletableFuture<Integer> loadAll() {
        return camps.findAll().thenApply(rows -> {
            byWar.clear();
            for (SiegeCampRow row : rows) {
                byWar.computeIfAbsent(row.warId(), key -> new ArrayList<>())
                        .add(SiegeCamp.fromRow(row));
            }
            return rows.size();
        });
    }

    public List<SiegeCamp> campsOf(int warId) {
        return List.copyOf(byWar.getOrDefault(warId, List.of()));
    }

    public Optional<SiegeCamp> campOf(int warId, int cityId) {
        return byWar.getOrDefault(warId, List.of()).stream()
                .filter(camp -> camp.cityId() == cityId)
                .findFirst();
    }

    /**
     * The camp standing in this chunk, if any.
     *
     * <p>Chunk rather than block because {@code /city map} draws chunks and because a camp is a
     * place. Two camps can never share a chunk in practice: SPEC 29.5 gives one per city and the
     * attacker's own claims and wilderness are the only ground either may use.
     */
    public Optional<SiegeCamp> campAt(String world, int chunkX, int chunkZ) {
        return byWar.values().stream()
                .flatMap(List::stream)
                .filter(SiegeCamp::stands)
                .filter(camp -> camp.world().equals(world)
                        && camp.chunkX() == chunkX && camp.chunkZ() == chunkZ)
                .findFirst();
    }

    // ==================================================================================
    // Placing a camp, SPEC 29.5
    // ==================================================================================

    public SiegePlacement placementRule() {
        return new SiegePlacement(capacity.maxCampDistanceChunks());
    }

    /**
     * Everything the pure rule needs, gathered from the live registries.
     *
     * <p>Kept separate from {@link #placeCamp} so a command can show a refusal before charging
     * anything, which is what {@code /city outpost cost} established for outposts: a player about
     * to spend money is entitled to see the rule that would stop them.
     */
    public SiegePlacement.Site siteFor(War war, City placer, String world, int blockX, int blockZ,
                                       long now) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        Integer owner = claims.at(world, chunkX, chunkZ).map(Claim::cityId).orElse(null);

        return new SiegePlacement.Site(
                isEngaged(war, now),
                war.isAttackerSide(placer.id()),
                placer.id(),
                owner,
                chunksToDefendingSide(war, world, chunkX, chunkZ),
                campOf(war.id(), placer.id()).orElse(null));
    }

    /**
     * Chebyshev distance in chunks from a site to the nearest defending-side claim.
     *
     * <p>Chebyshev because that is how every other distance in this plugin is measured — SPEC
     * 6.2's claim cost, SPEC 7.2's outpost spacing, SPEC 12.3's leash — and a camp rule that used
     * a different metric would put the boundary somewhere no player could predict from anything
     * else they had learned.
     *
     * @return {@link Integer#MAX_VALUE} when the defending side owns nothing in this world, which
     *         reads as "too far" and refuses rather than allowing a camp anywhere at all
     */
    public int chunksToDefendingSide(War war, String world, int chunkX, int chunkZ) {
        int nearest = Integer.MAX_VALUE;
        for (int cityId : war.side(false)) {
            for (Claim claim : claims.claimsOf(cityId)) {
                if (!claim.world().equals(world)) {
                    continue;
                }
                int distance = Math.max(Math.abs(claim.chunkX() - chunkX),
                        Math.abs(claim.chunkZ() - chunkZ));
                nearest = Math.min(nearest, distance);
            }
        }
        return nearest;
    }

    /**
     * Plants or rebuilds a camp.
     *
     * <p>The charge and the row share one transaction. A camp paid for and not recorded would be
     * money taken for nothing; a camp recorded and not paid for would be free siege.
     */
    public CompletableFuture<Result<SiegeCamp>> placeCamp(UUID actor, War war, City placer,
                                                          String world, int x, int y, int z,
                                                          long now) {
        if (!placer.hasPermission(actor, CityPermission.DECLARE_WAR)) {
            return completed(Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.DECLARE_WAR.name())));
        }

        SiegePlacement rule = placementRule();
        SiegePlacement.Site site = siteFor(war, placer, world, x, z, now);
        SiegePlacement.Verdict verdict = rule.judge(site);
        if (verdict != SiegePlacement.Verdict.OK) {
            return completed(refusal(verdict, site));
        }

        boolean rebuild = rule.isRebuild(site);
        BigDecimal cost = campCost(rebuild);
        double health = capacity.campHealth();

        return db.transaction(connection -> {
            Result<BigDecimal> paid = treasury.adjust(connection, placer, cost.negate(),
                    TransactionType.DEFENSE_PURCHASE, actor,
                    "{\"siege_camp\":" + war.id() + ",\"rebuild\":" + rebuild + "}");
            if (paid instanceof Result.Failure<BigDecimal> failure) {
                return Result.<SiegeCamp>propagate(failure);
            }

            SiegeCamp existing = site.existing();
            if (existing != null) {
                camps.rebuild(existing.id(), world, x, y, z, health, now).join();
                // Rebuilt in place: the row is the record that the one allowance is spent, and
                // a fresh row would hand the attacker another.
                SiegeCamp moved = new SiegeCamp(existing.id(), war.id(), placer.id(), world,
                        x, y, z, health, null, true);
                replace(war.id(), moved);
                return Result.success(moved);
            }

            int id = camps.insert(connection, new SiegeCampRow(0, war.id(), placer.id(), world,
                    x, y, z, health, now, null, false));
            SiegeCamp camp = new SiegeCamp(id, war.id(), placer.id(), world, x, y, z, health,
                    null, false);
            byWar.computeIfAbsent(war.id(), key -> new ArrayList<>()).add(camp);
            return Result.success(camp);
        });
    }

    private void replace(int warId, SiegeCamp camp) {
        List<SiegeCamp> list = byWar.computeIfAbsent(warId, key -> new ArrayList<>());
        list.removeIf(existing -> existing.id() == camp.id());
        list.add(camp);
    }

    private static Result<SiegeCamp> refusal(SiegePlacement.Verdict verdict,
                                             SiegePlacement.Site site) {
        return switch (verdict) {
            case WRONG_PHASE -> Result.failure("WRONG_PHASE", "siege.camp-wrong-phase");
            case NOT_ATTACKING -> Result.failure("NOT_ATTACKING", "siege.camp-not-attacking");
            case FOREIGN_GROUND -> Result.failure("FOREIGN_GROUND", "siege.camp-foreign-ground");
            case TOO_FAR -> Result.failure("TOO_FAR", "siege.camp-too-far",
                    Map.of("actual", site.chunksToDefender() == Integer.MAX_VALUE
                            ? "?" : String.valueOf(site.chunksToDefender())));
            case ALREADY_PLACED -> Result.failure("ALREADY_PLACED", "siege.camp-already-placed");
            case REBUILD_SPENT -> Result.failure("REBUILD_SPENT", "siege.camp-rebuild-spent");
            case OK -> throw new IllegalStateException("OK is not a refusal");
        };
    }

    // ==================================================================================
    // Losing a camp, SPEC 29.5
    // ==================================================================================

    /**
     * Applies damage to a camp.
     *
     * @return true when this blow destroyed it, exactly once
     */
    public boolean damageCamp(SiegeCamp camp, double amount, long now) {
        boolean destroyed = camp.damage(amount, now);
        if (destroyed) {
            // The guarded UPDATE is what makes "exactly once" true across two threads; the
            // in-memory check above only covers one.
            camps.markDestroyed(camp.id(), now);
            units.markCityDead(camp.warId(), camp.cityId());
            // SPEC 29.5: "Destroying the camp despawns all siege units of that city."
            despawn.accept(camp.warId(), camp.cityId());
        } else {
            camps.saveHealth(camp.id(), camp.health());
        }
        return destroyed;
    }

    // ==================================================================================
    // Buying units, SPEC 29.4
    // ==================================================================================

    /** What a city has committed to this war, dead units included. */
    public CompletableFuture<Integer> pointsSpent(int warId, int cityId) {
        return units.spentPoints(warId, cityId);
    }

    /**
     * Buys one siege unit and records it.
     *
     * <p>Unlike a defense unit there is no egg. SPEC 12.4 wanted a defensive placement to be
     * "deliberate and visible" inside a city; a siege unit has exactly one legal place to stand —
     * SPEC 29.4: "Where placed: inside a Siege Camp" — so there is no placement decision to make
     * and an item would only be a way to lose one.
     */
    public CompletableFuture<Result<SiegeUnitRow>> buy(UUID actor, War war, City buyer,
                                                       SiegeUnitType type, long now) {
        if (!buyer.hasPermission(actor, CityPermission.MANAGE_DEFENSE)) {
            return completed(Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.MANAGE_DEFENSE.name())));
        }
        if (!isEngaged(war, now)) {
            return completed(Result.failure("WRONG_PHASE", "siege.wrong-phase"));
        }
        if (!war.isAttackerSide(buyer.id())) {
            return completed(Result.failure("NOT_ATTACKING", "siege.not-attacking"));
        }

        Optional<SiegeCamp> camp = campOf(war.id(), buyer.id());
        if (camp.isEmpty() || !camp.get().stands()) {
            return completed(Result.failure("NO_CAMP", "siege.no-camp"));
        }

        int budget = war.siegeCapacity();
        return units.spentPoints(war.id(), buyer.id()).thenCompose(spent -> {
            if (!capacity.fits(spent, type.points(), budget)) {
                return completed(Result.<SiegeUnitRow>failure("CAPACITY_FULL",
                        "siege.capacity-full", Map.of(
                                "used", String.valueOf(spent),
                                "total", String.valueOf(budget),
                                "cost", String.valueOf(type.points()))));
            }

            SiegeCamp at = camp.get();
            return db.transaction(connection -> {
                Result<BigDecimal> paid = treasury.adjust(connection, buyer,
                        type.cost().negate(), TransactionType.DEFENSE_PURCHASE, actor,
                        "{\"siege_unit\":\"" + type.key() + "\",\"war\":" + war.id() + "}");
                if (paid instanceof Result.Failure<BigDecimal> failure) {
                    return Result.<SiegeUnitRow>propagate(failure);
                }
                SiegeUnitRow row = new SiegeUnitRow(0, war.id(), buyer.id(), type.key(),
                        type.points(), at.world(), at.x() + 0.5, at.y() + 1, at.z() + 0.5,
                        true, now);
                int id = units.insert(connection, row);
                return Result.success(new SiegeUnitRow(id, row.warId(), row.cityId(), row.type(),
                        row.points(), row.world(), row.x(), row.y(), row.z(), true, now));
            });
        });
    }

    public CompletableFuture<List<SiegeUnitRow>> unitsOf(int warId) {
        return units.findByWar(warId);
    }

    public CompletableFuture<Integer> markUnitDead(int unitId) {
        return units.markDead(unitId);
    }

    /**
     * Ends a war's siege, SPEC 29.4.
     *
     * <p>Rows are deleted rather than flagged. A siege has no life beyond its war, nothing is
     * refunded, and there is no later question the rows could answer — unlike a destroyed camp,
     * whose row is the only record that its one rebuild was spent.
     */
    public CompletableFuture<Void> endWar(int warId) {
        byWar.remove(warId);
        despawn.accept(warId, null);
        return units.deleteByWar(warId)
                .thenCompose(ignored -> camps.deleteByWar(warId))
                .thenApply(ignored -> null);
    }

    /**
     * Removes the mobs themselves.
     *
     * <p>Deleting rows is not despawning: the entities carry SPEC 12.5's persistence flags, so
     * without this a war's siege would stand in the world forever with nothing owning it. A seam
     * rather than a direct world scan because the service is otherwise free of Bukkit and is
     * tested without a server.
     *
     * <p>Does nothing until wired, which is the safe direction — an unwired plugin leaves mobs
     * standing, where the alternative failure would be deleting somebody's entities by mistake.
     */
    @FunctionalInterface
    public interface Despawn {

        /** @param cityId null to remove every unit of the war, per SPEC 29.4's war-end sweep */
        void remove(int warId, Integer cityId);
    }

    private java.util.function.BiConsumer<Integer, Integer> despawn = (warId, cityId) -> { };

    public void useDespawn(Despawn sweep) {
        Objects.requireNonNull(sweep, "sweep");
        this.despawn = sweep::remove;
    }

    // ==================================================================================
    // Configuration
    // ==================================================================================

    /** SPEC 29.4: "PREP and ACTIVE only". */
    private boolean isEngaged(War war, long now) {
        WarState state = war.state();
        return state == WarState.PREP || state == WarState.ACTIVE;
    }

    /**
     * What a camp costs.
     *
     * <p>SPEC 29.5 says a camp "can be rebuilt once per war at half cost" and never says what the
     * whole cost is. The percentage only means something against a figure, so one is shipped as a
     * config key — {@code siege.camp-cost} — and it is <b>this implementation's number, not
     * SPEC's</b>. The alternative was a free camp, which would have made SPEC's own
     * {@code camp-rebuild-cost-percent} inert: half of nothing is nothing.
     */
    public BigDecimal campCost(boolean rebuild) {
        BigDecimal full = new BigDecimal(configs.get(ConfigFile.DEFENSE)
                .getString("siege.camp-cost", "20000"));
        if (!rebuild) {
            return full;
        }
        return full.multiply(BigDecimal.valueOf(capacity.campRebuildCostPercent()))
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
    }

    /** How much health one blow takes off a camp. Not a SPEC figure; see {@link SiegeCamp}. */
    public double campDamagePerHit() {
        return configs.get(ConfigFile.DEFENSE).getDouble("siege.camp-damage-per-hit", 5.0);
    }

    /** Per-player debounce on camp hits, so one held mouse button is not a database flood. */
    public long campHitCooldownMillis() {
        return configs.get(ConfigFile.DEFENSE).getLong("siege.camp-hit-cooldown-ms", 400L);
    }

    public SiegeCatalogue catalogue() {
        return catalogue;
    }

    public SiegeCapacity capacityRule() {
        return capacity;
    }

    public CityRegistry cities() {
        return cities;
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }
}
