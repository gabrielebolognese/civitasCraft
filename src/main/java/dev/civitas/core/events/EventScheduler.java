package dev.civitas.core.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.util.Result;

/**
 * Decides when a SPEC 13.5 event runs, announces it, and ends it.
 *
 * <h2>SPEC gives no schedule</h2>
 * SPEC 13.5 calls these "automatic, scheduled, config-driven" and lists what each does, but
 * never says how often one fires or how the next is picked. So the schedule is this
 * implementation's, and it is deliberately dull: wait an interval, pick one at random by
 * weight, skip anything that ran too recently. Every number is in {@code events.yml} and none
 * of them is from the specification.
 *
 * <h2>Catching up</h2>
 * Like the contest cycle, this never asks whether a moment has just passed. It asks what
 * should be true now and makes it so. An event whose end passed while the server was down is
 * closed on the first tick rather than running for however long the outage lasted.
 */
public final class EventScheduler implements Runnable {

    private final EventService events;
    private final EventAnnouncer announcer;
    private final ConfigManager configs;
    private final Logger logger;
    private final LongSupplier clock;
    private final DoubleSupplier random;

    /** When the next event may start. Set after each one ends. */
    private long nextEventAt;

    public EventScheduler(EventService events, EventAnnouncer announcer, ConfigManager configs,
                          Logger logger, LongSupplier clock, DoubleSupplier random) {
        this.events = Objects.requireNonNull(events, "events");
        this.announcer = Objects.requireNonNull(announcer, "announcer");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Sets the clock running from a known point, after {@link EventService#load} has resumed. */
    public void begin(long now) {
        this.nextEventAt = now + intervalMillis();
    }

    @Override
    public void run() {
        try {
            tick(clock.getAsLong());
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "The event scheduler failed; it will retry next sweep.", e);
        }
    }

    /** Package-visible and taking the time, so a whole week can be tested by moving a number. */
    void tick(long now) {
        if (!events.isEnabled()) {
            return;
        }

        Optional<ServerEvent> current = events.current();
        if (current.isPresent()) {
            advance(current.get(), now);
            return;
        }

        if (now >= nextEventAt) {
            startSomething(now);
        }
    }

    /** Announces, starts and ends the event already on the books. */
    private void advance(ServerEvent event, long now) {
        if (event.hasEnded(now)) {
            events.stop(now).join();
            announcer.announceEnded(event);
            nextEventAt = now + intervalMillis();
            return;
        }

        if (!event.announced() && now >= announceAt(event)) {
            announcer.announceUpcoming(event, Math.max(0L, event.startsAt() - now));
            events.markAnnounced().join();
        }
    }

    private long announceAt(ServerEvent event) {
        return event.startsAt() - events.effects().announceLeadMillis(event.type());
    }

    /** Picks an event and books it for after its announcement window. */
    private void startSomething(long now) {
        Optional<ServerEventType> picked = pick(now);
        if (picked.isEmpty()) {
            // Everything is on cooldown. Look again after an interval rather than spinning.
            nextEventAt = now + intervalMillis();
            return;
        }

        ServerEventType type = picked.get();
        long startsAt = now + events.effects().announceLeadMillis(type);

        Result<ServerEvent> started = events.start(type, startsAt).join();
        if (started instanceof Result.Failure<ServerEvent> failure) {
            logger.warning("Could not start event " + type.key() + ": " + failure.reason());
            nextEventAt = now + intervalMillis();
            return;
        }

        ServerEvent event = started.orElseThrow();
        announcer.announceUpcoming(event, startsAt - now);
        events.markAnnounced().join();
    }

    /**
     * Chooses the next event by weight, skipping any that ran inside its own cooldown.
     *
     * <p>The cooldown is what stops a weighted draw handing out Double Upkeep three times in a
     * fortnight, which is the failure a pure random pick has and which players read as the
     * server being broken rather than unlucky.
     */
    Optional<ServerEventType> pick(long now) {
        List<ServerEventType> candidates = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0.0;

        for (ServerEventType type : ServerEventType.all()) {
            if (!isEnabled(type) || isOnCooldown(type, now)) {
                continue;
            }
            double weight = weightOf(type);
            if (weight <= 0.0) {
                continue;
            }
            candidates.add(type);
            weights.add(weight);
            total += weight;
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        double roll = random.getAsDouble() * total;
        for (int index = 0; index < candidates.size(); index++) {
            roll -= weights.get(index);
            if (roll <= 0.0) {
                return Optional.of(candidates.get(index));
            }
        }
        return Optional.of(candidates.get(candidates.size() - 1));
    }

    private boolean isOnCooldown(ServerEventType type, long now) {
        long cooldown = cooldownMillis(type);
        if (cooldown <= 0L) {
            return false;
        }
        Optional<Long> last = events.lastRunOf(type).join();
        return last.isPresent() && now - last.get() < cooldown;
    }

    // ==================================================================================
    // Configuration. None of these numbers is from SPEC 13.5, which gives no schedule.
    // ==================================================================================

    /** The gap between one event ending and the next being picked. */
    long intervalMillis() {
        return TimeUnit.HOURS.toMillis(configs.get(ConfigFile.EVENTS)
                .getLong("events.interval-hours", 12));
    }

    private double weightOf(ServerEventType type) {
        return events.effects().definition(type).getDouble("weight", 1.0);
    }

    private boolean isEnabled(ServerEventType type) {
        return events.effects().definition(type).getBoolean("enabled", true);
    }

    private long cooldownMillis(ServerEventType type) {
        double hours = events.effects().definition(type).getDouble("cooldown-hours",
                configs.get(ConfigFile.EVENTS).getDouble("events.default-cooldown-hours", 72));
        return (long) (hours * 3_600_000L);
    }

    /** What tells players an event is coming, running or over. */
    public interface EventAnnouncer {

        void announceUpcoming(ServerEvent event, long millisUntilStart);

        void announceEnded(ServerEvent event);

        /** An announcer that says nothing, for tests. */
        static EventAnnouncer silent() {
            return new EventAnnouncer() {
                @Override
                public void announceUpcoming(ServerEvent event, long millisUntilStart) {
                    // nothing
                }

                @Override
                public void announceEnded(ServerEvent event) {
                    // nothing
                }
            };
        }
    }
}
