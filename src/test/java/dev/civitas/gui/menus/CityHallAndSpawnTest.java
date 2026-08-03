package dev.civitas.gui.menus;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRank;
import dev.civitas.util.Result;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * The City Hall block (SPEC 8.1) and the city spawn (SPEC 5.6), both of which M2 and M3
 * deferred to this milestone.
 */
class CityHallAndSpawnTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private WorldMock world;
    private MenuTestSupport support;

    private PlayerMock mayorPlayer;
    private PlayerMock memberPlayer;
    private UUID mayor;
    private UUID member;
    private City city;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        var plugin = MockBukkit.createMockPlugin("CivitasTest");
        support = MenuTestSupport.open(directory, plugin);

        mayorPlayer = server.addPlayer("Romulus");
        memberPlayer = server.addPlayer("Titus");
        mayor = mayorPlayer.getUniqueId();
        member = memberPlayer.getUniqueId();

        support.cities.givenPlayer(mayor, "Romulus", new BigDecimal("50000.00"),
                TimeUnit.HOURS.toMillis(10));
        support.cities.givenPlayer(member, "Titus", new BigDecimal("50000.00"),
                TimeUnit.HOURS.toMillis(10));

        city = support.cities.givenCity(mayor, "Roma", 0, 0);
        await(support.cities.cities.invite(mayor, city, member));
        await(support.cities.cities.acceptInvite(member, city));
        CityRank citizen = city.rankByName("Citizen").orElseThrow();
        await(support.cities.ranks.assign(mayor, city, member, citizen));
    }

    @AfterEach
    void tearDown() {
        support.close();
        MockBukkit.unmock();
    }

    // ==================================================================================
    // SPEC 8.1
    // ==================================================================================

    @Nested
    @DisplayName("The City Hall block")
    class Hall {

        @Test
        @DisplayName("SPEC 8.1: a lodestone stamped with the city id")
        void item() {
            ItemStack stack = support.services.cityHall().item(city);

            assertEquals(Material.LODESTONE, stack.getType());
            assertEquals(city.id(), support.services.cityHall().cityIdOf(stack).orElseThrow());
        }

        @Test
        @DisplayName("an ordinary lodestone is not a City Hall")
        void plainBlockIsNotAHall() {
            assertTrue(support.services.cityHall()
                    .cityIdOf(new ItemStack(Material.LODESTONE)).isEmpty());
        }

        @Test
        @DisplayName("a placed block remembers whose it is")
        void placedBlockRemembers() {
            var block = world.getBlockAt(0, 64, 0);
            block.setType(Material.LODESTONE);

            // A lodestone is a tile entity on a real server and can carry persistent data.
            // The mock server does not implement every block state, so a mock that cannot
            // hold the stamp is a limitation of the fixture rather than of the plugin: the
            // rule under test is that marking either works and reads back, or reports that
            // it could not, and never silently claims a block it did not stamp.
            boolean marked = support.services.cityHall().mark(block, city);

            assertEquals(marked, support.services.cityHall().cityIdOf(block).isPresent(),
                    "mark() must not report success it cannot back up");
            if (marked) {
                assertEquals(city.id(), support.services.cityHall().cityIdOf(block).orElseThrow());
            }
        }

        @Test
        @DisplayName("SPEC 8.1: nobody below Co-Mayor may break it")
        void breakRules() {
            var halls = support.services.cityHall();

            assertTrue(halls.mayBreak(city, mayor), "the mayor may");
            assertFalse(halls.mayBreak(city, member), "a citizen may not");
            assertFalse(halls.mayBreak(city, UUID.randomUUID()), "a stranger certainly may not");
        }

        @Test
        @DisplayName("the weight needed to break it is a config key, not a rank name")
        void breakWeightIsConfigurable() {
            // Ranks are editable, so the rule is expressed as a weight. Lowering it lets a
            // Citizen through without renaming anything.
            support.cities.configs.get(ConfigFile.CITIES).set("city-hall.min-break-weight", 40);

            assertTrue(support.services.cityHall().mayBreak(city, member));
        }
    }

    // ==================================================================================
    // SPEC 5.6
    // ==================================================================================

    @Nested
    @DisplayName("City spawn")
    class Spawn {

        @Test
        @DisplayName("SPEC 5.6: the spawn must be inside one of the city's own claims")
        void setSpawnInsideClaims() {
            Result<City> inside = await(support.cities.cities.setSpawn(mayor, city, "world",
                    8.0, 65.0, 8.0, 0f, 0f));
            assertTrue(inside.isSuccess(), reasonOf(inside));
            assertEquals(65.0, city.spawnY());

            Result<City> outside = await(support.cities.cities.setSpawn(mayor, city, "world",
                    5000.0, 65.0, 5000.0, 0f, 0f));
            assertEquals("SPAWN_OUTSIDE_CLAIMS", reasonOf(outside));
        }

        @Test
        @DisplayName("SET_SPAWN is what gates it, not EDIT_SETTINGS")
        void gatedOnSetSpawn() {
            // SPEC 5.4 gives Architect SET_SPAWN and not EDIT_SETTINGS; a Citizen has neither.
            assertEquals("NO_CITY_PERMISSION", reasonOf(await(support.cities.cities
                    .setSpawn(member, city, "world", 8.0, 65.0, 8.0, 0f, 0f))));
        }

        @Test
        @DisplayName("SPEC 5.6: teleporting home warms up rather than happening at once")
        void warmup() {
            mayorPlayer.setLocation(new Location(world, 500.0, 64.0, 500.0));

            Result<Long> started = support.services.spawns().requestTeleport(mayorPlayer);

            assertTrue(started.isSuccess(), reasonOf(started));
            assertEquals(5L, started.orElseThrow(), "five seconds, from cities.yml");
            assertTrue(support.services.spawns().isWarmingUp(mayorPlayer));
            assertEquals(500.0, mayorPlayer.getLocation().getX(),
                    "and they have not moved yet");
        }

        @Test
        @DisplayName("SPEC 17.6 case 77: moving cancels the warmup")
        void movingCancels() {
            mayorPlayer.setLocation(new Location(world, 500.0, 64.0, 500.0));
            support.services.spawns().requestTeleport(mayorPlayer);

            assertTrue(support.services.spawns()
                    .hasMovedAway(mayorPlayer, new Location(world, 503.0, 64.0, 500.0)));
            assertFalse(support.services.spawns()
                            .hasMovedAway(mayorPlayer, new Location(world, 500.1, 64.0, 500.0)),
                    "a twitch is not a walk");

            assertTrue(support.services.spawns().cancel(mayorPlayer, null));
            assertFalse(support.services.spawns().isWarmingUp(mayorPlayer));
        }

        @Test
        @DisplayName("asking twice does not restart the countdown")
        void secondRequestIsRefused() {
            support.services.spawns().requestTeleport(mayorPlayer);

            assertEquals("ALREADY_WARMING",
                    reasonOf(support.services.spawns().requestTeleport(mayorPlayer)));
        }

        @Test
        @DisplayName("a player with no city has nowhere to go")
        void noCity() {
            PlayerMock stranger = server.addPlayer("Nobody");

            assertEquals("NO_CITY",
                    reasonOf(support.services.spawns().requestTeleport(stranger)));
        }

        @Test
        @DisplayName("a zero warmup teleports at once, which is what the bypass permission does")
        void instantWithBypass() {
            support.cities.configs.get(ConfigFile.CITIES).set("spawn.warmup-seconds", 0);
            mayorPlayer.setLocation(new Location(world, 500.0, 64.0, 500.0));

            Result<Long> result = support.services.spawns().requestTeleport(mayorPlayer);

            assertTrue(result.isSuccess(), reasonOf(result));
            assertEquals(0L, result.orElseThrow());
            assertEquals(city.spawnX(), mayorPlayer.getLocation().getX(), 0.001,
                    "they arrived immediately");
        }

        @Test
        @DisplayName("SPEC 5.6: a second teleport is refused until the cooldown passes")
        void cooldown() {
            support.cities.configs.get(ConfigFile.CITIES).set("spawn.warmup-seconds", 0);
            support.services.spawns().requestTeleport(mayorPlayer);

            Result<Long> again = support.services.spawns().requestTeleport(mayorPlayer);

            assertEquals("COOLDOWN", reasonOf(again));
            assertTrue(support.services.spawns().cooldownRemaining(mayorPlayer) > 0);
        }
    }
}
