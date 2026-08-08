package dev.civitas.msg;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.storage.dao.PlayerToggleDao;
import dev.civitas.storage.row.PlayerToggleRow;
import dev.civitas.util.Result;

/**
 * Who wants to hear what, SPEC 23.6.
 *
 * <p>Cache-first, like every other registry in this plugin, and for a sharper reason than most:
 * this is consulted before <b>every</b> message the plugin sends, so it cannot be a database
 * read. A player's overrides are loaded on join and dropped on quit.
 *
 * <h2>The lock is enforced here, not in the command</h2>
 *
 * <p>SPEC 23.6 locks four categories on. {@link #set} refuses them, so there is no path — no
 * command, no GUI, no future admin tool — that can turn off a war warning or silence a treasury
 * withdrawal. {@link #wants} refuses them a second time, on the read side, so even a row written
 * into the table by hand cannot mute one. That belt and braces is deliberate: SPEC 23.5.6 calls
 * the withdrawal broadcast "the primary anti-fraud mechanism in the plugin", and a mechanism
 * with one guard is a mechanism with one bug between it and being off.
 *
 * <h2>Unknown players want everything</h2>
 *
 * <p>A player whose preferences have not loaded yet reads every default, which is on for
 * seventeen of the eighteen categories. Failing toward sending is right: a message that arrives
 * when somebody muted it is an annoyance, and a message that does not arrive because a cache was
 * cold can be a lost city.
 */
public final class TogglePreferences {

    private final PlayerToggleDao dao;
    private final Logger logger;

    /** Loaded overrides, per player. Absent means "not loaded", which reads as all defaults. */
    private final Map<UUID, Map<ToggleCategory, Boolean>> cache = new ConcurrentHashMap<>();

    public TogglePreferences(PlayerToggleDao dao, Logger logger) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // Asking
    // ==================================================================================

    /**
     * Whether this player should receive messages in this category.
     *
     * <p>Consulted on every send, so it is a map lookup and never touches storage.
     */
    public boolean wants(UUID player, ToggleCategory category) {
        if (category.locked()) {
            // Second guard. A row saying otherwise can only have arrived by hand, and SPEC
            // 23.6 does not permit it to mean anything.
            return true;
        }
        Map<ToggleCategory, Boolean> overrides = cache.get(player);
        if (overrides == null) {
            return category.defaultOn();
        }
        return overrides.getOrDefault(category, category.defaultOn());
    }

    /** Every category and its effective state, for {@code /toggle list}. */
    public Map<ToggleCategory, Boolean> all(UUID player) {
        Map<ToggleCategory, Boolean> states = new EnumMap<>(ToggleCategory.class);
        for (ToggleCategory category : ToggleCategory.values()) {
            states.put(category, wants(player, category));
        }
        return states;
    }

    /** Whether this player has changed this category from its default. */
    public boolean isOverridden(UUID player, ToggleCategory category) {
        Map<ToggleCategory, Boolean> overrides = cache.get(player);
        return overrides != null && overrides.containsKey(category);
    }

    // ==================================================================================
    // Changing
    // ==================================================================================

    /**
     * Sets one category, refusing the four SPEC 23.6 locks.
     *
     * <p>The cache is updated first and the write follows, so the next message respects the
     * change immediately rather than a round trip later. A failed write is logged and leaves the
     * cache as it is: the player asked, and re-reading their preference from a stale table on
     * the next join is a smaller harm than a setting that appears not to have taken.
     */
    public CompletableFuture<Result<Boolean>> set(UUID player, ToggleCategory category,
                                                  boolean enabled) {
        if (category.locked()) {
            return CompletableFuture.completedFuture(Result.failure("TOGGLE_LOCKED",
                    "toggle.locked",
                    Map.of("category", category.key())));
        }
        cache.computeIfAbsent(player, key -> new ConcurrentHashMap<>())
                .put(category, enabled);

        try {
            return dao.upsert(new PlayerToggleRow(player, category.key(), enabled))
                    .handle((written, error) -> {
                        if (error != null) {
                            logger.log(Level.WARNING, "Could not save a notification "
                                    + "preference; it holds for this session only.", error);
                        }
                        return Result.success(enabled);
                    });
        } catch (RuntimeException e) {
            // A closed pool throws from the call itself rather than failing the future.
            logger.log(Level.WARNING, "Could not save a notification preference; it holds for "
                    + "this session only.", e);
            return CompletableFuture.completedFuture(Result.success(enabled));
        }
    }

    /** Flips one category and reports what it became. */
    public CompletableFuture<Result<Boolean>> toggle(UUID player, ToggleCategory category) {
        return set(player, category, !wants(player, category));
    }

    // ==================================================================================
    // Lifecycle
    // ==================================================================================

    /** Loads one player's overrides. Called on join. */
    public CompletableFuture<Integer> load(UUID player) {
        try {
            return dao.findFor(player).handle((rows, error) -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Could not load notification preferences for "
                            + player + "; they will read every default.", error);
                    return 0;
                }
                Map<ToggleCategory, Boolean> overrides = new ConcurrentHashMap<>();
                for (PlayerToggleRow row : rows) {
                    ToggleCategory.byKey(row.category())
                            .filter(category -> !category.locked())
                            .ifPresent(category -> overrides.put(category, row.enabled()));
                }
                cache.put(player, overrides);
                return overrides.size();
            });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not load notification preferences for " + player
                    + "; they will read every default.", e);
            return CompletableFuture.completedFuture(0);
        }
    }

    /** Drops one player's overrides. Called on quit. */
    public void forget(UUID player) {
        cache.remove(player);
    }

    /** How many players are cached, for {@code /ca perf} and the tests. */
    public int cached() {
        return cache.size();
    }
}
