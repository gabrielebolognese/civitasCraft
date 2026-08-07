package dev.civitas.core.city;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which cities are dormant, SPEC 17.1 case 2.
 *
 * <p>"Entire city inactive 60 days: city is flagged {@code dormant}, claims become unprotected
 * but are not removed. On any member login, protection restores instantly."
 *
 * <h2>Why this is a cache and not a column</h2>
 *
 * <p>Dormancy is a question about {@code players.last_seen}, and it is asked by
 * {@code ProtectionService} on <b>every block event</b> — the path SPEC 17.7 case 81 requires
 * to stay O(1) and SPEC 2.1 forbids from touching the database at all. So the sweep computes
 * the answer off the main thread and publishes it here, and the hot path does a set lookup.
 *
 * <p>Deriving it rather than storing a column also gets SPEC's "restores instantly" for free
 * in the direction that matters. A member logging in calls {@link #wake}, which is a set
 * removal and takes effect on the next block event — not on the next sweep, which could be an
 * hour away. A stored flag would need the same wake-up call anyway, plus a write, plus the
 * risk of the column and the truth disagreeing after a crash.
 *
 * <h2>Failing open</h2>
 *
 * <p>An empty cache means nothing is dormant, which means everything stays protected. That is
 * the safe direction: the failure mode of this class is a city keeping protection it should
 * have lost, never a city losing protection it should have kept. A sweep that never runs, a
 * database that will not answer, or a startup that has not finished all read the same way.
 */
public final class DormancyCache {

    private final Set<Integer> dormant = ConcurrentHashMap.newKeySet();

    /** Whether this city's claims are currently unprotected through inactivity. */
    public boolean isDormant(int cityId) {
        return dormant.contains(cityId);
    }

    /** Replaces the whole set, which is what a completed sweep produces. */
    public void replaceAll(Set<Integer> cityIds) {
        dormant.retainAll(cityIds);
        dormant.addAll(cityIds);
    }

    /**
     * Ends a city's dormancy immediately.
     *
     * <p>SPEC 17.1 case 2's "on any member login, protection restores instantly". Called from
     * the join listener rather than waited for: a player who comes back to defend their city
     * must not have to wait out a sweep interval while somebody digs through their walls.
     */
    public void wake(int cityId) {
        dormant.remove(cityId);
    }

    /** Marks one city dormant, for the sweep and for tests. */
    public void sleep(int cityId) {
        dormant.add(cityId);
    }

    /** How many cities are dormant, for {@code /ca perf} and the tests. */
    public int size() {
        return dormant.size();
    }

    /** Drops everything, so a reload or a disconnect fails open rather than stale. */
    public void clear() {
        dormant.clear();
    }
}
