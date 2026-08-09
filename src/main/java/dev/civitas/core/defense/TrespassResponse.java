package dev.civitas.core.defense;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPEC 26.2's phases: warning, then alert, then de-escalation.
 *
 * <p>Pure. It decides what should happen and to whom; the listener plays the sounds, sends the
 * messages and writes the audit rows. That keeps the sequence testable without a server, which
 * matters here more than usual because the sequence is the feature — SPEC 26.2's warning phase
 * exists so that "no player is ever killed without being told, in plain language, that they are
 * about to be", and a warning that can be skipped is worse than no warning at all.
 *
 * <h2>The cancel path is the important one</h2>
 *
 * <p>A player who takes the warning and walks away must not be attacked. That is the whole
 * point of having a warning rather than simply alerting on the third violation, so
 * {@link #promote} refuses for anybody who has left, and {@link #deEscalate} exists to be
 * called the moment they cross the border.
 */
public final class TrespassResponse {

    /** What a city is currently doing about one player. */
    public enum Phase {

        /** Nothing. Below the threshold, or long since calmed down. */
        NONE,

        /** SPEC 26.2 step 1: units roar and glow, the trespasser is told, nothing attacks. */
        WARNING,

        /** SPEC 26.2 step 2: units target this player and nobody else. */
        ALERTED
    }

    /** A city's response to one player, and when it next changes. */
    public record Response(Phase phase, long since, long until) {

        boolean expired(long now) {
            return until > 0 && now >= until;
        }
    }

    private final Map<Integer, Map<UUID, Response>> byCity = new ConcurrentHashMap<>();

    private final long warningMillis;
    private final long alertedMillis;

    public TrespassResponse(long warningMillis, long alertedMillis) {
        this.warningMillis = Math.max(0, warningMillis);
        this.alertedMillis = Math.max(0, alertedMillis);
    }

    /**
     * The threshold was crossed. Begins the warning phase.
     *
     * @return true when this actually started a warning, false when one was already running or
     *         the player is already alerted — a burst of violations warns once, not per swing
     */
    public boolean warn(int cityId, UUID player, long now) {
        Objects.requireNonNull(player, "player");
        Map<UUID, Response> city = byCity.computeIfAbsent(cityId, id -> new ConcurrentHashMap<>());

        Response current = city.get(player);
        if (current != null && !current.expired(now) && current.phase() != Phase.NONE) {
            return false;
        }
        city.put(player, new Response(Phase.WARNING, now, now + warningMillis));
        return true;
    }

    /**
     * The warning has run its course, SPEC 26.2 step 2.
     *
     * <p>"If the trespasser is still inside the city's claims when the warning ends, units enter
     * ALERTED against that player only." So this takes whether they are still there, and a
     * player who left is calmed rather than alerted.
     *
     * @return true when the player is now ALERTED
     */
    public boolean promote(int cityId, UUID player, boolean stillInsideClaims, long now) {
        Map<UUID, Response> city = byCity.get(cityId);
        if (city == null) {
            return false;
        }
        Response current = city.get(player);
        if (current == null || current.phase() != Phase.WARNING) {
            return false;
        }
        if (!stillInsideClaims) {
            // Took the warning and walked away. This is the outcome the warning phase exists
            // to produce, and it is the branch worth getting right.
            city.remove(player);
            return false;
        }
        city.put(player, new Response(Phase.ALERTED, now, now + alertedMillis));
        return true;
    }

    /** SPEC 26.2 step 3: leaving the claims calms everything down. */
    public void deEscalate(int cityId, UUID player) {
        Map<UUID, Response> city = byCity.get(cityId);
        if (city != null) {
            city.remove(player);
        }
    }

    /** What a city is doing about this player, resolving expiry on read. */
    public Phase phaseOf(int cityId, UUID player, long now) {
        Map<UUID, Response> city = byCity.get(cityId);
        if (city == null) {
            return Phase.NONE;
        }
        Response current = city.get(player);
        if (current == null) {
            return Phase.NONE;
        }
        if (current.expired(now)) {
            // SPEC 26.1: ALERTED "reverts to PASSIVE when it expires". Resolved on read so an
            // expiry is exact rather than up to one sweep late.
            city.remove(player);
            return Phase.NONE;
        }
        return current.phase();
    }

    /** Every player a city is currently alerted against. */
    public List<UUID> alertedIn(int cityId, long now) {
        Map<UUID, Response> city = byCity.get(cityId);
        if (city == null) {
            return List.of();
        }
        List<UUID> alerted = new ArrayList<>();
        for (Map.Entry<UUID, Response> entry : city.entrySet()) {
            if (!entry.getValue().expired(now) && entry.getValue().phase() == Phase.ALERTED) {
                alerted.add(entry.getKey());
            }
        }
        return alerted;
    }

    /** When the running warning for this player ends, if one is running. */
    public Optional<Long> warningEndsAt(int cityId, UUID player) {
        Map<UUID, Response> city = byCity.get(cityId);
        if (city == null) {
            return Optional.empty();
        }
        Response current = city.get(player);
        return current != null && current.phase() == Phase.WARNING
                ? Optional.of(current.until())
                : Optional.empty();
    }

    public void forget(UUID player) {
        byCity.values().forEach(city -> city.remove(player));
    }

    public void forgetCity(int cityId) {
        byCity.remove(cityId);
    }

    public long warningMillis() {
        return warningMillis;
    }

    public long alertedMillis() {
        return alertedMillis;
    }
}
