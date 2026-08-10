package dev.civitas.gui.menus;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRank;
import dev.civitas.core.war.War;
import dev.civitas.core.war.WarState;
import dev.civitas.gui.framework.MenuListener;
import dev.civitas.storage.row.WarRow;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * SPEC 8.8's Wars screen, and the sidebar beside it.
 *
 * <p>What is being tested is mostly that the screen shows the right one of its three faces:
 * SPEC 8.8 describes three layouts and nothing in the click path distinguishes them, so a
 * mistake here means a mayor at war looking at a Declare War button.
 */
class WarsMenuTest {

    @TempDir
    Path directory;

    private static final BigDecimal WAGER = new BigDecimal("50000.00");

    private ServerMock server;
    private MenuTestSupport support;
    private MenuListener listener;

    private PlayerMock mayorPlayer;
    private PlayerMock memberPlayer;
    private UUID mayor;
    private UUID member;
    private City city;
    private City enemy;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin("CivitasTest");
        support = MenuTestSupport.open(directory, plugin);
        listener = new MenuListener(support.menus);

        mayorPlayer = server.addPlayer("Romulus");
        memberPlayer = server.addPlayer("Titus");
        mayor = mayorPlayer.getUniqueId();
        member = memberPlayer.getUniqueId();

        support.cities.givenPlayer(mayor, "Romulus", new BigDecimal("50000.00"),
                java.util.concurrent.TimeUnit.HOURS.toMillis(10));
        support.cities.givenPlayer(member, "Titus", new BigDecimal("50000.00"),
                java.util.concurrent.TimeUnit.HOURS.toMillis(10));

        city = support.cities.givenCity(mayor, "Roma", 0, 0);
        await(support.cities.cities.invite(mayor, city, member));
        await(support.cities.cities.acceptInvite(member, city));
        CityRank citizen = city.rankByName("Citizen").orElseThrow();
        await(support.cities.ranks.assign(mayor, city, member, citizen));

        enemy = support.cities.givenCity(support.cities.givenEligiblePlayer("Dido"),
                "Carthago", 40, 40);
    }

    @AfterEach
    void tearDown() {
        support.close();
        MockBukkit.unmock();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private WarsMenu open(PlayerMock player) {
        WarsMenu menu = new WarsMenu(support.menus, support.services, player, city, null);
        menu.open();
        return menu;
    }

    /** Puts a war on the board, in whichever phase the test needs to look at. */
    private War givenWar(WarState state) {
        long now = System.currentTimeMillis();
        long prepEnds = now + java.util.concurrent.TimeUnit.HOURS.toMillis(48);
        long warEnds = prepEnds + java.util.concurrent.TimeUnit.DAYS.toMillis(7);
        int id = await(support.cities.daos.wars().insert(new WarRow(0, city.id(), enemy.id(),
                now, prepEnds, warEnds, state.key(), 0, 0, null, WAGER, null, null, 0)));
        War war = new War(id, city.id(), enemy.id(), now, prepEnds, warEnds, state, WAGER);
        support.wars.remember(war);
        return war;
    }

    private static String plain(ItemStack stack) {
        return stack == null || stack.getItemMeta() == null
                ? ""
                : PlainTextComponentSerializer.plainText()
                        .serialize(stack.getItemMeta().displayName());
    }

    private static String lore(ItemStack stack) {
        if (stack == null || stack.getItemMeta() == null || stack.getItemMeta().lore() == null) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        stack.getItemMeta().lore().forEach(line ->
                joined.append(PlainTextComponentSerializer.plainText().serialize(line))
                        .append('\n'));
        return joined.toString();
    }

    private static String plainOf(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // ==================================================================================
    // The three faces, SPEC 8.8
    // ==================================================================================

    @Nested
    @DisplayName("at peace")
    class AtPeace {

        @Test
        @DisplayName("the screen offers a declaration")
        void showsDeclare() {
            open(mayorPlayer);

            ItemStack declare = mayorPlayer.getOpenInventory().getItem(22);
            assertNotNull(declare);
            assertEquals(Material.NETHERITE_SWORD, declare.getType());
            assertTrue(plain(declare).contains("Declare"), plain(declare));
        }

        @Test
        @DisplayName("there is nothing to sue for peace over")
        void noPeaceButton() {
            open(mayorPlayer);

            ItemStack slot = mayorPlayer.getOpenInventory().getItem(40);
            assertFalse(slot != null && plain(slot).contains("Peace"), "peace needs a war");
        }

        @Test
        @DisplayName("a member without DECLARE_WAR sees a barrier, and clicking does nothing")
        void permissionIsEnforced() {
            // SPEC 17.5 case 59. The Citizen rank does not hold DECLARE_WAR, so the button
            // renders as the barrier SPEC 8.2 requires rather than silently working.
            open(memberPlayer);

            ItemStack declare = memberPlayer.getOpenInventory().getItem(22);
            assertNotNull(declare);
            assertEquals(Material.BARRIER, declare.getType());

            InventoryClickEvent event = new InventoryClickEvent(
                    memberPlayer.getOpenInventory(),
                    org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 22,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL);
            listener.onClick(event);

            assertTrue(event.isCancelled());
            assertNotNull(memberPlayer.getOpenInventory().getItem(22),
                    "the window is still open, the click did nothing");
        }
    }

    @Nested
    @DisplayName("preparing")
    class Preparing {

        @Test
        @DisplayName("the screen counts down instead of offering a declaration")
        void showsCountdown() {
            givenWar(WarState.PREP);

            open(mayorPlayer);

            ItemStack clock = mayorPlayer.getOpenInventory().getItem(13);
            assertNotNull(clock);
            assertEquals(Material.CLOCK, clock.getType());
            assertTrue(lore(clock).contains("Carthago"), "the opponent is named: " + lore(clock));
        }

        @Test
        @DisplayName("peace can be sued for, and the forfeit is stated before it is")
        void showsPeaceWithItsPrice() {
            givenWar(WarState.PREP);

            open(mayorPlayer);

            ItemStack peace = mayorPlayer.getOpenInventory().getItem(40);
            assertNotNull(peace);
            assertTrue(plain(peace).contains("Peace"), plain(peace));
            assertTrue(lore(peace).contains("12,500") || lore(peace).contains("12500"),
                    "SPEC 8.8's 25% forfeit is shown: " + lore(peace));
        }

        @Test
        @DisplayName("the defence preparation link is offered, as SPEC 8.8 asks")
        void linksToDefense() {
            givenWar(WarState.PREP);

            open(mayorPlayer);

            ItemStack shield = mayorPlayer.getOpenInventory().getItem(20);
            assertNotNull(shield);
            assertEquals(Material.SHIELD, shield.getType());
        }
    }

    @Nested
    @DisplayName("fighting")
    class Fighting {

        @Test
        @DisplayName("the scores of both sides are on the screen")
        void showsScores() {
            War war = givenWar(WarState.ACTIVE);
            war.addScore(true, 30);
            war.addScore(false, 10);

            open(mayorPlayer);

            ItemStack board = mayorPlayer.getOpenInventory().getItem(4);
            assertNotNull(board);
            assertTrue(plain(board).contains("30") && plain(board).contains("10"),
                    plain(board));
        }

        @Test
        @DisplayName("an empty kill feed says so rather than showing nothing")
        void emptyKillFeed() {
            givenWar(WarState.ACTIVE);

            open(mayorPlayer);

            ItemStack feed = mayorPlayer.getOpenInventory().getItem(13);
            assertNotNull(feed);
            assertEquals(Material.IRON_SWORD, feed.getType());
            assertTrue(lore(feed).toLowerCase(java.util.Locale.ROOT).contains("nobody"),
                    lore(feed));
        }

        @Test
        @DisplayName("an enemy member online is counted")
        void countsEnemiesOnline() {
            War war = givenWar(WarState.ACTIVE);
            PlayerMock hostile = server.addPlayer("Hannibal");
            support.cities.givenPlayer(hostile.getUniqueId(), "Hannibal",
                    new BigDecimal("0.00"), java.util.concurrent.TimeUnit.HOURS.toMillis(10));
            await(support.cities.cities.invite(enemy.mayorUuid(), enemy, hostile.getUniqueId()));
            await(support.cities.cities.acceptInvite(hostile.getUniqueId(), enemy));

            open(mayorPlayer);

            ItemStack heads = mayorPlayer.getOpenInventory().getItem(11);
            assertNotNull(heads);
            // Carthago's mayor exists but has never connected, so only Hannibal is counted:
            // this is who a defender can actually be fought by right now, not who is enrolled.
            assertTrue(plain(heads).contains("1"), plain(heads));
            assertTrue(war.areEnemies(city.id(), enemy.id()));
        }
    }

    // ==================================================================================
    // SPEC 17.5 case 60
    // ==================================================================================

    @Test
    @DisplayName("a player kicked from the city has the screen taken away")
    void kickedMemberLosesTheScreen() {
        givenWar(WarState.ACTIVE);
        WarsMenu menu = open(memberPlayer);
        await(support.cities.cities.kick(mayor, city, member));

        menu.refresh();

        assertEquals(org.bukkit.event.inventory.InventoryType.CRAFTING,
                memberPlayer.getOpenInventory().getType(),
                "the window closes rather than showing another city's war");
    }

    // ==================================================================================
    // The sidebar, SPEC 9.3
    // ==================================================================================

    @Nested
    @DisplayName("the war sidebar")
    class Sidebar {

        @Test
        @DisplayName("it is off until asked for, and off again when asked twice")
        void togglesBothWays() {
            assertFalse(support.scoreboard.isWatching(mayor));

            assertTrue(support.scoreboard.toggle(mayorPlayer));
            assertTrue(support.scoreboard.isWatching(mayor));

            assertFalse(support.scoreboard.toggle(mayorPlayer));
            assertFalse(support.scoreboard.isWatching(mayor));
        }

        @Test
        @DisplayName("during preparation it counts down to the fighting")
        void prepLines() {
            War war = givenWar(WarState.PREP);

            var lines = support.scoreboard.lines(war, System.currentTimeMillis());

            assertEquals(3, lines.size());
            assertTrue(plainOf(lines.get(1)).contains("Roma")
                            && plainOf(lines.get(1)).contains("Carthago"),
                    plainOf(lines.get(1)));
            // 48 hours minus the moment that has passed, so it reads in days rather than
            // hours: the sidebar coarsens rather than showing a second-by-second countdown.
            assertTrue(plainOf(lines.get(2)).endsWith("d"), plainOf(lines.get(2)));
        }

        @Test
        @DisplayName("once fighting it shows a line per side, with the score")
        void activeLines() {
            War war = givenWar(WarState.ACTIVE);
            war.addScore(true, 25);

            var lines = support.scoreboard.lines(war, System.currentTimeMillis());

            assertEquals(3, lines.size());
            assertTrue(plainOf(lines.get(0)).contains("Roma")
                    && plainOf(lines.get(0)).contains("25"), plainOf(lines.get(0)));
            assertTrue(plainOf(lines.get(1)).contains("Carthago")
                    && plainOf(lines.get(1)).contains("0"), plainOf(lines.get(1)));
        }

        @Test
        @DisplayName("a finished war says so rather than showing a stale score")
        void finishedLines() {
            War war = givenWar(WarState.RESOLVED);

            var lines = support.scoreboard.lines(war, System.currentTimeMillis());

            assertEquals(1, lines.size());
            assertTrue(plainOf(lines.get(0)).toLowerCase(java.util.Locale.ROOT).contains("over"),
                    plainOf(lines.get(0)));
        }

        @Test
        @DisplayName("a player who leaves is forgotten rather than remembered forever")
        void quitForgets() {
            support.scoreboard.toggle(mayorPlayer);

            support.scoreboard.onQuit(new org.bukkit.event.player.PlayerQuitEvent(mayorPlayer,
                    net.kyori.adventure.text.Component.empty(),
                    org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));

            assertFalse(support.scoreboard.isWatching(mayor));
        }
    }
}
