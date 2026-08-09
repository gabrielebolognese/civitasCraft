package dev.civitas.core.defense;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.storage.dao.CityWardenDao;
import dev.civitas.storage.row.CityWardenRow;

/**
 * Which cities own a Warden, in memory, SPEC 28.
 *
 * <p>Cache-first like every other registry in the plugin (SPEC 2.3), and kept out of
 * {@link DefenseRegistry} on purpose. That registry answers {@code activeCount},
 * {@code countInChunk} and {@code dailyUpkeep}, and SPEC 28.2 excludes the Warden from the
 * Defense Capacity budget entirely — so the questions it is the answer to and the questions it
 * must not be part of are different questions, and separating the stores is what keeps them so.
 *
 * <p>The Warden's {@code defense_units} row is still in {@link DefenseRegistry}: it materialises,
 * leashes, takes damage and costs upkeep like any other unit. What lives here is only what makes
 * it the flagship — the one-per-city limit and SPEC 28.6's recovery clock.
 */
public final class WardenRegistry {

    private final CityWardenDao wardens;

    private final Map<Integer, CityWarden.Owned> byCity = new ConcurrentHashMap<>();

    public WardenRegistry(CityWardenDao wardens) {
        this.wardens = Objects.requireNonNull(wardens, "wardens");
    }

    /** @return how many cities own a Warden */
    public CompletableFuture<Integer> loadAll() {
        return wardens.findAll().thenApply(rows -> {
            byCity.clear();
            for (CityWardenRow row : rows) {
                byCity.put(row.cityId(), of(row));
            }
            return byCity.size();
        });
    }

    static CityWarden.Owned of(CityWardenRow row) {
        return new CityWarden.Owned(row.cityId(), row.unitId(), row.purchasedAt(),
                row.recoveringUntil());
    }

    public Optional<CityWarden.Owned> of(int cityId) {
        return Optional.ofNullable(byCity.get(cityId));
    }

    /**
     * Whether this city already has one, present or burrowed.
     *
     * <p>A recovering Warden still counts. SPEC 28.6 makes recovery a temporary absence rather
     * than a death — "it re-emerges at full health" — so a city that could buy a second one during
     * the six hours would end up with two, which SPEC 28.2 forbids in its own sentence.
     */
    public boolean owns(int cityId) {
        return byCity.containsKey(cityId);
    }

    /** The Warden a {@code defense_units} row belongs to, if any. */
    public Optional<CityWarden.Owned> byUnit(int unitId) {
        return byCity.values().stream().filter(owned -> owned.unitId() == unitId).findFirst();
    }

    /** Whether this unit id is somebody's Warden, which several listeners ask on every hit. */
    public boolean isWarden(int unitId) {
        return byUnit(unitId).isPresent();
    }

    public void put(CityWarden.Owned owned) {
        byCity.put(owned.cityId(), owned);
    }

    public void remove(int cityId) {
        byCity.remove(cityId);
    }

    public java.util.Collection<CityWarden.Owned> all() {
        return byCity.values();
    }

    public int total() {
        return byCity.size();
    }
}
