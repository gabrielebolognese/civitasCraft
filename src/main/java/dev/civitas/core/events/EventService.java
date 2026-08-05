package dev.civitas.core.events;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.dao.ServerEventDao;
import dev.civitas.storage.row.ServerEventRow;
import dev.civitas.util.Result;

/**
 * The running server event, SPEC 13.5.
 *
 * <h2>One at a time</h2>
 * SPEC 13.5 never says whether two events may overlap, and the answer here is no. Market Boom
 * and Tax Holiday together would compound into a sell multiplier nobody designed, and SPEC 4.1
 * is explicit that the economy's properties are deliberate. One event is also what a boss bar
 * and an announcement can describe honestly.
 *
 * <h2>Surviving a restart</h2>
 * Founders' Week runs seven days, so an event outliving a restart is the normal case rather
 * than the edge one. The row is written when the event starts and closed when it ends, and
 * {@link #load} decides which of those two happened while the server was down: an open row
 * still inside its window resumes, and one whose window has passed is closed on the spot with
 * its effects never having applied to anyone.
 */
public final class EventService {

    private final ServerEventDao events;
    private final ConfigManager configs;
    private final java.util.function.LongSupplier clock;

    /** What is running, or null. Read on every effect lookup, so it is never stale. */
    private final AtomicReference<ServerEvent> active = new AtomicReference<>();

    private final EventEffects effects;

    public EventService(ServerEventDao events, ConfigManager configs) {
        this(events, configs, System::currentTimeMillis);
    }

    /**
     * @param clock where "now" comes from when deciding whether a booked event has started.
     *              Injected rather than read from {@link System}, because a service that
     *              consults the wall clock behind its caller's back cannot be tested against
     *              a moved one, and every scheduling decision here is about time.
     */
    public EventService(ServerEventDao events, ConfigManager configs,
                        java.util.function.LongSupplier clock) {
        this.events = Objects.requireNonNull(events, "events");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.effects = new EventEffects(configs, this::running);
    }

    /** The multipliers every affected system reads. Never null, neutral when nothing runs. */
    public EventEffects effects() {
        return effects;
    }

    /**
     * The event in progress.
     *
     * <p>A pending event, announced but not yet started, is deliberately not returned: its
     * effects must not apply until it begins.
     */
    public Optional<ServerEvent> running() {
        ServerEvent event = active.get();
        if (event == null) {
            return Optional.empty();
        }
        return event.isPending(clock.getAsLong()) ? Optional.empty() : Optional.of(event);
    }

    /** What is running or about to, for the boss bar and the announcement. */
    public Optional<ServerEvent> current() {
        return Optional.ofNullable(active.get());
    }

    public boolean isEnabled() {
        return configs.get(ConfigFile.EVENTS).getBoolean("events.enabled", true);
    }

    // ==================================================================================
    // Lifecycle
    // ==================================================================================

    /**
     * Picks up whatever the last run left behind.
     *
     * @return the event that resumed, if any
     */
    public CompletableFuture<Optional<ServerEvent>> load(long now) {
        return events.findOpen().thenCompose(open -> {
            if (open.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            Optional<ServerEvent> parsed = ServerEvent.of(open.get());
            if (parsed.isEmpty()) {
                // A key this build does not know, from a downgrade. Close it rather than
                // leaving a row that blocks every future event from starting.
                return events.markEnded(open.get().id(), now).thenApply(ignored -> Optional.empty());
            }
            ServerEvent event = parsed.get();
            if (event.hasEnded(now)) {
                return events.markEnded(event.id(), now).thenApply(ignored -> Optional.empty());
            }
            active.set(event);
            return CompletableFuture.completedFuture(Optional.of(event));
        });
    }

    /**
     * Starts an event, or schedules one to start later.
     *
     * @param startsAt when its effects begin; a time in the future makes it pending, which is
     *                 how SPEC 13.5's advance announcement works
     */
    public CompletableFuture<Result<ServerEvent>> start(ServerEventType type, long startsAt) {
        Objects.requireNonNull(type, "type");
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(
                    Result.failure("EVENTS_DISABLED", "event.disabled"));
        }
        if (active.get() != null) {
            return CompletableFuture.completedFuture(
                    Result.failure("EVENT_RUNNING", "event.already-running"));
        }

        long endsAt = startsAt + effects.durationMillis(type);
        ServerEventRow row = new ServerEventRow(0, type.key(), startsAt, endsAt, null, false);

        return events.insert(row).thenApply(id -> {
            ServerEvent event = new ServerEvent(id, type, startsAt, endsAt, false);
            active.set(event);
            return Result.success(event);
        });
    }

    /** Records that the SPEC 13.5 advance warning has gone out, so a restart cannot repeat it. */
    public CompletableFuture<Void> markAnnounced() {
        ServerEvent event = active.get();
        if (event == null || event.announced()) {
            return CompletableFuture.completedFuture(null);
        }
        active.set(event.withAnnounced());
        return events.markAnnounced(event.id()).thenApply(ignored -> null);
    }

    /**
     * Ends the running event.
     *
     * <p>The cached reference is cleared first. Every effect is derived from it, so clearing it
     * is what actually takes the multipliers off; the write that follows only makes that
     * survive a restart. Doing it in this order means a failed write leaves the effects off
     * rather than stuck on.
     */
    public CompletableFuture<Result<ServerEvent>> stop(long now) {
        ServerEvent event = active.getAndSet(null);
        if (event == null) {
            return CompletableFuture.completedFuture(
                    Result.failure("NO_EVENT", "event.none"));
        }
        return events.markEnded(event.id(), now).thenApply(ignored -> Result.success(event));
    }

    /** When this event last ran, for the repeat cooldown. */
    public CompletableFuture<Optional<Long>> lastRunOf(ServerEventType type) {
        return events.findLatestOf(type.key())
                .thenApply(row -> row.map(ServerEventRow::startsAt));
    }
}
