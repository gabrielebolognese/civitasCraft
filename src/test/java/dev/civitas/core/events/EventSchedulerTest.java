package dev.civitas.core.events;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.CityTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The schedule of SPEC 13.5, which SPEC does not specify.
 *
 * <p>Every test moves a clock rather than waiting, and the draw is made deterministic by
 * handing the scheduler a fixed roll instead of a random one, so a weighted pick can be
 * asserted rather than hoped for.
 */
class EventSchedulerTest {

    @TempDir
    Path directory;

    private static final long NOW = 4_000_000_000L;
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);

    private CityTestSupport support;
    private EventService events;
    private EventScheduler scheduler;
    private long clock = NOW;
    private double roll;

    private final List<String> announced = new ArrayList<>();

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        // The service reads the same moved clock as the scheduler. Letting it consult the
        // wall clock instead is what made "announced before it applies" pass by accident.
        events = new EventService(support.daos.serverEvents(), support.configs, () -> clock);
        scheduler = new EventScheduler(events, recordingAnnouncer(), support.configs,
                CityTestSupport.quietLogger(), () -> clock, () -> roll);
        scheduler.begin(NOW);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private EventScheduler.EventAnnouncer recordingAnnouncer() {
        return new EventScheduler.EventAnnouncer() {
            @Override
            public void announceUpcoming(ServerEvent event, long millisUntilStart) {
                announced.add("upcoming:" + event.type().key());
            }

            @Override
            public void announceEnded(ServerEvent event) {
                announced.add("ended:" + event.type().key());
            }
        };
    }

    private void tickAt(long now) {
        clock = now;
        scheduler.run();
    }

    // ==================================================================================
    // Starting
    // ==================================================================================

    @Test
    @DisplayName("nothing starts before the interval has passed")
    void waitsForTheInterval() {
        tickAt(NOW);
        tickAt(NOW + HOUR);

        assertTrue(events.current().isEmpty());
        assertTrue(announced.isEmpty());
    }

    @Test
    @DisplayName("after the interval an event is booked and announced in advance")
    void booksAndAnnounces() {
        tickAt(NOW + scheduler.intervalMillis() + 1);

        ServerEvent booked = events.current().orElseThrow();
        assertEquals(1, announced.size());
        assertEquals("upcoming:" + booked.type().key(), announced.get(0));
        assertTrue(booked.announced(), "the announcement is recorded so a restart cannot repeat it");
    }

    @Test
    @DisplayName("an event is announced before it applies, per SPEC 13.5")
    void announcedBeforeItApplies() {
        long start = NOW + scheduler.intervalMillis() + 1;
        tickAt(start);

        ServerEvent booked = events.current().orElseThrow();
        assertTrue(booked.startsAt() > start, "it must not start the instant it is announced");
        assertTrue(events.running().isEmpty(), "and its effects must not apply yet");
    }

    // ==================================================================================
    // Ending
    // ==================================================================================

    @Test
    @DisplayName("an event ends on time and is announced as over")
    void endsOnTime() {
        tickAt(NOW + scheduler.intervalMillis() + 1);
        ServerEvent booked = events.current().orElseThrow();

        tickAt(booked.endsAt() + 1);

        assertTrue(events.current().isEmpty());
        assertTrue(announced.contains("ended:" + booked.type().key()));
    }

    @Test
    @DisplayName("an event whose end passed while the server was down is closed on the next tick")
    void catchesUpAnEndItMissed() {
        tickAt(NOW + scheduler.intervalMillis() + 1);
        ServerEvent booked = events.current().orElseThrow();

        // A week later. Whatever it was, it is over.
        tickAt(booked.endsAt() + TimeUnit.DAYS.toMillis(7));

        assertTrue(events.current().isEmpty());
        assertTrue(events.running().isEmpty());
    }

    @Test
    @DisplayName("the next event waits an interval after the last one ended")
    void spacesEventsOut() {
        tickAt(NOW + scheduler.intervalMillis() + 1);
        ServerEvent first = events.current().orElseThrow();
        long ended = first.endsAt() + 1;
        tickAt(ended);

        tickAt(ended + 1);
        assertTrue(events.current().isEmpty(), "a second event must not begin immediately");

        tickAt(ended + scheduler.intervalMillis() + 1);
        assertTrue(events.current().isPresent());
    }

    // ==================================================================================
    // Picking
    // ==================================================================================

    @Test
    @DisplayName("the draw only offers events that are not on cooldown")
    void cooldownExcludes() {
        // Founders' Week ships with a 30-day cooldown, so running one takes it out of the
        // draw for a month. Without this a weighted draw repeats rare events uncomfortably.
        await(events.start(ServerEventType.FOUNDERS_WEEK, NOW));
        await(events.stop(NOW + HOUR));

        List<ServerEventType> offered = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            roll = index / 200.0;
            scheduler.pick(NOW + 2 * HOUR).ifPresent(offered::add);
        }

        assertFalse(offered.contains(ServerEventType.FOUNDERS_WEEK));
        assertFalse(offered.isEmpty(), "the other seven are still available");
    }

    @Test
    @DisplayName("the cooldown expires and the event returns to the draw")
    void cooldownExpires() {
        await(events.start(ServerEventType.FOUNDERS_WEEK, NOW));
        await(events.stop(NOW + HOUR));

        long afterCooldown = NOW + TimeUnit.DAYS.toMillis(31);
        List<ServerEventType> offered = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            roll = index / 200.0;
            scheduler.pick(afterCooldown).ifPresent(offered::add);
        }

        assertTrue(offered.contains(ServerEventType.FOUNDERS_WEEK));
    }

    @Test
    @DisplayName("weight decides how often an event comes up")
    void weightsAreHonoured() {
        int boom = 0;
        int founders = 0;
        for (int index = 0; index < 1000; index++) {
            roll = index / 1000.0;
            Optional<ServerEventType> picked = scheduler.pick(NOW);
            if (picked.orElse(null) == ServerEventType.MARKET_BOOM) {
                boom++;
            } else if (picked.orElse(null) == ServerEventType.FOUNDERS_WEEK) {
                founders++;
            }
        }

        // Market Boom ships at weight 1.5 and Founders' Week at 0.3, so the week-long event
        // should be much rarer.
        assertTrue(boom > founders, "a heavier event should be drawn more often");
        assertTrue(founders > 0, "but a light one should still be reachable");
    }

    @Test
    @DisplayName("an event disabled in config is never drawn")
    void disabledEventIsSkipped() {
        support.configs.get(ConfigFile.EVENTS)
                .set(ServerEventType.INVASION.configPath() + ".enabled", false);

        for (int index = 0; index < 200; index++) {
            roll = index / 200.0;
            assertFalse(scheduler.pick(NOW).orElse(null) == ServerEventType.INVASION);
        }
    }

    @Test
    @DisplayName("with the whole system disabled the scheduler does nothing")
    void disabledDoesNothing() {
        support.configs.get(ConfigFile.EVENTS).set("events.enabled", false);

        tickAt(NOW + scheduler.intervalMillis() * 10);

        assertTrue(events.current().isEmpty());
        assertTrue(announced.isEmpty());
    }
}
