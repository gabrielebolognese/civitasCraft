package dev.civitas.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.world.WorldRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 33's PvP policy.
 *
 * <p>The headline assertion is that peacetime PvP is off, which is the <b>conservative</b> side
 * of a contradiction SPEC does not resolve: SPEC 33.1, 33.3 and 33.10 enable it and SPEC 37 with
 * SPEC 38's milestone row disable it. There is a test asserting the config key flips it, so the
 * other reading costs an edit rather than a rewrite.
 */
class PvpPolicyTest {

    @TempDir
    Path directory;

    private ConfigManager configs;
    private PvpPolicy policy;
    /** Chunks the test has marked admin-protected, injected rather than stored. */
    private final java.util.Set<String> protectedChunks = new java.util.HashSet<>();

    private UUID attacker;
    private UUID victim;

    private static final String WORLD = "world";
    private static final long NOON = 1_754_000_000_000L;

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("pvp-test-" + System.nanoTime());
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        configs = new ConfigManager(PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        protectedChunks.clear();
        policy = newPolicy();

        attacker = UUID.randomUUID();
        victim = UUID.randomUUID();
    }

    /** A policy over the same config and the same admin-protected set. */
    private PvpPolicy newPolicy() {
        PvpPolicy fresh = new PvpPolicy(configs, new WorldRegistry(configs));
        fresh.useAdminProtection((world, x, z) ->
                protectedChunks.contains(world + ":" + x + ":" + z));
        return fresh;
    }

    /** Marks a chunk admin-protected, the way /ca claim protect would. */
    private void protect(String world, int chunkX, int chunkZ) {
        protectedChunks.add(world + ":" + chunkX + ":" + chunkZ);
    }

    private PvpPolicy.PvpDecision check() {
        return check(WORLD, 0, 0);
    }

    private PvpPolicy.PvpDecision check(String world, int chunkX, int chunkZ) {
        return policy.check(attacker, victim, world, chunkX, chunkZ, NOON);
    }

    // ==================================================================================
    // The contested rule
    // ==================================================================================

    @Nested
    @DisplayName("peacetime, SPEC 33 against SPEC 37")
    class Peacetime {

        @Test
        @DisplayName("it ships off, which is the conservative side of the contradiction")
        void shipsDisabled() {
            // Part I's pillar 1.4: "Outside of declared wars, the world is fully protected."
            // SPEC 1 makes the pillars decide ambiguous calls, and this is one.
            assertFalse(policy.peacetimeEnabled());

            PvpPolicy.PvpDecision decision = check();

            assertTrue(decision.denied());
            assertEquals("PEACETIME", decision.reason());
        }

        @Test
        @DisplayName("it is off in wilderness, in claims and in the nether alike")
        void offEverywhere() {
            // Before M4a, PvP was refused inside claims and left to vanilla everywhere else,
            // so the wilderness was the 99% of the map nothing covered.
            for (String world : List.of("world", "world_nether", "world_the_end", "elsewhere")) {
                assertTrue(check(world, 500, -500).denied(), world + " allowed peacetime PvP");
            }
        }

        @Test
        @DisplayName("one config key turns SPEC 33's reading on")
        void oneKeyFlipsIt() {
            configs.get(ConfigFile.COMBAT).set("pvp.peacetime", true);

            assertTrue(policy.peacetimeEnabled());
            assertTrue(check().allowed(),
                    "the other reading of SPEC must cost an edit, not a rewrite");
        }

        @Test
        @DisplayName("hurting yourself is not PvP")
        void selfDamage() {
            assertTrue(policy.check(attacker, attacker, WORLD, 0, 0, NOON).allowed());
        }

        @Test
        @DisplayName("damage with no player behind it is not PvP")
        void noAttacker() {
            assertTrue(policy.check(null, victim, WORLD, 0, 0, NOON).allowed());
        }
    }

    // ==================================================================================
    // SPEC 37's exclusion zones
    // ==================================================================================

    @Nested
    @DisplayName("exclusion zones, SPEC 37")
    class Zones {

        @Test
        @DisplayName("spawn is a sanctuary, out to the configured radius")
        void spawnZone() {
            policy.useSpawnChunk(() -> Optional.of(new Object[] {WORLD, 0, 0}));
            configs.get(ConfigFile.COMBAT).set("pvp.spawn-radius-chunks", 4);

            assertEquals(Optional.of(PvpZone.SPAWN), policy.zoneAt(WORLD, 4, -4));
            assertEquals(Optional.empty(), policy.zoneAt(WORLD, 5, 0), "just outside");
            assertEquals(Optional.empty(), policy.zoneAt("other", 0, 0), "another world");
        }

        @Test
        @DisplayName("an admin-protected chunk is a sanctuary")
        void adminZone() {
            protect(WORLD, 7, 7);

            assertEquals(Optional.of(PvpZone.ADMIN_PROTECTED), policy.zoneAt(WORLD, 7, 7));
            assertEquals("ZONE_ADMIN_PROTECTED", check(WORLD, 7, 7).reason());
        }

        @Test
        @DisplayName("removing a zone from the list stops it protecting")
        void zonesAreConfigurable() {
            protect(WORLD, 7, 7);
            configs.get(ConfigFile.COMBAT).set("pvp.exclusion-zones",
                    List.of("SPAWN", "MINING_CLAIM"));

            assertEquals(Optional.empty(), policy.zoneAt(WORLD, 7, 7));
        }

        @Test
        @DisplayName("a zone outranks a war, or it would not be a sanctuary")
        void zoneBeatsWar() {
            // SPEC 32.7 makes spawn peaceful "under all circumstances including active wars".
            // A sanctuary a war could override is not one.
            protect(WORLD, 7, 7);
            PvpPolicy atWar = newPolicy();
            atWar.useWarCheck((a, v, world, x, z) -> true);

            assertTrue(atWar.check(attacker, victim, WORLD, 7, 7, NOON).denied(),
                    "a war reached inside an admin-protected chunk");
            assertTrue(atWar.check(attacker, victim, WORLD, 0, 0, NOON).allowed(),
                    "and outside it, the war seam is what allows the fight");
        }

        @Test
        @DisplayName("an unknown zone name in the config is ignored rather than fatal")
        void unknownZoneName() {
            configs.get(ConfigFile.COMBAT).set("pvp.exclusion-zones",
                    List.of("SPAWN", "NOT_A_ZONE"));

            assertEquals(java.util.Set.of(PvpZone.SPAWN), policy.exclusionZones());
        }
    }

    // ==================================================================================
    // SPEC 33.5
    // ==================================================================================

    @Nested
    @DisplayName("the resource worlds, SPEC 33.5")
    class ResourceWorlds {

        @Test
        @DisplayName("PvP is off there, and stays off during a war")
        void offEvenInWar() {
            // "Enabling war PvP there would make the primary income source unavailable to
            // whichever side is losing a war, which compounds a defeat into an economic
            // collapse."
            PvpPolicy atWar = newPolicy();
            atWar.useWarCheck((a, v, world, x, z) -> true);

            assertEquals("RESOURCE_WORLD",
                    atWar.check(attacker, victim, "resource", 0, 0, NOON).reason());
            assertEquals("RESOURCE_WORLD",
                    atWar.check(attacker, victim, "resource_nether", 0, 0, NOON).reason());
        }

        @Test
        @DisplayName("a server that wants it can have it")
        void configurable() {
            configs.get(ConfigFile.COMBAT).set("pvp.resource-worlds", true);
            configs.get(ConfigFile.COMBAT).set("pvp.peacetime", true);

            assertTrue(policy.check(attacker, victim, "resource", 0, 0, NOON).allowed());
        }
    }

    // ==================================================================================
    // SPEC 37's grace periods
    // ==================================================================================

    @Nested
    @DisplayName("join and respawn grace, SPEC 37")
    class Grace {

        @BeforeEach
        void allowPeacetime() {
            // Otherwise every assertion below would pass for the wrong reason.
            configs.get(ConfigFile.COMBAT).set("pvp.peacetime", true);
        }

        @Test
        @DisplayName("a player who has just joined cannot be attacked")
        void victimGrace() {
            policy.onJoin(victim, NOON);

            assertEquals("VICTIM_IN_GRACE", check().reason());
        }

        @Test
        @DisplayName("and cannot attack either, SPEC 33.9 case 123")
        void graceIsSymmetric() {
            // "Immunity is not a one-way shield." A grace that only protected would be a free
            // ten seconds of hitting people who cannot hit back.
            policy.onJoin(attacker, NOON);

            assertEquals("ATTACKER_IN_GRACE", check().reason());
        }

        @Test
        @DisplayName("it expires")
        void expires() {
            policy.onJoin(victim, NOON);
            long after = NOON + policy.joinGraceMillis() + 1;

            assertTrue(policy.check(attacker, victim, WORLD, 0, 0, after).allowed());
            assertFalse(policy.isInGrace(victim, after));
        }

        @Test
        @DisplayName("respawning grants it again")
        void respawnGrace() {
            policy.onRespawn(victim, NOON);

            assertTrue(policy.isInGrace(victim, NOON));
            assertEquals("VICTIM_IN_GRACE", check().reason());
        }

        @Test
        @DisplayName("quitting drops it, so it cannot be banked")
        void forgetting() {
            policy.onJoin(victim, NOON);
            policy.forget(victim);

            assertFalse(policy.isInGrace(victim, NOON));
        }

        @Test
        @DisplayName("the remaining time is reported, for the message")
        void remainingIsReported() {
            policy.onJoin(victim, NOON);

            assertEquals(policy.joinGraceMillis(), policy.graceRemaining(victim, NOON));
            assertEquals(0, policy.graceRemaining(victim,
                    NOON + policy.joinGraceMillis() + 1));
            assertEquals(0, policy.graceRemaining(UUID.randomUUID(), NOON),
                    "a player nobody has seen has no grace to run out");
        }

        @Test
        @DisplayName("both windows are configurable, and zero switches one off")
        void configurable() {
            configs.get(ConfigFile.COMBAT).set("pvp.join-grace-seconds", 30);
            configs.get(ConfigFile.COMBAT).set("pvp.respawn-grace-seconds", 0);

            policy.onJoin(victim, NOON);
            assertTrue(policy.isInGrace(victim, NOON + 20_000));

            UUID other = UUID.randomUUID();
            policy.onRespawn(other, NOON);
            assertFalse(policy.isInGrace(other, NOON), "zero seconds is no grace at all");
        }
    }

    // ==================================================================================
    // The seam M19b fills
    // ==================================================================================

    @Nested
    @DisplayName("the war seam")
    class WarSeam {

        @Test
        @DisplayName("it answers no until the war half is built")
        void noWarYet() {
            assertFalse(policy.isWarPvpAllowed(attacker, victim, WORLD, 0, 0));
        }

        @Test
        @DisplayName("filling it in is the only change war PvP needs here")
        void seamIsSufficient() {
            PvpPolicy atWar = newPolicy();
            atWar.useWarCheck((a, v, world, x, z) -> true);

            assertTrue(atWar.check(attacker, victim, WORLD, 0, 0, NOON).allowed(),
                    "peacetime is still off, so only the seam can have allowed this");
        }
    }
}
