package dev.civitas.core.war;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.dao.WarDao;
import dev.civitas.storage.row.WarRow;

/**
 * Every war that is not finished, in memory.
 *
 * <p>Cache-first, like the other registries, and for a sharper reason than most: the question
 * "is this block inside a war zone" is asked on every block change of every player, and SPEC
 * 2.1 forbids a database round trip on that path. The database is where wars survive a
 * restart; this is where they are read.
 */
public final class WarRegistry {

    private final WarDao dao;

    private final java.util.Map<Integer, War> byId = new ConcurrentHashMap<>();

    /** City id to the wars it is party to. A city fights one war (SPEC 11.3) but may ally. */
    private final java.util.Map<Integer, List<Integer>> byCity = new ConcurrentHashMap<>();

    public WarRegistry(WarDao dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    /** Loads every unfinished war at startup, per SPEC 11.8.5. */
    public CompletableFuture<Integer> loadAll() {
        return dao.findByStates(List.of(WarState.DECLARED.key(), WarState.PREP.key(),
                        WarState.ACTIVE.key(), WarState.ROLLING_BACK.key(),
                        WarState.ROLLBACK_FAILED.key()))
                .thenApply(rows -> {
                    for (WarRow row : rows) {
                        remember(War.fromRow(row));
                    }
                    return rows.size();
                });
    }

    public void remember(War war) {
        byId.put(war.id(), war);
        index(war);
    }

    private void index(War war) {
        for (int cityId : allCities(war)) {
            byCity.computeIfAbsent(cityId, ignored -> new ArrayList<>()).add(war.id());
        }
    }

    /** Re-indexes a war after an ally joined it. */
    public void reindex(War war) {
        forgetIndex(war.id());
        index(war);
    }

    private static List<Integer> allCities(War war) {
        List<Integer> cities = new ArrayList<>();
        cities.add(war.attackerCityId());
        cities.add(war.defenderCityId());
        cities.addAll(war.attackerAllies());
        cities.addAll(war.defenderAllies());
        return cities;
    }

    public Optional<War> war(int warId) {
        return Optional.ofNullable(byId.get(warId));
    }

    public Collection<War> all() {
        return List.copyOf(byId.values());
    }

    /** Every war a city is party to. Usually none, occasionally one. */
    public List<War> warsOf(int cityId) {
        List<Integer> ids = byCity.get(cityId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<War> wars = new ArrayList<>(ids.size());
        for (int id : ids) {
            War war = byId.get(id);
            if (war != null) {
                wars.add(war);
            }
        }
        return wars;
    }

    /** The war this city is currently engaged in, if any. SPEC 11.3 allows at most one. */
    public Optional<War> engagedWarOf(int cityId) {
        return warsOf(cityId).stream().filter(war -> war.state().isEngaged()).findFirst();
    }

    /** Whether any war is in a state that logs damage. The listeners' first question. */
    public boolean isAnyWarActive() {
        for (War war : byId.values()) {
            if (war.state() == WarState.ACTIVE) {
                return true;
            }
        }
        return false;
    }

    /** Every active war whose zone covers a block, SPEC 17.4 case 51. */
    public List<War> activeWarsCovering(String world, int blockX, int blockZ) {
        List<War> covering = null;
        for (War war : byId.values()) {
            if (war.state() != WarState.ACTIVE) {
                continue;
            }
            if (war.zone().containsBlock(world, blockX, blockZ)) {
                if (covering == null) {
                    covering = new ArrayList<>(2);
                }
                covering.add(war);
            }
        }
        return covering == null ? List.of() : covering;
    }

    /** Drops a finished war once its rollback is done. */
    public void forget(int warId) {
        byId.remove(warId);
        forgetIndex(warId);
    }

    private void forgetIndex(int warId) {
        for (List<Integer> ids : byCity.values()) {
            ids.remove(Integer.valueOf(warId));
        }
    }

    public int size() {
        return byId.size();
    }
}
