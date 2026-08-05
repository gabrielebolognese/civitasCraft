package dev.civitas.core.events;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import dev.civitas.core.city.CityTestSupport;
import dev.civitas.storage.row.ServerEventRow;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Starting, stopping and resuming a SPEC 13.5 event.
 *
 * <p>The restart cases are the ones worth having. Founders' Week runs seven days, so an event
 * outliving a restart is the normal case rather than the edge one, and the two ways that can
 * go wrong are opposite: an event that should have ended still applying its effects, and one
 * still inside its window being silently cancelled.
 */
class EventServiceTest {

    @TempDir
    Path directory;

    private static final long NOW = 3_000_000_000L;
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);

    private CityTestSupport support;
    private EventService events;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        events = new EventService(support.daos.serverEvents(), support.configs);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    @Test
    @DisplayName("a started event is running and its effects apply")
    void startApplies() {
        Result<ServerEvent> started = await(events.start(ServerEventType.MARKET_BOOM, NOW));

        assertTrue(started.isSuccess(), reasonOf(started));
        assertTrue(events.running().isPresent());
        assertEquals(0, new BigDecimal("1.400000")
                .compareTo(events.effects().sellPriceMultiplier()));
    }

    @Test
    @DisplayName("an event scheduled for later does not apply yet")
    void pendingDoesNotApply() {
        // This is how SPEC 13.5's advance announcement works: the row exists and the players
        // have been told, but nothing has changed yet.
        await(events.start(ServerEventType.MARKET_BOOM, System.currentTimeMillis() + HOUR));

        assertTrue(events.current().isPresent(), "it is on the books");
        assertTrue(events.running().isEmpty(), "but it is not running");
        assertEquals(0, BigDecimal.ONE.compareTo(events.effects().sellPriceMultiplier()));
    }

    @Test
    @DisplayName("only one event runs at a time")
    void oneAtATime() {
        await(events.start(ServerEventType.MARKET_BOOM, NOW));

        Result<ServerEvent> second = await(events.start(ServerEventType.TAX_HOLIDAY, NOW));

        assertEquals("EVENT_RUNNING", reasonOf(second));
    }

    @Test
    @DisplayName("stopping takes the effects off immediately")
    void stopClears() {
        await(events.start(ServerEventType.MARKET_BOOM, NOW));

        await(events.stop(NOW + HOUR));

        assertTrue(events.running().isEmpty());
        assertEquals(0, BigDecimal.ONE.compareTo(events.effects().sellPriceMultiplier()));
    }

    @Test
    @DisplayName("stopping twice writes nothing the second time")
    void stopIsIdempotent() {
        await(events.start(ServerEventType.MARKET_BOOM, NOW));
        await(events.stop(NOW + HOUR));

        assertEquals("NO_EVENT", reasonOf(await(events.stop(NOW + 2 * HOUR))));
    }

    @Test
    @DisplayName("an event still inside its window resumes after a restart")
    void resumesAcrossRestart() {
        await(events.start(ServerEventType.FOUNDERS_WEEK, NOW));

        // A fresh service over the same database is what a restart looks like from here.
        EventService restarted = new EventService(support.daos.serverEvents(), support.configs);
        Optional<ServerEvent> resumed = await(restarted.load(NOW + HOUR));

        assertTrue(resumed.isPresent());
        assertEquals(ServerEventType.FOUNDERS_WEEK, resumed.orElseThrow().type());
        assertTrue(restarted.effects().isCityCreationFree());
    }

    @Test
    @DisplayName("an event whose window passed while the server was down is closed, not resumed")
    void closesWhatItMissed() {
        await(events.start(ServerEventType.MARKET_BOOM, NOW));
        long afterItShouldHaveEnded = NOW + TimeUnit.DAYS.toMillis(3);

        EventService restarted = new EventService(support.daos.serverEvents(), support.configs);
        Optional<ServerEvent> resumed = await(restarted.load(afterItShouldHaveEnded));

        assertTrue(resumed.isEmpty(), "a six-hour event must not still be running three days on");
        assertEquals(0, BigDecimal.ONE.compareTo(restarted.effects().sellPriceMultiplier()));
        assertTrue(await(support.daos.serverEvents().findOpen()).isEmpty(),
                "and it must be closed on disk, or the next boot faces the same row");
    }

    @Test
    @DisplayName("a row naming an event this build does not know is closed rather than blocking")
    void unknownEventKeyIsClosed() {
        // What a downgrade leaves behind. Left open, it would block every future event.
        await(support.daos.serverEvents().insert(
                new ServerEventRow(0, "something-a-later-version-added", NOW, NOW + HOUR,
                        null, false)));

        EventService restarted = new EventService(support.daos.serverEvents(), support.configs);

        assertTrue(await(restarted.load(NOW)).isEmpty());
        assertTrue(await(support.daos.serverEvents().findOpen()).isEmpty());
        assertTrue(await(restarted.start(ServerEventType.MARKET_BOOM, NOW)).isSuccess());
    }

    @Test
    @DisplayName("the announcement flag survives a restart, so nobody is told twice")
    void announcementIsRecorded() {
        await(events.start(ServerEventType.MARKET_BOOM, NOW + HOUR));
        await(events.markAnnounced());

        EventService restarted = new EventService(support.daos.serverEvents(), support.configs);
        ServerEvent resumed = await(restarted.load(NOW)).orElseThrow();

        assertTrue(resumed.announced());
    }

    @Test
    @DisplayName("the last run of an event is remembered, for the repeat cooldown")
    void lastRunIsQueryable() {
        assertTrue(await(events.lastRunOf(ServerEventType.MARKET_BOOM)).isEmpty());

        await(events.start(ServerEventType.MARKET_BOOM, NOW));

        assertEquals(NOW, await(events.lastRunOf(ServerEventType.MARKET_BOOM)).orElseThrow());
    }

    @Test
    @DisplayName("a disabled event system starts nothing")
    void disabledStartsNothing() {
        support.configs.get(dev.civitas.config.ConfigFile.EVENTS).set("events.enabled", false);

        Result<ServerEvent> result = await(events.start(ServerEventType.MARKET_BOOM, NOW));

        assertEquals("EVENTS_DISABLED", reasonOf(result));
        assertFalse(events.isEnabled());
    }
}
