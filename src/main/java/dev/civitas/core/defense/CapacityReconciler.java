package dev.civitas.core.defense;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.storage.dao.DefenseUnitDao;
import dev.civitas.util.Scheduler;

/**
 * SPEC 30.2 case 101, applied to the world: "Units over budget are marked inactive
 * (dematerialized, upkeep suspended) newest-first until within budget. <b>Not deleted.</b>"
 *
 * <p>{@link DefenseCapacity} decides which units; this one carries it out, which means three
 * things that are easy to get wrong on their own and are done in one place here.
 *
 * <h2>Marking a unit inactive does not take it down</h2>
 *
 * <p>{@link UnitMaterializer}'s sweep skips units that are not active, so it will never issue a
 * dematerialise for one — a unit suspended while standing would simply keep standing forever,
 * which is the opposite of what case 101 asks for. The dematerialise is issued here.
 *
 * <h2>And it goes down through the materializer, never through despawn</h2>
 *
 * <p>{@link DefenseService#despawn} removes the entity and writes nothing;
 * {@link UnitMaterializer#dematerialize} writes the health it was standing at. Using the former
 * would make a Fortification round trip a free full heal for a whole garrison — the healing SPEC
 * 25.4 disables during a war, handed back by a bookkeeping route.
 *
 * <h2>One rule in both directions</h2>
 *
 * <p>A solvent city stands up as many of its units as its capacity allows, oldest first, and
 * takes down whatever is over, newest first. That is idempotent, so running it at startup, on
 * {@code /ca reload} and whenever a debt clears converges on the same garrison every time.
 *
 * <p>A delinquent city is left alone entirely. SPEC 12.3 gives the upkeep sweep the flag while a
 * city is in debt, and two systems writing one boolean from opposite directions is how a city
 * ends up with an army it is not paying for.
 *
 * <h2>Why this is a sweep and not a hook</h2>
 *
 * <p>Case 101 says "after a Fortification downgrade", and nothing in the plugin can downgrade an
 * upgrade: {@code UpgradeService} has only {@code purchase}, and SPEC 9.4 defines no admin
 * command for it. The realistic trigger is an operator lowering {@code capacity.base} or
 * {@code capacity.per-fortification-level} and reloading — which SPEC never mentions and which
 * no event can announce. So there is nothing to hook, and this runs as a pass instead.
 */
public final class CapacityReconciler {

    private final DefenseService defense;
    private final DefenseRegistry registry;
    private final DefenseCatalogue catalogue;
    private final DefenseUnitDao units;
    private final UnitMaterializer materializer;
    private final Scheduler scheduler;

    public CapacityReconciler(DefenseService defense, DefenseRegistry registry,
                              DefenseCatalogue catalogue, DefenseUnitDao units,
                              UnitMaterializer materializer, Scheduler scheduler) {
        this.defense = Objects.requireNonNull(defense, "defense");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.units = Objects.requireNonNull(units, "units");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Brings one city's garrison in line with its Defense Capacity.
     *
     * @return how many units changed, which is zero on the overwhelmingly common pass
     */
    public CompletableFuture<Integer> reconcile(City city) {
        if (!catalogue.enabled()) {
            return CompletableFuture.completedFuture(0);
        }
        if (city.isDelinquent()) {
            // The upkeep sweep owns the flag while a city is in debt. Standing a unit up here
            // would hand a city an army it has already failed to pay for.
            return CompletableFuture.completedFuture(0);
        }

        int capacity = defense.capacity(city);
        List<DefenseCapacity.Placed> standing =
                registry.standing(city.id(), catalogue::pointsOf);
        List<DefenseCapacity.Placed> suspended =
                registry.suspended(city.id(), catalogue::pointsOf);

        List<Integer> down = DefenseCapacity.suspendToFit(standing, capacity);
        List<Integer> up = down.isEmpty()
                ? DefenseCapacity.restoreToFit(suspended, DefenseCapacity.spent(standing),
                        capacity)
                // Already over budget, so nothing suspended can possibly fit. Skipping the
                // second pass is not an optimisation: computing it against a spend that is
                // about to fall would stand a unit up and take another one down in one sweep.
                : List.of();

        if (down.isEmpty() && up.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        List<CompletableFuture<Integer>> writes = new ArrayList<>();
        for (int id : down) {
            writes.add(suspend(id));
        }
        for (int id : up) {
            writes.add(restore(id));
        }
        int changed = down.size() + up.size();
        if (!down.isEmpty()) {
            notifySuspended(city, down.size());
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> changed);
    }

    /** Every city, for startup and for {@code /ca reload}. */
    public CompletableFuture<Integer> reconcileAll(CityRegistry cities) {
        List<CompletableFuture<Integer>> all = new ArrayList<>();
        for (City city : cities.cities()) {
            all.add(reconcile(city));
        }
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> all.stream()
                        .mapToInt(future -> future.getNow(0)).sum());
    }

    private CompletableFuture<Integer> suspend(int unitId) {
        return registry.byId(unitId).map(unit -> units.setActive(unitId, false)
                .thenApply(rows -> {
                    registry.put(unit.withActive(false));
                    // On the server thread: it removes an entity. The health it is standing at
                    // is written on the way down, which is the whole reason this is not despawn.
                    scheduler.runOnMain(() -> materializer.dematerialize(
                            registry.byId(unitId).orElse(unit.withActive(false)),
                            System.currentTimeMillis()));
                    return rows;
                })).orElseGet(() -> CompletableFuture.completedFuture(0));
    }

    private CompletableFuture<Integer> restore(int unitId) {
        return registry.byId(unitId).map(unit -> units.setActive(unitId, true)
                .thenApply(rows -> {
                    registry.put(unit.withActive(true));
                    // No spawn here. SPEC 25.4 makes materialisation a question of whether
                    // anybody is near, and the sweep asks it every two seconds; spawning from
                    // here would put an entity in an empty chunk for nobody to see.
                    return rows;
                })).orElseGet(() -> CompletableFuture.completedFuture(0));
    }

    // ==================================================================================
    // Telling the city
    // ==================================================================================

    private BiConsumer<City, Integer> onSuspended = (city, count) -> { };

    /**
     * Who to tell when a garrison goes dark.
     *
     * <p>SPEC 30.4 has no key for this, and it needs one: a city's units silently switching off
     * is the silent failure SPEC 23.1's first principle exists to forbid, and the money is not
     * refunded, so a mayor who cannot see why has lost an army for no visible reason.
     */
    public void useNotifier(BiConsumer<City, Integer> notifier) {
        this.onSuspended = Objects.requireNonNull(notifier, "notifier");
    }

    private void notifySuspended(City city, int count) {
        onSuspended.accept(city, count);
    }
}
