package dev.civitas.listener.war;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import dev.civitas.config.PluginResources;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.war.Evacuation;
import dev.civitas.core.war.War;
import dev.civitas.core.war.WarRegistry;
import dev.civitas.core.war.WarState;
import dev.civitas.core.war.WarZone;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.row.WarRow;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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
 * SPEC 17.4 cases 41 and 48: arriving inside a war zone rather than being caught in one.
 *
 * <p><b>Case 41</b> is about somebody who has nothing to do with the war. They belong to
 * neither city, so they are not a valid target and cannot grief — which means leaving them
 * where they are means being killed by a fight they have no part in and cannot join.
 *
 * <p><b>Case 48</b> is about somebody who does. They logged out inside the zone during the
 * war, and the rollback has since put back whatever was there before it — which may well be
 * the inside of a wall. SPEC's requirement is short: "moved to the nearest safe location.
 * Prevents suffocation in a restored block."
 */
class WarJoinListenerTest {

    @TempDir
    Path directory;

    private static final long NOW = System.currentTimeMillis();
    private static final BigDecimal WAGER = new BigDecimal("50000.00");

    private ServerMock server;
    private WorldMock world;
    private CityTestSupport support;
    private WarRegistry registry;
    private WarJoinListener listener;
    private City attacker;
    private City defender;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        support = CityTestSupport.open(directory);
        registry = new WarRegistry(support.daos.wars());

        LangManager lang = new LangManager(PluginResources.ofClasspath(
                directory.resolve("plugin").toFile(),
                java.util.logging.Logger.getLogger("join-test")), support.configs);
        lang.load();

        listener = new WarJoinListener(registry, support.registry,
                Evacuation.empty(support.registry), lang);

        attacker = support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
        defender = support.givenCity(support.givenEligiblePlayer("Dido"), "Carthago", 40, 40);
    }

    @AfterEach
    void tearDown() {
        support.close();
        MockBukkit.unmock();
    }

    /** A war whose zone is the defender's core chunk. */
    private War givenWar(WarState state) {
        int id = await(support.daos.wars().insert(new WarRow(0, attacker.id(), defender.id(),
                NOW, NOW + 1000L, NOW + 2000L, state.key(), 0, 0, null, WAGER, null, null)));
        War war = new War(id, attacker.id(), defender.id(), NOW, NOW + 1000L, NOW + 2000L,
                state, WAGER);
        war.zone(WarZone.of(List.of(new dev.civitas.core.claim.Claim(id, defender.id(),
                "world", 40, 40, NOW, defender.mayorUuid(), BigDecimal.ZERO,
                dev.civitas.core.claim.ClaimType.CORE, null)), 0));
        registry.remember(war);
        return war;
    }

    /** A position inside the zone. */
    private Location insideZone() {
        return new Location(world, 40 * 16 + 8, 64, 40 * 16 + 8);
    }

    private Location outsideZone() {
        return new Location(world, 900, 64, 900);
    }

    // ==================================================================================
    // Case 41
    // ==================================================================================

    @Test
    @DisplayName("case 41: a bystander standing in a live zone is moved out")
    void bystanderIsMovedOut() {
        givenWar(WarState.ACTIVE);
        PlayerMock stranger = server.addPlayer("Wanderer");
        stranger.teleport(insideZone());

        listener.onJoin(new PlayerJoinEvent(stranger, net.kyori.adventure.text.Component.empty()));

        assertFalse(isInside(stranger.getLocation()),
                "somebody with no city is not a combatant and must not be left in a war");
    }

    @Test
    @DisplayName("a member of a warring city logging back in is left where they are")
    void combatantsStay() {
        // The rule must not evict the people the war is between. A defender logging in at
        // their own gate is exactly where they should be.
        givenWar(WarState.ACTIVE);
        PlayerMock soldier = server.addPlayer("Hannibal");
        enlist(defender, soldier);
        soldier.teleport(insideZone());

        listener.onJoin(new PlayerJoinEvent(soldier, net.kyori.adventure.text.Component.empty()));

        assertTrue(isInside(soldier.getLocation()), "a combatant belongs in their own war");
    }

    @Test
    @DisplayName("somebody joining well away from any war is not touched")
    void elsewhereIsUntouched() {
        givenWar(WarState.ACTIVE);
        PlayerMock farmer = server.addPlayer("Cincinnatus");
        Location home = outsideZone();
        farmer.teleport(home);

        listener.onJoin(new PlayerJoinEvent(farmer, net.kyori.adventure.text.Component.empty()));

        assertEquals(home.getBlockX(), farmer.getLocation().getBlockX());
    }

    // ==================================================================================
    // Case 48
    // ==================================================================================

    @Test
    @DisplayName("case 48: logging back in where the ground has just been restored moves you")
    void rebuiltGroundMovesEvenACombatant() {
        // The difference from case 41: this applies to everyone, including the people whose
        // war it was. The blocks around them are being written back with physics suppressed
        // and no regard for what is standing there.
        givenWar(WarState.ROLLING_BACK);
        PlayerMock soldier = server.addPlayer("Scipio");
        enlist(defender, soldier);
        soldier.teleport(insideZone());

        listener.onJoin(new PlayerJoinEvent(soldier, net.kyori.adventure.text.Component.empty()));

        assertFalse(isInside(soldier.getLocation()),
                "a zone being restored is no place to stand, whoever you are");
    }

    @Test
    @DisplayName("a zone being restored refuses a teleport in")
    void teleportIntoAClosedZoneIsCancelled() {
        // SPEC 11.8.2 step 1: "The war zone is then closed: entry is blocked with a message."
        // A join is not the only way in.
        givenWar(WarState.ROLLING_BACK);
        PlayerMock visitor = server.addPlayer("Curious");
        visitor.teleport(outsideZone());

        PlayerTeleportEvent event = new PlayerTeleportEvent(visitor, visitor.getLocation(),
                insideZone());
        listener.onTeleport(event);

        assertTrue(event.isCancelled());
    }

    @Test
    @DisplayName("a teleport into a live war zone is allowed, because a war is fought in it")
    void teleportIntoALiveZoneIsFine() {
        givenWar(WarState.ACTIVE);
        PlayerMock soldier = server.addPlayer("Fabius");
        soldier.teleport(outsideZone());

        PlayerTeleportEvent event = new PlayerTeleportEvent(soldier, soldier.getLocation(),
                insideZone());
        listener.onTeleport(event);

        assertFalse(event.isCancelled(), "only a zone being restored is closed");
    }

    /**
     * Puts a mock player into a city.
     *
     * <p>Through the real invite and accept, because the listener asks the registry who a
     * player belongs to and a membership written any other way would not be there.
     */
    private void enlist(City city, PlayerMock player) {
        support.givenPlayer(player.getUniqueId(), player.getName(),
                new BigDecimal("10000.00"),
                java.util.concurrent.TimeUnit.HOURS.toMillis(10));
        assertTrue(await(support.cities.invite(city.mayorUuid(), city, player.getUniqueId()))
                .isSuccess());
        assertTrue(await(support.cities.acceptInvite(player.getUniqueId(), city)).isSuccess());
    }

    private boolean isInside(Location at) {
        return at.getWorld() != null
                && (at.getBlockX() >> 4) == 40 && (at.getBlockZ() >> 4) == 40;
    }
}
