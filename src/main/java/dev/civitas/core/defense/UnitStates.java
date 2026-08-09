package dev.civitas.core.defense;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What state each unit is in right now, SPEC 26.1.
 *
 * <p>In memory only, and deliberately. A state is a fact about this moment — who provoked a
 * guard thirty seconds ago, whether a war is running — and none of it is worth surviving a
 * restart: SPEC 31 case 87 says every unit comes back DORMANT whatever it was doing, and an
 * alert that outlived the server would mean a player killed on login for something they did
 * before the crash.
 *
 * <p>Written by three milestones and read by one. M12a decides materialised or not, M12c writes
 * ALERTED when SPEC 26.2's trespass threshold is crossed, and M19 writes HOSTILE while a war
 * runs. {@link TargetingRule} is the only reader, which is what SPEC 30.1's "exactly one
 * handler" amounts to in practice.
 */
public final class UnitStates {

    /** A state and, for ALERTED, the one player it is about. */
    public record Current(UnitState state, UUID alertedTarget, long expiresAt) {

        public Current {
            Objects.requireNonNull(state, "state");
        }

        static Current of(UnitState state) {
            return new Current(state, null, 0L);
        }
    }

    private final Map<Integer, Current> states = new ConcurrentHashMap<>();

    /**
     * The state of a unit, defaulting to DORMANT.
     *
     * <p>An unknown unit is DORMANT rather than PASSIVE, which is the direction that attacks
     * nobody: SPEC 30.1 cancels on DORMANT, so a bookkeeping slip makes a guard harmless rather
     * than making it hostile to somebody it should have ignored.
     */
    public Current of(int unitId, long now) {
        Current current = states.get(unitId);
        if (current == null) {
            return Current.of(UnitState.DORMANT);
        }
        if (current.state() == UnitState.ALERTED && current.expiresAt() > 0
                && now >= current.expiresAt()) {
            // SPEC 26.1: ALERTED "reverts to PASSIVE when it expires". Resolved on read rather
            // than swept, so an expiry is exact rather than up to one tick late — and a unit
            // nobody is asking about costs nothing to expire.
            Current reverted = Current.of(UnitState.PASSIVE);
            states.put(unitId, reverted);
            return reverted;
        }
        return current;
    }

    public UnitState stateOf(int unitId, long now) {
        return of(unitId, now).state();
    }

    /** M12a: a unit that has just materialised is PASSIVE, which is the peacetime default. */
    public void materialized(int unitId) {
        states.put(unitId, Current.of(UnitState.PASSIVE));
    }

    /** M12a: a unit that has gone back to being a row. Any alert goes with it. */
    public void dematerialized(int unitId) {
        states.put(unitId, Current.of(UnitState.DORMANT));
    }

    /**
     * M12c: SPEC 26.2's trespass response, against one named player for a limited time.
     *
     * <p>Refused for a unit that is not standing, because an alert against a unit nobody can
     * see would fire the moment somebody walked into range — and SPEC 26.2's whole design is
     * that a trespasser is warned first.
     */
    public boolean alert(int unitId, UUID target, long until) {
        Objects.requireNonNull(target, "target");
        Current current = states.get(unitId);
        if (current == null || current.state() == UnitState.DORMANT) {
            return false;
        }
        states.put(unitId, new Current(UnitState.ALERTED, target, until));
        return true;
    }

    /** M12c: SPEC 26.2's de-escalation, when a trespasser leaves. */
    public void calm(int unitId) {
        states.computeIfPresent(unitId, (id, current) ->
                current.state() == UnitState.ALERTED ? Current.of(UnitState.PASSIVE) : current);
    }

    /** M19: a war is running and this unit is in its zone. */
    public void hostile(int unitId) {
        states.put(unitId, Current.of(UnitState.HOSTILE));
    }

    /** M19: the war ended. SPEC 30.2 case 96 reverts units before the rollback evacuation. */
    public void peace(int unitId) {
        states.computeIfPresent(unitId, (id, current) ->
                current.state() == UnitState.HOSTILE ? Current.of(UnitState.PASSIVE) : current);
    }

    /** Who this unit is alerted against, if anyone. */
    public Optional<UUID> alertedTarget(int unitId, long now) {
        Current current = of(unitId, now);
        return current.state() == UnitState.ALERTED
                ? Optional.ofNullable(current.alertedTarget())
                : Optional.empty();
    }

    public void forget(int unitId) {
        states.remove(unitId);
    }

    public int size() {
        return states.size();
    }
}
