package dev.civitas.core.defense;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.storage.dao.DefenseUnitDao;
import dev.civitas.storage.row.DefenseUnitRow;

/**
 * Every defense unit, in memory, and the link from a live entity back to its row.
 *
 * <p>Two maps, because there are two questions asked constantly and they are different ones.
 * "What does this city have" drives the menu, the caps and the upkeep sweep. "Which unit is
 * this entity" is asked on every damage event involving a mob, which must never be a database
 * read.
 */
public final class DefenseRegistry {

    private final DefenseUnitDao units;

    private final Map<Integer, DefenseUnit> byId = new ConcurrentHashMap<>();

    /** Live entity to unit id, rebuilt as chunks load rather than persisted. */
    private final Map<UUID, Integer> byEntity = new ConcurrentHashMap<>();

    public DefenseRegistry(DefenseUnitDao units) {
        this.units = Objects.requireNonNull(units, "units");
    }

    /** @return how many units exist, active or not */
    public CompletableFuture<Integer> loadAll() {
        return units.findAll().thenApply(rows -> {
            byId.clear();
            byEntity.clear();
            for (DefenseUnitRow row : rows) {
                byId.put(row.id(), DefenseUnit.from(row));
            }
            return byId.size();
        });
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    public Optional<DefenseUnit> byId(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The unit a live entity belongs to, if it is one of ours. */
    public Optional<DefenseUnit> byEntity(UUID entity) {
        Integer id = byEntity.get(entity);
        return id == null ? Optional.empty() : byId(id);
    }

    public List<DefenseUnit> of(int cityId) {
        List<DefenseUnit> found = new ArrayList<>();
        for (DefenseUnit unit : byId.values()) {
            if (unit.cityId() == cityId) {
                found.add(unit);
            }
        }
        found.sort((left, right) -> Integer.compare(left.id(), right.id()));
        return found;
    }

    /** Only the units that are actually standing, which is what the SPEC 12.4 cap counts. */
    public List<DefenseUnit> activeOf(int cityId) {
        return of(cityId).stream().filter(DefenseUnit::active).toList();
    }

    public int activeCount(int cityId) {
        return activeOf(cityId).size();
    }

    /** How many active units stand in one chunk, for the SPEC 12.4 per-chunk cap. */
    public int countInChunk(int cityId, String world, int chunkX, int chunkZ) {
        int count = 0;
        for (DefenseUnit unit : activeOf(cityId)) {
            if (unit.world().equals(world) && unit.chunkX() == chunkX
                    && unit.chunkZ() == chunkZ) {
                count++;
            }
        }
        return count;
    }

    /** What this city's standing units cost it a day, SPEC 12.2. */
    public BigDecimal dailyUpkeep(int cityId) {
        BigDecimal total = dev.civitas.storage.SqlDialect.zero();
        for (DefenseUnit unit : activeOf(cityId)) {
            total = total.add(unit.upkeep());
        }
        return total;
    }

    /** Units whose entity the plugin has not seen, so a chunk load knows what to respawn. */
    public List<DefenseUnit> unlinkedIn(String world, int chunkX, int chunkZ) {
        List<DefenseUnit> found = new ArrayList<>();
        for (DefenseUnit unit : byId.values()) {
            if (!unit.active() || !unit.world().equals(world)) {
                continue;
            }
            if (unit.chunkX() != chunkX || unit.chunkZ() != chunkZ) {
                continue;
            }
            if (!byEntity.containsValue(unit.id())) {
                found.add(unit);
            }
        }
        return found;
    }

    // ==================================================================================
    // Writing
    // ==================================================================================

    public void put(DefenseUnit unit) {
        byId.put(unit.id(), unit);
    }

    public void remove(int id) {
        byId.remove(id);
        byEntity.values().removeIf(value -> value == id);
    }

    /** Records that a live entity is this unit. */
    public void link(UUID entity, int unitId) {
        byEntity.put(entity, unitId);
    }

    /** Forgets an entity without touching its row, for a chunk unloading. */
    public void unlink(UUID entity) {
        byEntity.remove(entity);
    }

    public void forgetCity(int cityId) {
        List<DefenseUnit> mine = of(cityId);
        mine.forEach(unit -> remove(unit.id()));
    }

    public int total() {
        return byId.size();
    }

    public int linkedEntities() {
        return byEntity.size();
    }
}
