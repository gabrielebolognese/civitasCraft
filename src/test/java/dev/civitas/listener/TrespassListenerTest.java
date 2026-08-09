package dev.civitas.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.admin.AuditService;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityNameValidator;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.city.CityService;
import dev.civitas.core.city.Placement;
import dev.civitas.core.claim.ClaimCostEngine;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.claim.ClaimService;
import dev.civitas.core.defense.DefenseRegistry;
import dev.civitas.core.defense.TrespassResponse;
import dev.civitas.core.defense.TrespassService;
import dev.civitas.core.defense.UnitGlow;
import dev.civitas.core.defense.UnitStates;
import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.PlayerAccountService;
import dev.civitas.lang.LangManager;
import dev.civitas.msg.Messenger;
import dev.civitas.msg.TogglePreferences;
import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.DatabaseSettings;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.dao.DaoRegistry;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.EventBus;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * SPEC 26.2's clocks, which the pure classes cannot see.
 *
 * <p>{@link TrespassService} decides what a city is doing; nothing in it makes time pass. Three
 * properties live only here, and all three are the sort that would be silently wrong:
 *
 * <ul>
 *   <li>{@link #leavingDuringTheWarningIsSafe} — SPEC 26.2's warning phase exists so that "no
 *       player is ever killed without being told", and that is worth nothing unless the timer
 *       asks where the player is <em>when it fires</em>.
 *   <li>{@link #deEscalationTakesTheConfiguredTime} — SPEC 26.2 step 3 grants ten seconds, and
 *       the key that says so was read by a getter nobody called until this milestone.
 *   <li>{@link #quittingDoesNotClearAnAlert} — SPEC 30.2 case 94. Every other listener in this
 *       package clears per-player state on quit, and copying that line breaks the one case that
 *       requires it not to be cleared.
 * </ul>
 */
class TrespassListenerTest {

    private static final String WORLD = "world";

    @TempDir
    Path directory;

    private ServerMock server;
    private WorldMock world;
    private DatabaseManager db;
    private Plugin plugin;

    private TrespassService trespass;
    private TrespassListener listener;
    private UnitGlow glow;

    private City city;
    private PlayerMock stranger;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld(WORLD);
        plugin = MockBukkit.createMockPlugin("CivitasTest");

        Logger quiet = Logger.getLogger("trespass-listener-test");
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        ConfigManager configs = new ConfigManager(
                PluginResources.ofClasspath(directory.resolve("plugin").toFile(), quiet));
        configs.loadAll();
        // The debounce is asserted in TrespassServiceTest. Here it would only mean sleeping
        // between strikes, because these tests run against the wall clock.
        configs.get(ConfigFile.DEFENSE).set("trespass.violation-cooldown-ms", 0);

        LangManager lang = new LangManager(
                PluginResources.ofClasspath(directory.resolve("plugin").toFile(), quiet), configs);
        lang.load();

        DatabaseSettings settings = new DatabaseSettings(SqlDialect.SQLITE,
                "jdbc:sqlite:" + directory.resolve("trespass.db").toAbsolutePath(),
                "", "", 2, 5000, "WAL", Long.MAX_VALUE, false, 6, 28);
        db = new DatabaseManager(quiet, settings, () -> false);
        db.open();

        DaoRegistry daos = new DaoRegistry(db);
        CityRegistry cities = new CityRegistry(daos);
        ClaimRegistry claimRegistry = new ClaimRegistry(daos.claims());
        PlayerAccountService accounts =
                new PlayerAccountService(db, daos.players(), daos.ledger(), configs);
        ClaimService claims = new ClaimService(db, daos, cities, claimRegistry,
                new ClaimCostEngine(configs), configs, Scheduler.direct(), EventBus.noop());
        EconomyService economy = new EconomyService(db, daos.players(), daos.ledger(),
                configs, quiet);
        CityService cityService = new CityService(db, daos, cities, configs,
                new CityNameValidator(configs), economy,
                claims, accounts, Scheduler.direct(), EventBus.noop());

        UnitStates states = new UnitStates();
        DefenseRegistry units = new DefenseRegistry(daos.defenseUnits());
        trespass = new TrespassService(configs, cities, claimRegistry, states, units);

        glow = new UnitGlow(units);
        // MockBukkit implements neither setGlowing nor scoreboard team registration, and an
        // unimplemented call aborts a test as a SKIP rather than a failure. The glow's own
        // bookkeeping is still exercised; only the paint is replaced.
        glow.usePaint((entity, cityId, colour, on) -> { });

        Messenger messenger = new Messenger(lang, configs,
                new TogglePreferences(daos.playerToggles(), quiet));
        listener = new TrespassListener(plugin, trespass, messenger, glow,
                new AuditService(daos.auditLog(), quiet));
        trespass.useEffects(listener::on);

        PlayerMock mayor = server.addPlayer("Romulus");
        mayor.setOp(false);
        stranger = server.addPlayer("Hostis");
        stranger.setOp(false);
        seedPlayer(daos, mayor.getUniqueId(), "Romulus");
        seedPlayer(daos, stranger.getUniqueId(), "Hostis");

        Result<City> founded = await(cityService.create(mayor.getUniqueId(), "Roma",
                new Placement(WORLD, 0, 0, 8.5, 64.0, 8.5, 0f, 0f)));
        assertTrue(founded.isSuccess(), "fixture city failed");
        city = founded.orElseThrow();

        stranger.setLocation(inside());
    }

    @AfterEach
    void tearDown() {
        db.close();
        MockBukkit.unmock();
    }

    private static void seedPlayer(DaoRegistry daos, UUID uuid, String name) {
        await(daos.players().insert(new PlayerRow(uuid, name, new BigDecimal("50000.00"),
                null, null, 1L, System.currentTimeMillis(),
                TimeUnit.HOURS.toMillis(10), TimeUnit.HOURS.toMillis(10),
                0, 0L, 0L, false, 0L, 0L)));
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Somewhere inside the city's core chunk. */
    private Location inside() {
        return new Location(world, 8.5, 64, 8.5);
    }

    /** Somewhere no city has ever claimed. */
    private Location outside() {
        return new Location(world, 6400.5, 64, 6400.5);
    }

    /** Crosses the SPEC 26.2 threshold, which starts the warning and its timer. */
    private void earnAWarning() {
        long now = System.currentTimeMillis();
        for (int strike = 0; strike < 3; strike++) {
            trespass.violated(city.id(), stranger.getUniqueId(), stranger.getLocation(),
                    now + strike);
        }
        assertEquals(TrespassResponse.Phase.WARNING, phase(), "the fixture must have warned");
    }

    private TrespassResponse.Phase phase() {
        return trespass.response().phaseOf(city.id(), stranger.getUniqueId(),
                System.currentTimeMillis());
    }

    private int warningTicks() {
        return (int) trespass.warningSeconds() * 20;
    }

    private int deEscalationTicks() {
        return (int) trespass.deEscalationSeconds() * 20;
    }

    private void moveTo(Location where) {
        Location from = stranger.getLocation();
        stranger.setLocation(where);
        listener.onTeleport(new PlayerTeleportEvent(stranger, from, where));
    }

    // ==================================================================================
    // The warning timer
    // ==================================================================================

    @Test
    @DisplayName("SPEC 26.2 step 2: staying put through the warning earns the alert")
    void stayingThroughTheWarningEarnsTheAlert() {
        earnAWarning();

        server.getScheduler().performTicks(warningTicks() + 1);

        assertEquals(TrespassResponse.Phase.ALERTED, phase());
    }

    @Test
    @DisplayName("SPEC 26.2: taking the warning and walking away costs nothing")
    void leavingDuringTheWarningIsSafe() {
        earnAWarning();

        // No crossing event, deliberately: the warning task must ask where the player is when
        // it fires rather than trusting anything it was told when it was scheduled.
        stranger.setLocation(outside());
        server.getScheduler().performTicks(warningTicks() + 1);

        assertEquals(TrespassResponse.Phase.NONE, phase(),
                "the warning phase exists so that a player who heeds it is not attacked");
    }

    @Test
    @DisplayName("logging out during the warning is not staying put")
    void loggingOutDuringTheWarningIsSafe() {
        earnAWarning();

        stranger.disconnect();
        server.getScheduler().performTicks(warningTicks() + 1);

        assertEquals(TrespassResponse.Phase.NONE, phase());
    }

    // ==================================================================================
    // SPEC 26.2 step 3, the de-escalation
    // ==================================================================================

    @Test
    @DisplayName("SPEC 26.2 step 3: de-escalation takes the configured time, it is not instant")
    void deEscalationTakesTheConfiguredTime() {
        earnAWarning();
        server.getScheduler().performTicks(warningTicks() + 1);
        assertEquals(TrespassResponse.Phase.ALERTED, phase());

        moveTo(outside());

        server.getScheduler().performTicks(deEscalationTicks() - 5);
        assertEquals(TrespassResponse.Phase.ALERTED, phase(),
                "SPEC 26.2 grants ten seconds of de-escalation, so a raider cannot step over "
                        + "the border and be safe on the spot");

        server.getScheduler().performTicks(10);
        assertEquals(TrespassResponse.Phase.NONE, phase());
    }

    @Test
    @DisplayName("stepping back inside cancels the de-escalation")
    void reEntryCancelsDeEscalation() {
        earnAWarning();
        server.getScheduler().performTicks(warningTicks() + 1);

        moveTo(outside());
        server.getScheduler().performTicks(deEscalationTicks() / 2);
        moveTo(inside());
        server.getScheduler().performTicks(deEscalationTicks() * 2);

        assertEquals(TrespassResponse.Phase.ALERTED, phase(),
                "a trespasser who steps over the border and back has not left");
    }

    @Test
    @DisplayName("a move by somebody nobody is watching costs one lookup and does nothing")
    void aMoveByAnUninvolvedPlayerDoesNothing() {
        PlayerMock passerby = server.addPlayer("Vagus");
        passerby.setLocation(inside());

        listener.onTeleport(new PlayerTeleportEvent(passerby, inside(), outside()));
        server.getScheduler().performTicks(deEscalationTicks() * 2);

        assertEquals(TrespassResponse.Phase.NONE,
                trespass.response().phaseOf(city.id(), passerby.getUniqueId(),
                        System.currentTimeMillis()));
    }

    // ==================================================================================
    // SPEC 30.2 cases 94 and 95
    // ==================================================================================

    @Test
    @DisplayName("SPEC 30.2 case 94: quitting keeps the alert for the rest of its duration")
    void quittingDoesNotClearAnAlert() {
        earnAWarning();
        server.getScheduler().performTicks(warningTicks() + 1);
        assertEquals(TrespassResponse.Phase.ALERTED, phase());

        listener.onQuit(new PlayerQuitEvent(stranger, (net.kyori.adventure.text.Component) null,
                PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertEquals(TrespassResponse.Phase.ALERTED, phase(),
                "case 94: the alert survives a logout and resumes on the way back in");
        assertEquals(java.util.List.of(city.id()),
                trespass.citiesAlerting(stranger.getUniqueId(), System.currentTimeMillis()));
    }

    @Test
    @DisplayName("a pending de-escalation does not fire against somebody who has quit and returned")
    void quittingCancelsThePendingDeEscalation() {
        earnAWarning();
        server.getScheduler().performTicks(warningTicks() + 1);

        moveTo(outside());
        listener.onQuit(new PlayerQuitEvent(stranger, (net.kyori.adventure.text.Component) null,
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        server.getScheduler().performTicks(deEscalationTicks() * 2);

        assertEquals(TrespassResponse.Phase.ALERTED, phase(),
                "the timer was cancelled with the session, so case 94's alert is intact");
    }

    // ==================================================================================
    // The glow, SPEC 26.2 step 1
    // ==================================================================================

    // ==================================================================================
    // SPEC 30.4's keys
    // ==================================================================================

    @Test
    @DisplayName("every trespass message exists and renders, in both shipped languages")
    void everyMessageResolves() {
        // LangKeyUsageTest's "does it exist" half scans lang.send / lang.get / Result.failure
        // and nothing else, so a key reached only through the messenger is checked in one
        // direction only: an orphan fails the build, a typo ships as "Missing message" to a
        // player. These five are reached only through the messenger.
        for (String key : java.util.List.of("trespass.warning-title", "trespass.warning-subtitle",
                "trespass.warning", "trespass.alerted", "trespass.city-notice")) {
            for (String language : java.util.List.of("en", "it")) {
                String value = org.bukkit.configuration.file.YamlConfiguration
                        .loadConfiguration(new java.io.File(
                                "src/main/resources/lang/" + language + ".yml"))
                        .getString(key);
                assertTrue(value != null && !value.isBlank(),
                        key + " is missing from " + language + ".yml");
            }
        }

        // The city's name is <cityname>, never <city>. SPEC 23.2 registers "city" as a palette
        // colour, the palette resolver is consulted first, and a message that used <city> for
        // both would render the name as a colour and lose it.
        for (String key : java.util.List.of("trespass.warning-subtitle", "trespass.warning",
                "trespass.alerted")) {
            for (String language : java.util.List.of("en", "it")) {
                String value = org.bukkit.configuration.file.YamlConfiguration
                        .loadConfiguration(new java.io.File(
                                "src/main/resources/lang/" + language + ".yml"))
                        .getString(key);
                assertTrue(value.contains("<cityname>"),
                        key + " in " + language + ".yml must name the city, and must do it "
                                + "with <cityname> rather than the palette's <city>");
            }
        }
    }

    @Test
    @DisplayName("nothing is left glowing once the warning is over")
    void theGlowEndsWithTheWarning() {
        earnAWarning();
        server.getScheduler().performTicks(warningTicks() + 1);

        assertEquals(TrespassResponse.Phase.ALERTED, phase(), "the fixture must have promoted");
        assertFalse(glow.isGlowing(city.id()),
                "UnitStates resolves expiries on read and nothing sweeps them, so a glow "
                        + "tied to the alert rather than the warning would never go out");
    }
}
