package dev.civitas.core.defense;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * SPEC 12.3's leash: "Unit outside its claim: teleported back if it wanders more than 8 blocks
 * past the claim border."
 *
 * <p>M12 wrote the rule and tested the arithmetic, and nothing called it, because units had no
 * reason to move: in peacetime a unit attacks only hostile mobs inside its own claims. It is
 * war that gives a guard something to chase, and a chase is what carries it over the border,
 * so the tick belongs to M19 and so does this test.
 *
 * <p>The measurement is what matters. SPEC 12.3 measures from the <em>claim border</em>, not
 * from where the unit was placed: a guard must be free to cross its own city to reach a fight,
 * and must not follow a fleeing attacker into the wilderness.
 */
class DefenseLeashTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private WorldMock world;
    private CityTestSupport support;
    private DefenseLeash leash;
    private City city;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        support = CityTestSupport.open(directory);

        var plugin = MockBukkit.createMockPlugin("CivitasTest");
        DefenseCatalogue catalogue = new DefenseCatalogue(support.configs, quiet());
        catalogue.load();
        DefenseRegistry registry = new DefenseRegistry(support.daos.defenseUnits());
        leash = new DefenseLeash(registry,
                new DefenseSpawner(plugin, catalogue,
                        new dev.civitas.lang.LangManager(
                                dev.civitas.config.PluginResources.ofClasspath(
                                        directory.resolve("plugin").toFile(), quiet()),
                                support.configs)),
                new DefenseBehaviour(catalogue, support.registry),
                support.claimRegistry);

        // A city occupying one chunk at the origin, so the border is at x=16 and z=16.
        city = support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
    }

    @AfterEach
    void tearDown() {
        support.close();
        MockBukkit.unmock();
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("civitas-leash-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private double outsideAt(int x, int z) {
        return leash.blocksOutsideClaims(city.id(), new Location(world, x, 64, z));
    }

    // ==================================================================================
    // The measurement
    // ==================================================================================

    @Test
    @DisplayName("a unit standing on its city's land is not outside at all")
    void insideIsZero() {
        assertEquals(0, outsideAt(8, 8));
        assertEquals(0, outsideAt(0, 0));
        assertEquals(0, outsideAt(15, 15), "the far corner of the chunk is still inside");
    }

    @Test
    @DisplayName("just over the border reads as barely outside")
    void justOutsideIsSmall() {
        // The adjacent chunk's near edge is the claim border, so a unit hugging it reads near
        // zero and is left alone: SPEC 12.3's rule is about wandering, not about crossing.
        assertTrue(outsideAt(16, 8) < 8, "at the border: " + outsideAt(16, 8));
        assertFalse(leash.blocksOutsideClaims(city.id(), new Location(world, 16, 64, 8)) > 8);
    }

    @Test
    @DisplayName("a chunk further out is past the leash")
    void farOutExceedsTheLeash() {
        assertTrue(outsideAt(40, 8) > 8, "two chunks out: " + outsideAt(40, 8));
        assertTrue(outsideAt(200, 200) > 8);
    }

    @Test
    @DisplayName("a city with no land in this world is infinitely far from it")
    void anotherWorldIsUnreachable() {
        WorldMock nether = server.addSimpleWorld("world_nether");

        assertEquals(Double.MAX_VALUE,
                leash.blocksOutsideClaims(city.id(), new Location(nether, 0, 64, 0)));
    }

    // ==================================================================================
    // The rule the measurement feeds
    // ==================================================================================

    @Test
    @DisplayName("the leash distance is the one from defense.yml")
    void leashIsConfigured() {
        DefenseCatalogue catalogue = new DefenseCatalogue(support.configs, quiet());
        catalogue.load();
        DefenseBehaviour behaviour = new DefenseBehaviour(catalogue, support.registry);

        assertEquals(8, catalogue.leashDistance(), "SPEC 12.3's eight blocks");
        assertFalse(behaviour.shouldReturn(8), "exactly at the limit is still allowed");
        assertTrue(behaviour.shouldReturn(9), "past it is not");
    }

    @Test
    @DisplayName("ticking an empty world moves nothing and costs nothing")
    void emptyTickIsQuiet() {
        assertEquals(0, leash.tick(java.util.List.of()));
        assertEquals(0, leash.tick(new java.util.ArrayList<>(world.getEntities())));
    }

    @Test
    @DisplayName("an entity that is not a defense unit is left where it is")
    void ignoresOrdinaryMobs() {
        // The tick runs over every living entity in every world, so this is the common case
        // by a wide margin and it must do nothing at all.
        world.spawnEntity(new Location(world, 500, 64, 500), org.bukkit.entity.EntityType.COW);

        assertEquals(0, leash.tick(new java.util.ArrayList<>(world.getEntities())));
        assertTrue(world.getEntities().stream().anyMatch(entity ->
                entity.getLocation().getBlockX() == 500), "the cow has not moved");
    }

    @Test
    @DisplayName("claims of another city do not count as home")
    void onlyItsOwnCitysLand() {
        City other = support.givenCity(support.givenEligiblePlayer("Dido"), "Carthago", 40, 40);
        await(support.claims.claim(other.mayorUuid(), other, "world", 41, 40));

        // Standing deep inside Carthago, a guard of Roma is still a long way from home.
        assertTrue(outsideAt(40 * 16 + 8, 40 * 16 + 8) > 8);
    }
}
