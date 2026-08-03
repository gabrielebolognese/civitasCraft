package dev.civitas.core.outpost;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.storage.dao.OutpostDao;
import dev.civitas.storage.row.OutpostRow;

/**
 * Every outpost, in memory, keyed by city.
 *
 * <p>Cached for the same reason as claims and balances (SPEC 2.3): the daily upkeep sweep
 * counts outposts for every city, the Claims menu shows the count on every redraw, and a
 * teleport has to resolve a name. None of those should be a database read.
 */
public final class OutpostRegistry {

    private final OutpostDao outposts;

    private final Map<Integer, Outpost> byId = new ConcurrentHashMap<>();

    public OutpostRegistry(OutpostDao outposts) {
        this.outposts = Objects.requireNonNull(outposts, "outposts");
    }

    /** @return how many outposts exist */
    public CompletableFuture<Integer> loadAll() {
        return outposts.findAll().thenApply(rows -> {
            byId.clear();
            for (OutpostRow row : rows) {
                byId.put(row.id(), Outpost.from(row));
            }
            return byId.size();
        });
    }

    public Optional<Outpost> byId(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** A city's outposts, oldest first, which is the order they were paid for in. */
    public List<Outpost> of(int cityId) {
        List<Outpost> found = new ArrayList<>();
        for (Outpost outpost : byId.values()) {
            if (outpost.cityId() == cityId) {
                found.add(outpost);
            }
        }
        found.sort(Comparator.comparingLong(Outpost::createdAt)
                .thenComparingInt(Outpost::id));
        return found;
    }

    public int countOf(int cityId) {
        int count = 0;
        for (Outpost outpost : byId.values()) {
            if (outpost.cityId() == cityId) {
                count++;
            }
        }
        return count;
    }

    /** Finds one by the name a player typed. */
    public Optional<Outpost> byName(int cityId, String name) {
        for (Outpost outpost : byId.values()) {
            if (outpost.cityId() == cityId && outpost.isNamed(name)) {
                return Optional.of(outpost);
            }
        }
        return Optional.empty();
    }

    public void put(Outpost outpost) {
        byId.put(outpost.id(), outpost);
    }

    public void remove(int id) {
        byId.remove(id);
    }

    /** Drops a disbanded city's outposts from the cache. */
    public void forgetCity(int cityId) {
        byId.values().removeIf(outpost -> outpost.cityId() == cityId);
    }

    public int total() {
        return byId.size();
    }
}
