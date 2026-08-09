package dev.civitas.core.defense;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPEC 27.8's post-placement window: "Units placed during ACTIVE war cost double and enter a
 * 60-second inactive period before functioning."
 *
 * <p>The double price makes wartime defense expensive; this makes it <em>late</em>, which is the
 * half that decides a fight. Without it a defender losing a push could buy a Colossus, drop it
 * into the melee and have it swinging on the same tick, and SPEC 27.8's stated intent — "defense
 * must be planned in PREP" — would be a price rather than a rule.
 *
 * <h2>Why a timestamp rather than a fifth state</h2>
 *
 * <p>SPEC 26.1 defines exactly four states and SPEC 30.1 is unusually prescriptive about the
 * order its table is read in. A fifth state would mean reordering that table; a per-unit
 * timestamp is an extra cancel at the top of it, which is an addition rather than a change.
 *
 * <h2>In memory, and it does not survive a restart</h2>
 *
 * <p>SPEC says nothing either way. Sixty seconds of state is not worth a schema column, and the
 * failure mode of losing it — a unit arming early after a crash that has already interrupted the
 * fight far more than this could — is smaller than the migration.
 */
public final class UnitCommissioning {

    private final Map<Integer, Long> readyAt = new ConcurrentHashMap<>();

    /** Starts the window for a unit placed mid-war. */
    public void commission(int unitId, long ready) {
        readyAt.put(unitId, ready);
    }

    /**
     * Whether this unit is still warming up.
     *
     * <p>An unknown unit is <b>ready</b>, not warming up: every unit placed in peacetime never
     * enters here at all, and a bookkeeping slip must not leave a city's garrison inert.
     */
    public boolean isWarmingUp(int unitId, long now) {
        Long ready = readyAt.get(unitId);
        if (ready == null) {
            return false;
        }
        if (now >= ready) {
            // Resolved on read, so an expiry is exact rather than up to a sweep late, and a unit
            // nobody is asking about costs nothing to arm.
            readyAt.remove(unitId);
            return false;
        }
        return true;
    }

    public void forget(int unitId) {
        readyAt.remove(unitId);
    }

    public int size() {
        return readyAt.size();
    }
}
