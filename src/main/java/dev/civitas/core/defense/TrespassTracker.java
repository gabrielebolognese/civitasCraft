package dev.civitas.core.defense;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPEC 26.2's violation counter: a sliding window, not a running total.
 *
 * <p>SPEC 26.2 says "Violations decay. The counter is a sliding window, not a running total,"
 * and the difference is the whole feature. A running total means a player who bumps a locked
 * door on Monday and again on Friday is treated as a raider; a sliding window means three
 * things inside thirty seconds, which is what somebody testing a city's defences actually does
 * and what somebody lost in it does not.
 *
 * <p>Pure and per city, so the rule can be tested without a server and so one city's patience
 * has nothing to do with another's.
 */
public final class TrespassTracker {

    /** A violation, and when. */
    private record Strike(long at) {
    }

    private final Map<Integer, Map<UUID, Deque<Strike>>> byCity = new ConcurrentHashMap<>();

    private final int threshold;
    private final long windowMillis;

    public TrespassTracker(int threshold, long windowMillis) {
        this.threshold = Math.max(1, threshold);
        this.windowMillis = Math.max(0, windowMillis);
    }

    /**
     * Records one violation and says whether it was the one that crossed the line.
     *
     * <p>Returns true exactly once per burst: the strikes are cleared when the threshold is
     * met, so a player who keeps going does not re-trigger the warning every swing. SPEC 26.2's
     * response has phases with their own timers, and restarting them on every block break would
     * mean a trespasser never actually reaching the alerted phase.
     */
    public boolean record(int cityId, UUID player, long now) {
        Objects.requireNonNull(player, "player");
        Deque<Strike> strikes = byCity
                .computeIfAbsent(cityId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(player, id -> new ArrayDeque<>());

        synchronized (strikes) {
            expire(strikes, now);
            strikes.addLast(new Strike(now));
            if (strikes.size() >= threshold) {
                strikes.clear();
                return true;
            }
            return false;
        }
    }

    /** How many violations are currently inside the window. */
    public int count(int cityId, UUID player, long now) {
        Map<UUID, Deque<Strike>> city = byCity.get(cityId);
        if (city == null) {
            return 0;
        }
        Deque<Strike> strikes = city.get(player);
        if (strikes == null) {
            return 0;
        }
        synchronized (strikes) {
            expire(strikes, now);
            return strikes.size();
        }
    }

    /** Drops everything remembered about a player in a city, for de-escalation. */
    public void clear(int cityId, UUID player) {
        Map<UUID, Deque<Strike>> city = byCity.get(cityId);
        if (city != null) {
            city.remove(player);
        }
    }

    public void forget(UUID player) {
        byCity.values().forEach(city -> city.remove(player));
    }

    /** Drops a whole city, for a disband. */
    public void forgetCity(int cityId) {
        byCity.remove(cityId);
    }

    private void expire(Deque<Strike> strikes, long now) {
        while (!strikes.isEmpty() && now - strikes.peekFirst().at() >= windowMillis) {
            strikes.removeFirst();
        }
    }

    public int threshold() {
        return threshold;
    }

    public long windowMillis() {
        return windowMillis;
    }
}
