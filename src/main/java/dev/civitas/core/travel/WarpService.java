package dev.civitas.core.travel;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.storage.dao.WarpDao;
import dev.civitas.storage.row.WarpRow;
import dev.civitas.util.Result;
import org.bukkit.Location;

/**
 * SPEC 32.7's public warps.
 *
 * <p>Cache-first, like every other registry here: {@code /warp} and its tab completion are read
 * constantly and must not touch storage.
 *
 * <h2>Nothing in SPEC creates one</h2>
 *
 * <p>SPEC 32.7 lists {@code /warp <name>} as "admin-defined public warps" and no section
 * defines a command that defines one — not SPEC 9.4, not SPEC 22.7. {@code /ca warp set} ships
 * with this for the same reason {@code /toggle} shipped with the preference store: a warp system
 * with no way to make a warp is inert. Recorded in {@code OPEN_QUESTIONS.md}.
 *
 * <h2>Temporary warps</h2>
 *
 * <p>SPEC 40.1 needs them: a contest submission "generates a temporary public warp… available
 * for the duration of the voting window only". Rather than let that milestone build a parallel
 * warp system, a warp carries an optional expiry and an expired one is invisible to
 * {@link #find} the moment it passes, whether or not the sweep has run.
 */
public final class WarpService {

    private final WarpDao dao;
    private final Logger logger;

    /** Case-insensitive, because SPEC 32.7 gives players a name to type rather than an id. */
    private final Map<String, WarpRow> cache =
            new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);

    /** Names being written, so two admins cannot create the same warp in one tick. */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public WarpService(WarpDao dao, Logger logger) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    /** Loads every warp into the cache. Called once, on storage ready. */
    public CompletableFuture<Integer> loadAll() {
        try {
            return dao.findAll().handle((rows, error) -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Could not load warps; /warp will find none.",
                            error);
                    return 0;
                }
                cache.clear();
                rows.forEach(row -> cache.put(row.name(), row));
                return cache.size();
            });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not load warps; /warp will find none.", e);
            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * A warp by name, if it exists and has not expired.
     *
     * <p>Expiry is judged here rather than trusted to the sweep, so a contest warp stops working
     * the moment voting closes rather than whenever housekeeping next runs.
     */
    public Optional<WarpRow> find(String name, long now) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(name.trim()))
                .filter(warp -> !warp.hasExpired(now));
    }

    /** Every live warp, alphabetically, for {@code /warp list} and tab completion. */
    public List<WarpRow> all(long now) {
        return cache.values().stream().filter(warp -> !warp.hasExpired(now)).toList();
    }

    /** Just the names, for a completer. */
    public List<String> names(long now) {
        return all(now).stream().map(WarpRow::name).toList();
    }

    // ==================================================================================
    // Writing
    // ==================================================================================

    /**
     * Creates or moves a warp.
     *
     * @param expiresAt when it should vanish, or null for a permanent one
     */
    public CompletableFuture<Result<WarpRow>> set(String name, Location at, UUID by,
                                                  Long expiresAt, long now) {
        Result<String> valid = validateName(name);
        if (valid instanceof Result.Failure<String> failure) {
            return CompletableFuture.completedFuture(Result.propagate(failure));
        }
        if (at == null || at.getWorld() == null) {
            return CompletableFuture.completedFuture(
                    Result.failure("NO_LOCATION", "warp.no-location"));
        }

        String clean = valid.orElseThrow();
        WarpRow row = new WarpRow(clean, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                at.getYaw(), at.getPitch(), by, now, expiresAt);

        synchronized (locks.computeIfAbsent(clean.toLowerCase(java.util.Locale.ROOT),
                key -> new Object())) {
            cache.put(clean, row);
        }
        try {
            return dao.upsert(row).handle((written, error) -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Could not save warp " + clean
                            + "; it holds until the next restart.", error);
                }
                return Result.success(row);
            });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not save warp " + clean
                    + "; it holds until the next restart.", e);
            return CompletableFuture.completedFuture(Result.success(row));
        }
    }

    /** Removes a warp. */
    public CompletableFuture<Result<String>> delete(String name) {
        Optional<WarpRow> existing = find(name, 0L);
        if (existing.isEmpty() && (name == null || !cache.containsKey(name.trim()))) {
            return CompletableFuture.completedFuture(Result.failure("UNKNOWN_WARP",
                    "warp.unknown", Map.of("name", String.valueOf(name))));
        }
        String actual = existing.map(WarpRow::name).orElse(name.trim());
        cache.remove(actual);
        try {
            return dao.delete(actual).handle((removed, error) -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Could not delete warp " + actual
                            + "; it will return on the next restart.", error);
                }
                return Result.success(actual);
            });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not delete warp " + actual, e);
            return CompletableFuture.completedFuture(Result.success(actual));
        }
    }

    /** Drops expired warps from the cache and the table. */
    public CompletableFuture<Integer> sweepExpired(long now) {
        List<String> gone = cache.values().stream()
                .filter(warp -> warp.hasExpired(now))
                .map(WarpRow::name)
                .toList();
        gone.forEach(cache::remove);
        try {
            return dao.deleteExpired(now).exceptionally(error -> 0);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(gone.size());
        }
    }

    /** How many warps are held, for the tests and {@code /ca perf}. */
    public int count() {
        return cache.size();
    }

    // ==================================================================================
    // Names
    // ==================================================================================

    /**
     * Whether a name is usable, and trimmed if so.
     *
     * <p>Restricted to what a player can type without quoting and what fits the column. A warp
     * name reaches tab completion and a chat message, so it stays alphanumeric: nothing here is
     * player-supplied today, but SPEC 40.1's contest warps will be generated from city names.
     */
    public static Result<String> validateName(String name) {
        if (name == null || name.isBlank()) {
            return Result.failure("NAME_EMPTY", "warp.name-empty");
        }
        String clean = name.trim();
        if (clean.length() > 32) {
            return Result.failure("NAME_TOO_LONG", "warp.name-too-long",
                    Map.of("max", "32"));
        }
        if (!clean.matches("[A-Za-z0-9_-]+")) {
            return Result.failure("NAME_INVALID", "warp.name-invalid",
                    Map.of("name", clean));
        }
        return Result.success(clean);
    }
}
