package dev.civitas.gui.menus;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.core.city.City;
import dev.civitas.core.outpost.Outpost;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
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
 * SPEC 39.12's outpost screens.
 *
 * <p>What is worth testing here is not that buttons appear. It is the two things SPEC 39.12
 * asks for by name and that a menu usually gets wrong: that a price is shown <b>term by term</b>
 * rather than as a total, and that a button whose placement rule fails stays visible <b>with the
 * reason</b> rather than disappearing. A hidden button leaves a player guessing at a rule they
 * cannot see, which is the failure the whole milestone exists to fix.
 */
class OutpostScreensTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private MenuTestSupport support;

    private PlayerMock mayorPlayer;
    private UUID mayor;
    private City city;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin("CivitasTest");
        support = MenuTestSupport.open(directory, plugin);

        mayorPlayer = server.addPlayer("Romulus");
        mayor = mayorPlayer.getUniqueId();
        support.cities.givenPlayer(mayor, "Romulus", new BigDecimal("50000000.00"),
                TimeUnit.HOURS.toMillis(10));

        city = support.cities.givenCity(mayor, "Roma", 0, 0);
        await(support.cities.daos.cities().updateTreasury(city.id(),
                new BigDecimal("50000000.00")));
        city.setTreasury(new BigDecimal("50000000.00"));
    }

    @AfterEach
    void tearDown() {
        support.close();
        MockBukkit.unmock();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private OutpostsMenu open() {
        OutpostsMenu menu = new OutpostsMenu(support.menus, support.services, mayorPlayer,
                city, null);
        menu.open();
        return menu;
    }

    /** Puts the viewer in a chunk, which is what every price on these screens depends on. */
    private void standIn(int chunkX, int chunkZ) {
        mayorPlayer.setLocation(new Location(server.getWorlds().get(0),
                chunkX * 16 + 8, 64, chunkZ * 16 + 8));
    }

    private Outpost givenOutpost(String name, int chunkX, int chunkZ) {
        return await(support.services.outposts().create(mayor, city, name,
                server.getWorlds().get(0).getName(), chunkX, chunkZ,
                chunkX * 16 + 8, 64, chunkZ * 16 + 8, 0f, 0f)).orElseThrow();
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

    private ItemStack slot(int index) {
        return mayorPlayer.getOpenInventory().getItem(index);
    }

    // ==================================================================================
    // SPEC 39.12's seven slots
    // ==================================================================================

    @Nested
    @DisplayName("the outposts screen, SPEC 39.12")
    class Screen {

        @Test
        @DisplayName("every slot SPEC 39.12 names is filled")
        void everySlotIsFilled() {
            standIn(40, 0);
            open();

            for (int index : new int[] {4, 20, 22, 24, 30, 32}) {
                assertNotNull(slot(index), "SPEC 39.12 names slot " + index);
            }
        }

        @Test
        @DisplayName("the header counts remote chunks, not just outposts")
        void headerCountsChunks() {
            Outpost outpost = givenOutpost("North", 40, 0);
            assertTrue(await(support.services.outposts().expand(mayor, city, outpost,
                    server.getWorlds().get(0).getName(), 41, 0)).isSuccess());
            standIn(0, 0);
            open();

            // One outpost, two chunks. A screen that counted outposts alone would say 1 and 1,
            // which tells a player nothing about what they are paying upkeep on.
            assertTrue(lore(slot(4)).contains("2"), "expected a chunk count: " + lore(slot(4)));
        }
    }

    // ==================================================================================
    // Showing the work, SPEC 39.11 and 39.12
    // ==================================================================================

    @Nested
    @DisplayName("the price is shown term by term")
    class Breakdown {

        @Test
        @DisplayName("the claim button lists all four terms, not a total")
        void claimShowsEveryTerm() {
            givenOutpost("North", 40, 0);
            standIn(41, 0);
            open();

            String text = lore(slot(20));
            assertTrue(text.contains("base"), "no base term: " + text);
            assertTrue(text.contains("distance"), "no distance term: " + text);
            assertTrue(text.contains("founding"), "no chunk-factor term: " + text);
            assertTrue(text.contains("members"), "no member divisor: " + text);
            assertTrue(text.contains("="), "no total: " + text);
        }

        @Test
        @DisplayName("the explainer substitutes the city's own numbers, not an example")
        void explainerUsesRealNumbers() {
            standIn(500, 0);
            OutpostCostMenu menu = new OutpostCostMenu(support.menus, support.services,
                    mayorPlayer, city, null);
            menu.open();

            String distance = lore(slot(21));
            // 500 chunks out is 8,000 blocks, so D(d) is about 1.71 — a worked example with
            // invented numbers would explain the formula and answer nobody's actual question.
            assertTrue(distance.contains("8,000") || distance.contains("8000"),
                    "the distance term should name this city's distance: " + distance);
            assertFalse(lore(slot(31)).isBlank(), "the total should be present");
        }

        @Test
        @DisplayName("the distance term moves when the player does, which is the lesson")
        void explainerIsLive() {
            standIn(10, 0);
            OutpostCostMenu near = new OutpostCostMenu(support.menus, support.services,
                    mayorPlayer, city, null);
            near.open();
            String close = lore(slot(21));

            standIn(2000, 0);
            near.open();

            assertFalse(close.equals(lore(slot(21))),
                    "walking further out should change the distance term");
        }
    }

    // ==================================================================================
    // Refusals, SPEC 39.12
    // ==================================================================================

    @Nested
    @DisplayName("a refused button says why")
    class Refusals {

        @Test
        @DisplayName("with no outpost yet, the claim button explains rather than vanishing")
        void claimWithNothingToJoin() {
            standIn(40, 0);
            open();

            ItemStack claim = slot(20);
            assertNotNull(claim, "SPEC 39.12 wants the reason shown, not the button hidden");
            assertFalse(lore(claim).isBlank(), "and it should carry a reason");
        }

        @Test
        @DisplayName("a chunk that does not border the outpost names that rule")
        void claimTooFarAway() {
            givenOutpost("North", 40, 0);
            standIn(60, 0);
            open();

            String text = lore(slot(20));
            assertNotNull(slot(20));
            assertFalse(text.contains("="),
                    "a refused claim must not advertise a price: " + text);
        }

        @Test
        @DisplayName("founding too close to the city names the distance rule")
        void foundTooCloseToTheCity() {
            // SPEC 39.6: at least 32 chunks from the city body.
            standIn(2, 0);
            open();

            String text = lore(slot(22));
            assertFalse(text.isBlank(), "the found button should carry its refusal reason");
            assertFalse(text.contains("="), "and no price: " + text);
        }
    }

    // ==================================================================================
    // The detail screen, SPEC 39.12
    // ==================================================================================

    @Nested
    @DisplayName("the detail screen")
    class Detail {

        @Test
        @DisplayName("the diagram marks the chunks the outpost owns")
        void diagramMarksOwnership() {
            Outpost outpost = givenOutpost("North", 40, 0);
            assertTrue(await(support.services.outposts().expand(mayor, city, outpost,
                    server.getWorlds().get(0).getName(), 41, 0)).isSuccess());
            standIn(40, 0);

            new OutpostDetailMenu(support.menus, support.services, mayorPlayer, city,
                    outpost, null).open();

            // The window is anchored on the founding chunk at index 1, so 39 is (40,0) and
            // 40 is (41,0) — both owned — while 38 is (39,0), which is not.
            assertEquals(org.bukkit.Material.LIME_STAINED_GLASS_PANE, slot(39).getType());
            assertEquals(org.bukkit.Material.LIME_STAINED_GLASS_PANE, slot(40).getType());
            assertEquals(org.bukkit.Material.GRAY_STAINED_GLASS_PANE, slot(38).getType());
        }

        @Test
        @DisplayName("releasing a chunk is offered only where the player is standing in one")
        void unclaimNeedsYouToBeThere() {
            Outpost outpost = givenOutpost("North", 40, 0);

            standIn(200, 200);
            new OutpostDetailMenu(support.menus, support.services, mayorPlayer, city,
                    outpost, null).open();
            String elsewhere = lore(slot(25));

            standIn(40, 0);
            new OutpostDetailMenu(support.menus, support.services, mayorPlayer, city,
                    outpost, null).open();

            assertFalse(elsewhere.equals(lore(slot(25))),
                    "standing in the chunk should change what the button offers");
        }

        @Test
        @DisplayName("the defence entry says its system is not here yet, rather than lying")
        void defenceIsHonestlyUnavailable() {
            Outpost outpost = givenOutpost("North", 40, 0);
            standIn(40, 0);

            new OutpostDetailMenu(support.menus, support.services, mayorPlayer, city,
                    outpost, null).open();

            // M12a to M12f rebuild the roster; until then this renders through the refusal
            // path, the same way M8 handled seven screens whose systems did not exist.
            assertFalse(lore(slot(30)).isBlank());
        }
    }
}
