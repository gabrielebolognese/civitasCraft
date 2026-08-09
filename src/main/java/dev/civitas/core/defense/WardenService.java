package dev.civitas.core.defense;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.core.upgrade.UpgradeService;
import dev.civitas.core.upgrade.UpgradeType;
import dev.civitas.storage.dao.CityWardenDao;
import dev.civitas.storage.dao.DefenseUnitDao;
import dev.civitas.storage.row.CityWardenRow;
import dev.civitas.storage.row.DefenseUnitRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;

/**
 * Buying, losing and recovering the City Warden, SPEC 28.
 *
 * <p>Separate from {@link DefenseService} because almost nothing it does applies here. A Warden is
 * not bought from the shop, does not cost Defense Capacity (SPEC 28.2 excludes it from the budget
 * outright), is not handed over as a spawn item, may only stand in one chunk, and cannot be killed
 * at all outside a war. What it shares with an ordinary unit is the {@code defense_units} row —
 * so materialisation, the leash, the upkeep sum and the death handler need no second path.
 */
public final class WardenService {

    private final dev.civitas.storage.DatabaseManager db;
    private final CityWardenDao wardens;
    private final DefenseUnitDao units;
    private final WardenRegistry registry;
    private final DefenseRegistry defenseUnits;
    private final DefenseCatalogue catalogue;
    private final CityRegistry cities;
    private final TreasuryService treasury;
    private final UpgradeService upgrades;
    private final Scheduler scheduler;

    public WardenService(dev.civitas.storage.DatabaseManager db, CityWardenDao wardens,
                         DefenseUnitDao units, WardenRegistry registry,
                         DefenseRegistry defenseUnits, DefenseCatalogue catalogue,
                         CityRegistry cities, TreasuryService treasury, UpgradeService upgrades,
                         Scheduler scheduler) {
        this.db = Objects.requireNonNull(db, "db");
        this.wardens = Objects.requireNonNull(wardens, "wardens");
        this.units = Objects.requireNonNull(units, "units");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.defenseUnits = Objects.requireNonNull(defenseUnits, "defenseUnits");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.upgrades = Objects.requireNonNull(upgrades, "upgrades");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public WardenRegistry registry() {
        return registry;
    }

    // ==================================================================================
    // SPEC 28.2, buying
    // ==================================================================================

    /**
     * Whether this city may buy a Warden right now, and why not.
     *
     * <p>Asked by the command, by the menu's lore and by {@link #purchase}. Kept public so the
     * refusal a player is shown before they click is the same one the service would give, rather
     * than a second copy of the rule that can drift from it.
     */
    public Optional<CityWarden.Refusal> check(City city) {
        return CityWarden.checkPurchase(
                catalogue.wardenEnabled() && catalogue.warden().isPresent(),
                registry.owns(city.id()),
                upgrades.levelOf(city, UpgradeType.FORTIFICATION),
                catalogue.wardenRequiredFortification());
    }

    /**
     * Buys and places the Warden in one action.
     *
     * <p>Deliberately not SPEC 27.8's spawn item. SPEC 28.2 leaves exactly one legal chunk — "must
     * be placed in the core chunk, and cannot be moved afterwards" — so an item that could be
     * carried off, dropped in lava or handed to somebody adds no decision and adds one way to lose
     * 750,000 C. The buyer stands in the core chunk instead, which is the same requirement read
     * from the other end.
     *
     * <p>The treasury charge, the {@code defense_units} row and the {@code city_wardens} row are
     * one transaction. The primary key on {@code city_wardens.city_id} is what makes SPEC 28.2's
     * limit physical: two members buying in the same tick cannot both insert, and the loser's
     * charge is rolled back with the rest of its transaction.
     */
    public CompletableFuture<Result<CityWarden.Owned>> purchase(UUID actor, City city,
                                                                String world, double x,
                                                                double y, double z) {
        if (!city.hasPermission(actor, CityPermission.MANAGE_DEFENSE)) {
            return completed(Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.MANAGE_DEFENSE.name())));
        }
        if (city.isFrozen()) {
            return completed(Result.failure("CITY_FROZEN", "city.frozen"));
        }

        Optional<CityWarden.Refusal> refused = check(city);
        if (refused.isPresent()) {
            return completed(refusal(refused.get(), city));
        }
        Optional<CityWarden.Refusal> misplaced = CityWarden.checkPlacement(city.coreWorld(),
                city.coreChunkX(), city.coreChunkZ(), world,
                (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        if (misplaced.isPresent()) {
            return completed(refusal(misplaced.get(), city));
        }

        DefenseUnitType type = catalogue.warden().orElseThrow();
        BigDecimal cost = catalogue.wardenCost();
        long now = System.currentTimeMillis();

        return db.<Result<CityWarden.Owned>>transaction(connection -> {
            Result<BigDecimal> charged = treasury.adjust(connection, city, cost.negate(),
                    TransactionType.DEFENSE_PURCHASE, actor, "{\"unit\":\"" + type.key() + "\"}");
            if (charged instanceof Result.Failure<BigDecimal> failure) {
                return Result.propagate(failure);
            }
            int unitId = units.insert(connection, new DefenseUnitRow(0, city.id(), type.key(),
                    world, x, y, z, type.upkeepPerDay(), true, null, null));
            wardens.insert(connection, new CityWardenRow(city.id(), unitId, now, null));
            return Result.success(new CityWarden.Owned(city.id(), unitId, now, null));
        }).thenApply(result -> {
            if (result instanceof Result.Success<CityWarden.Owned>(CityWarden.Owned owned)) {
                DefenseUnit unit = new DefenseUnit(owned.unitId(), city.id(), type.key(), world,
                        x, y, z, type.upkeepPerDay(), true, null, null);
                scheduler.runOnMain(() -> {
                    registry.put(owned);
                    defenseUnits.put(unit);
                    onPurchased.accept(city, owned);
                });
            }
            return result;
        });
    }

    // ==================================================================================
    // SPEC 28.6, dying and coming back
    // ==================================================================================

    /**
     * A killing blow has landed. Whether it kills is SPEC 28.6's question.
     *
     * <p>In peacetime it does not: the Warden burrows, its row survives, and it re-emerges at full
     * health once {@code warden.recovery-hours} have passed. In an ACTIVE war it does, and the city
     * must buy another at full price (SPEC 30.2 case 97, explicitly including the war's last day).
     *
     * @return the recovery deadline when it survived, or empty when it died for good
     */
    public boolean diesPermanentlyFor(int cityId) {
        return CityWarden.diesPermanently(atWar.test(cityId));
    }

    public CompletableFuture<Optional<Long>> defeated(CityWarden.Owned owned, long now) {
        return defeated(owned, now, null);
    }

    /** @param killer who landed the blow, or null when nothing with a name did */
    public CompletableFuture<Optional<Long>> defeated(CityWarden.Owned owned, long now,
                                                      String killer) {
        if (CityWarden.diesPermanently(atWar.test(owned.cityId()))) {
            return destroyInWar(owned).thenApply(ignored -> Optional.empty());
        }

        long until = CityWarden.recoveryEndsAt(now, catalogue.wardenRecoveryHours());
        return wardens.setRecoveringUntil(owned.cityId(), until).thenApply(rows -> {
            registry.put(owned.recoveringUntil(until));
            scheduler.runOnMain(() -> {
                // Down through the materializer, never through a despawn: SPEC 28.6 says it comes
                // back "at full health", and the health it goes down at is what gets written.
                // Setting it full here instead would be a heal a war could not take away.
                defenseUnits.byId(owned.unitId()).ifPresent(unit ->
                        takeDown.take(unit, now));
                cities.city(owned.cityId()).ifPresent(city ->
                        onDefeated.tell(city, catalogue.wardenRecoveryHours(), killer));
            });
            return Optional.of(until);
        });
    }

    /**
     * Every Warden whose six hours are up, brought back to the surface.
     *
     * <p>A sweep over a deadline rather than a scheduled task, because SPEC 30.2 case 98 forbids
     * recovery being accelerated — "Recovery continues. The city fights that war without it" — and
     * a task cannot survive the crash it exists to be robust against.
     *
     * <p>Full health is restored by clearing the stored figure. {@code DefenseUnit.healthOr} reads
     * a null as the maximum, which is the same rule a never-materialised unit already follows.
     */
    public CompletableFuture<Integer> sweepRecovered(long now) {
        return wardens.findRecovered(now).thenCompose(rows -> {
            List<CompletableFuture<Integer>> writes = new ArrayList<>();
            for (CityWardenRow row : rows) {
                writes.add(recover(WardenRegistry.of(row)));
            }
            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> rows.size());
        });
    }

    private CompletableFuture<Integer> recover(CityWarden.Owned owned) {
        return wardens.setRecoveringUntil(owned.cityId(), null).thenCompose(rows -> {
            registry.put(owned.recovered());
            return units.saveState(owned.unitId(), null, null).thenApply(written -> {
                scheduler.runOnMain(() -> {
                    defenseUnits.byId(owned.unitId())
                            .ifPresent(unit -> defenseUnits.put(unit.withState(null, null)));
                    cities.city(owned.cityId()).ifPresent(onRecovered);
                });
                return rows;
            });
        });
    }

    /** SPEC 28.6 in a war, and SPEC 30.2 case 97: gone, and repurchasable at full price. */
    public CompletableFuture<Integer> destroy(CityWarden.Owned owned) {
        registry.remove(owned.cityId());
        defenseUnits.remove(owned.unitId());
        return wardens.delete(owned.cityId())
                .thenCompose(ignored -> units.delete(owned.unitId()));
    }

    /** The same, announced. Only the war path tells anyone; a disband and case 99 do not. */
    private CompletableFuture<Integer> destroyInWar(CityWarden.Owned owned) {
        return destroy(owned).thenApply(rows -> {
            scheduler.runOnMain(() ->
                    cities.city(owned.cityId()).ifPresent(onDestroyedInWar));
            return rows;
        });
    }

    // ==================================================================================
    // SPEC 30.2 case 99, and disbanding
    // ==================================================================================

    /**
     * "Core chunk is admin-transferred while a Warden is placed: Warden is removed and the city is
     * refunded 100%, because this is an admin action, not a player outcome."
     *
     * <p>The only path in the plugin that gives a defense unit its money back. SPEC 25.2 Rule 4
     * makes units consumed resources and nothing else refunds one — but a city losing 750,000 C
     * because an operator moved a chunk is not a game outcome, and case 99 says so.
     *
     * @return the amount refunded, or zero when the city had no Warden
     */
    public CompletableFuture<BigDecimal> removeForAdmin(City city) {
        Optional<CityWarden.Owned> owned = registry.of(city.id());
        if (owned.isEmpty()) {
            return CompletableFuture.completedFuture(dev.civitas.storage.SqlDialect.zero());
        }
        BigDecimal refund = catalogue.wardenCost();
        return destroy(owned.get()).thenCompose(ignored -> db.transaction(connection ->
                        treasury.adjust(connection, city, refund, TransactionType.DEFENSE_PURCHASE,
                                null, "{\"unit\":\"" + CityWarden.TYPE_KEY
                                        + "\",\"reason\":\"admin_core_chunk_moved\"}")))
                .thenApply(result -> {
                    scheduler.runOnMain(() -> onAdminRemoved.accept(city, refund));
                    return refund;
                });
    }

    /** A disbanding city takes its Warden with it, refunding nothing. */
    public CompletableFuture<Integer> removeCity(int cityId) {
        Optional<CityWarden.Owned> owned = registry.of(cityId);
        if (owned.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return destroy(owned.get());
    }

    // ==================================================================================
    // Seams
    // ==================================================================================

    /** Whether a city is in an ACTIVE war, which is the only thing that makes a death final. */
    private IntPredicate atWar = cityId -> false;

    /**
     * Wired to the war registry.
     *
     * <p>Answers "no war" until it is, which is the conservative direction by a long way: a wiring
     * mistake leaves a Warden recoverable when it should have died, where the opposite would delete
     * a 2.75 million coin asset in peacetime — exactly the outcome SPEC 28.6 exists to prevent.
     */
    public void useWars(IntPredicate cityInActiveWar) {
        this.atWar = Objects.requireNonNull(cityInActiveWar, "cityInActiveWar");
    }

    /** How a defeated Warden is taken down; wired to {@link UnitMaterializer#dematerialize}. */
    @FunctionalInterface
    public interface TakeDown {

        void take(DefenseUnit unit, long now);
    }

    private TakeDown takeDown = (unit, now) -> { };

    public void useMaterializer(TakeDown how) {
        this.takeDown = Objects.requireNonNull(how, "how");
    }

    private BiConsumer<City, CityWarden.Owned> onPurchased = (city, owned) -> { };
    private Defeat onDefeated = (city, hours, killer) -> { };
    private java.util.function.Consumer<City> onRecovered = city -> { };
    private BiConsumer<City, BigDecimal> onAdminRemoved = (city, refund) -> { };
    private java.util.function.Consumer<City> onDestroyedInWar = city -> { };

    /** SPEC 30.4's {@code warden.destroyed_war}, which SPEC sends to both sides and the server. */
    public void onDestroyedInWar(java.util.function.Consumer<City> notifier) {
        this.onDestroyedInWar = Objects.requireNonNull(notifier, "notifier");
    }

    /** SPEC 30.4's {@code warden.purchased}, which is a server-wide announcement. */
    public void onPurchased(BiConsumer<City, CityWarden.Owned> notifier) {
        this.onPurchased = Objects.requireNonNull(notifier, "notifier");
    }

    /**
     * SPEC 30.4's {@code warden.defeated_peacetime}, and SPEC 28.6's "the city is notified".
     *
     * @param killer who did it, or null. SPEC 30.4's template names {@code {player}} and assumes a
     *               player did it; in peacetime a creeper, a lava flow or the void are all at
     *               least as likely, and "driven underground by ?" is worse than not naming anyone
     */
    @FunctionalInterface
    public interface Defeat {

        void tell(City city, long recoveryHours, String killer);
    }

    public void onDefeated(Defeat notifier) {
        this.onDefeated = Objects.requireNonNull(notifier, "notifier");
    }

    /** SPEC 30.4's {@code warden.returned}, the other half of the same sentence. */
    public void onRecovered(java.util.function.Consumer<City> notifier) {
        this.onRecovered = Objects.requireNonNull(notifier, "notifier");
    }

    /** Case 99's refund, told to the city that did not choose to lose it. */
    public void onAdminRemoved(BiConsumer<City, BigDecimal> notifier) {
        this.onAdminRemoved = Objects.requireNonNull(notifier, "notifier");
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private <T> Result<T> refusal(CityWarden.Refusal refusal, City city) {
        if (refusal == CityWarden.Refusal.NEEDS_FORTIFICATION) {
            return Result.failure(refusal.name(), refusal.messageKey(),
                    Map.of("required", String.valueOf(catalogue.wardenRequiredFortification()),
                            "level", String.valueOf(
                                    upgrades.levelOf(city, UpgradeType.FORTIFICATION))));
        }
        return Result.failure(refusal.name(), refusal.messageKey());
    }

    private static <T> CompletableFuture<Result<T>> completed(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }
}
