package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
 * SPEC 27.8's leash: "A unit is bound to the chunk it is placed in. It may move up to
 * {@code defense.leash-blocks} (default 8) past that chunk's border, and is teleported back if it
 * exceeds it."
 *
 * <h2>What this replaces, and why the old assertions were right until they were not</h2>
 *
 * <p>Every measurement here used to be taken from the city's <em>claim set</em>, which is Part I
 * 12.3's rule and which this class asserted at length: a guard could cross a two-hundred-chunk
 * city without being pulled home. SPEC 25 supersedes Part I Section 12 in full and SPEC 27.8
 * binds a unit to its own chunk instead, so those assertions were replaced rather than deleted —
 * the reversal is deliberate and a future reader will otherwise take it for a regression.
 */
class DefenseLeashTest {

    private static final String WORLD = "world";

    @TempDir
    Path directory;

    private ServerMock server;
    private WorldMock world;
    private CityTestSupport support;
    private DefenseCatalogue catalogue;
    private DefenseLeash leash;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld(WORLD);
        support = CityTestSupport.open(directory);

        var plugin = MockBukkit.createMockPlugin("CivitasTest");
        catalogue = new DefenseCatalogue(support.configs, quiet());
        catalogue.load();
        DefenseRegistry registry = new DefenseRegistry(support.daos.defenseUnits());
        leash = new DefenseLeash(registry,
                new DefenseSpawner(plugin, catalogue,
                        new dev.civitas.lang.LangManager(
                                dev.civitas.config.PluginResources.ofClasspath(
                                        directory.resolve("plugin").toFile(), quiet()),
                                support.configs)),
                new DefenseBehaviour(catalogue, support.registry),
                catalogue);

        // A city occupying one chunk at the origin, so a unit placed there posts at chunk (0,0).
        City city = support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
        posted = new DefenseUnit(1, city.id(), "city-guard", WORLD, 8.0, 64.0, 8.0,
                new BigDecimal("900.00"), true, null, null);
    }

    private DefenseUnit posted;

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
        return leash.blocksOutsidePost(posted, new Location(world, x, 64, z));
    }

    // ==================================================================================
    // The measurement, SPEC 27.8
    // ==================================================================================

    @Test
    @DisplayName("a unit standing in the chunk it was placed in is not outside at all")
    void insideItsOwnChunkIsZero() {
        assertEquals(0, outsideAt(8, 8));
        assertEquals(0, outsideAt(0, 0));
        assertEquals(0, outsideAt(15, 15), "the far corner of its own chunk is still inside");
    }

    @Test
    @DisplayName("just over its chunk's border reads as barely outside")
    void justOutsideIsSmall() {
        // The adjacent chunk's near edge is where the post's chunk ends, so a unit hugging it
        // reads near zero and is left alone: SPEC 27.8's rule is about wandering, not crossing.
        assertTrue(outsideAt(16, 8) < 8, "at the border: " + outsideAt(16, 8));
        assertFalse(leash.blocksOutsidePost(posted, new Location(world, 16, 64, 8)) > 8);
    }

    @Test
    @DisplayName("a chunk further out is past the leash")
    void farOutExceedsTheLeash() {
        assertTrue(outsideAt(40, 8) > 8, "two chunks out: " + outsideAt(40, 8));
        assertTrue(outsideAt(200, 200) > 8);
    }

    @Test
    @DisplayName("SPEC 27.8 supersedes Part I 12.3: its city's other land is not home")
    void itsCitysOtherChunksAreStillOutside() {
        // The reversal, stated as an assertion. Under Part I 12.3 this position was inside the
        // city and therefore inside the leash; under SPEC 27.8 the unit is bound to the chunk it
        // was placed in, so a guard cannot wander three chunks of its own city away from its
        // post. This is the assertion that will look like a bug to somebody reading Part I.
        support.claims.registry().put(new dev.civitas.core.claim.Claim(
                500L, posted.cityId(), WORLD, 3, 0, System.currentTimeMillis(),
                java.util.UUID.randomUUID(), BigDecimal.ZERO,
                dev.civitas.core.claim.ClaimType.NORMAL, null));

        assertTrue(outsideAt(3 * 16 + 8, 8) > 8,
                "owned by the same city, and still past this unit's leash");
    }

    @Test
    @DisplayName("another world is unreachable rather than merely far")
    void anotherWorldIsUnreachable() {
        WorldMock nether = server.addSimpleWorld("world_nether");

        assertEquals(Double.MAX_VALUE,
                leash.blocksOutsidePost(posted, new Location(nether, 0, 64, 0)));
    }

    // ==================================================================================
    // The rule the measurement feeds
    // ==================================================================================

    @Test
    @DisplayName("the leash distance is the one from defense.yml")
    void leashIsConfigured() {
        DefenseBehaviour behaviour = new DefenseBehaviour(catalogue, support.registry);

        assertEquals(8, catalogue.leashDistance(), "SPEC 27.8's eight blocks");
        assertFalse(behaviour.shouldReturn(8), "exactly at the limit is still allowed");
        assertTrue(behaviour.shouldReturn(9), "past it is not");

        support.configs.get(dev.civitas.config.ConfigFile.DEFENSE)
                .set("behaviour.leash-distance-blocks", 24);
        assertFalse(behaviour.shouldReturn(20), "and it is a config key, not a constant");
    }

    @Test
    @DisplayName("SPEC 30.2 case 92: three refused teleports fall back to a rebuild")
    void teleportFailuresFallBackToARebuild() {
        java.util.List<Integer> rebuilt = new java.util.ArrayList<>();
        leash.useRecovery(unit -> rebuilt.add(unit.id()));

        // An entity whose teleport is always refused. MockBukkit's own entities teleport
        // successfully, so the failure path can only be reached by refusing it here.
        var stubborn = new StubbornEntity(server, world);
        for (int attempt = 1; attempt <= 2; attempt++) {
            assertFalse(leash.returnHome(stubborn, posted));
            assertEquals(attempt, leash.failureCount(posted.id()));
            assertTrue(rebuilt.isEmpty(), "two failures is not three");
        }

        assertFalse(leash.returnHome(stubborn, posted));
        assertEquals(java.util.List.of(posted.id()), rebuilt);
        assertEquals(0, leash.failureCount(posted.id()), "and the count starts again");
    }

    @Test
    @DisplayName("SPEC 30.2 case 93: a unit that is not far away is not touched")
    void trappedIsNotStrayed() {
        // "A unit trapped in a hole by an attacker: intended tactic, allowed. The leash teleport
        // only triggers on distance, not on being stuck."
        var stubborn = new StubbornEntity(server, world);
        stubborn.setLocation(new Location(world, 8, 20, 8));   // in its own chunk, in a pit

        assertFalse(leash.returnHome(stubborn, posted));
        assertEquals(0, leash.failureCount(posted.id()),
                "being stuck is not a failed teleport, because no teleport was attempted");
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
        // The tick runs over every living entity in every world, so this is the common case by a
        // wide margin and it must do nothing at all.
        world.spawnEntity(new Location(world, 500, 64, 500), org.bukkit.entity.EntityType.COW);

        assertEquals(0, leash.tick(new java.util.ArrayList<>(world.getEntities())));
        assertTrue(world.getEntities().stream().anyMatch(entity ->
                entity.getLocation().getBlockX() == 500), "the cow has not moved");
    }

    /**
     * A living entity a long way from its post whose teleport is always refused.
     *
     * <p>MockBukkit's entities teleport successfully, so SPEC 30.2 case 92 has no other way in.
     */
    private static final class StubbornEntity
            extends org.mockbukkit.mockbukkit.entity.ZombieMock {

        private StubbornEntity(ServerMock server, WorldMock where) {
            super(server, java.util.UUID.randomUUID());
            setLocation(new Location(where, 500, 64, 500));
        }

        @Override
        public boolean teleport(Location location) {
            return false;
        }
    }
}
