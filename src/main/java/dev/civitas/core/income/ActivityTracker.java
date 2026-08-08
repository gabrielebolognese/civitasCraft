package dev.civitas.core.income;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;

/**
 * The SPEC 4.2.1 anti-AFK check.
 *
 * <p>One interval, one set of distinct action kinds, and a threshold. That is the whole idea,
 * and the reason it works is worth writing down, because it is easy to "improve" it into
 * something worse.
 *
 * <h2>Why distinct kinds, and not a count of events</h2>
 * A water clock triggers movement events forever. A jump-clicker triggers attack events
 * forever. A macro on one key triggers whatever that key does, forever. Every one of those
 * produces an unbounded <em>number</em> of events and exactly one <em>kind</em>, so any
 * threshold on the count is defeated by leaving the machine on for longer, and a threshold on
 * distinct kinds is not defeated at all. SPEC 4.2.1 picks three, which a farmer clears
 * without noticing (walking, breaking, placing) and a machine does not clear at all.
 *
 * <h2>Why movement is a distance, not an event</h2>
 * Standing on a boat in flowing water fires a move event every tick while going nowhere.
 * Requiring 32 blocks of cumulative distance costs a real player nothing and costs the boat
 * everything.
 *
 * <p>This class is pure: it holds counters and answers questions. What feeds it is
 * {@code ActivityListener}, and what asks it is {@link StipendTask}.
 */
public final class ActivityTracker {

    private final ConfigManager configs;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public ActivityTracker(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    // ==================================================================================
    // Recording
    // ==================================================================================

    /** Records one action. Repeating the same kind adds nothing, by design. */
    public void record(UUID player, ActivityKind kind) {
        record(player, kind, System.currentTimeMillis());
    }

    /**
     * Records one action at a stated time.
     *
     * <p>The time is what SPEC 21.4 F11 needs: "the 3 actions must be of 3 different types,
     * <b>and must occur in at least 3 different minutes</b> of the 15-minute window. A single
     * repeating macro produces one action type and fails." The distinct-types half was already
     * here; without the minute, a macro that fires three different actions in one burst passes.
     */
    public void record(UUID player, ActivityKind kind, long now) {
        Session session = session(player);
        session.kinds.add(kind);
        session.minutes.add(now / 60_000L);
    }

    /**
     * Records movement.
     *
     * <p>Accumulates until the configured distance is reached, then counts as one
     * {@link ActivityKind#MOVED} for the rest of the interval.
     *
     * @param blocks distance covered since the last call
     */
    public void recordMovement(UUID player, double blocks) {
        if (blocks <= 0) {
            return;
        }
        recordMovement(player, blocks, System.currentTimeMillis());
    }

    /** Records movement at a stated time, for the SPEC 21.4 F11 minute. */
    public void recordMovement(UUID player, double blocks, long now) {
        if (blocks <= 0) {
            return;
        }
        Session session = session(player);
        session.movedBlocks += blocks;
        if (session.movedBlocks >= requiredDistance()) {
            session.kinds.add(ActivityKind.MOVED);
            session.minutes.add(now / 60_000L);
        }
    }

    // ==================================================================================
    // Asking
    // ==================================================================================

    /** How many distinct kinds this player has managed in the current interval. */
    public int distinctKinds(UUID player) {
        Session session = sessions.get(player);
        return session == null ? 0 : session.kinds.size();
    }

    public Set<ActivityKind> kinds(UUID player) {
        Session session = sessions.get(player);
        return session == null ? EnumSet.noneOf(ActivityKind.class)
                : EnumSet.copyOf(session.kinds.isEmpty()
                        ? EnumSet.noneOf(ActivityKind.class) : session.kinds);
    }

    /**
     * Whether this player counts as active for the interval just ended.
     *
     * <p>Both halves of SPEC 21.4 F11: enough distinct kinds, spread over enough distinct
     * minutes. Either alone is passable by a macro — three key presses in one second are three
     * kinds, and fifteen minutes of one repeated action is fifteen minutes.
     */
    public boolean wasActive(UUID player) {
        return distinctKinds(player) >= requiredActions()
                && distinctMinutes(player) >= requiredDistinctMinutes();
    }

    /** In how many separate minutes this player did something, SPEC 21.4 F11. */
    public int distinctMinutes(UUID player) {
        Session session = sessions.get(player);
        return session == null ? 0 : session.minutes.size();
    }

    /** Distance accumulated so far, for diagnostics and tests. */
    public double movedBlocks(UUID player) {
        Session session = sessions.get(player);
        return session == null ? 0.0 : session.movedBlocks;
    }

    // ==================================================================================
    // Rolling over
    // ==================================================================================

    /**
     * Ends the interval for one player and starts a fresh one.
     *
     * @return whether they were active in the interval that just ended
     */
    public boolean rollOver(UUID player) {
        boolean active = wasActive(player);
        sessions.remove(player);
        return active;
    }

    /** Forgets a player entirely, on quit. */
    public void forget(UUID player) {
        sessions.remove(player);
    }

    public void clear() {
        sessions.clear();
    }

    public int tracked() {
        return sessions.size();
    }

    // ==================================================================================
    // Config
    // ==================================================================================

    /** SPEC 4.2.1: {@code economy.income.stipend.required-actions}, default three. */
    public int requiredActions() {
        return configs.get(ConfigFile.ECONOMY).getInt("income.stipend.required-actions", 3);
    }

    /**
     * SPEC 21.11 {@code anti-abuse.stipend-required-distinct-minutes}, default three.
     *
     * <p>SPEC 21.11 also names {@code stipend-required-distinct-action-types}, which is the
     * key {@code income.stipend.required-actions} has held since M9 with the same default.
     * Shipping both would be the twin the config sweep found three of, where the file offers
     * one name and the code reads another, so the older name is kept and the new one is not
     * shipped. The value is a config key either way, which is what the rule requires.
     */
    public int requiredDistinctMinutes() {
        return configs.get(ConfigFile.ECONOMY)
                .getInt("anti-abuse.stipend-required-distinct-minutes", 3);
    }

    /** SPEC 4.2.1: 32 blocks of cumulative movement counts as one action. */
    public double requiredDistance() {
        return configs.get(ConfigFile.ECONOMY)
                .getDouble("income.stipend.move-distance-blocks", 32.0);
    }

    private Session session(UUID player) {
        return sessions.computeIfAbsent(player, key -> new Session());
    }

    /** One player's interval. */
    private static final class Session {
        private final Set<ActivityKind> kinds =
                java.util.Collections.synchronizedSet(EnumSet.noneOf(ActivityKind.class));
        /** Which minutes saw activity. Bounded by the interval, so at most a handful. */
        private final Set<Long> minutes =
                java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        private volatile double movedBlocks;
    }
}
