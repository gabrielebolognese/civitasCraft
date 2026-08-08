package dev.civitas.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SPEC 32 world model.
 *
 * <p>Three things are settled here rather than left as incidental behaviour: SPEC 20's Open
 * Decisions 1 and 4, which SPEC 32.2 answers, and SPEC 32.3's rejection of the border SPEC 37
 * still ships config for. All three are the kind of thing a later reader would "fix" back if the
 * only evidence were a comment.
 */
class WorldRegistryTest {

    @TempDir
    Path directory;

    private ConfigManager configs;
    private WorldRegistry worlds;
    private final List<LogRecord> warnings = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("world-test-" + System.nanoTime());
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.ALL);
        warnings.clear();
        quiet.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        configs = new ConfigManager(PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        worlds = new WorldRegistry(configs, quiet);
    }

    private void setCityEnabled(String... names) {
        configs.get(ConfigFile.CONFIG).set("worlds.city-enabled", List.of(names));
    }

    private void setBlacklisted(String... names) {
        configs.get(ConfigFile.CONFIG).set("worlds.blacklisted", List.of(names));
    }

    // ==================================================================================
    // SPEC 32.2's table
    // ==================================================================================

    @Nested
    @DisplayName("what each world is for, SPEC 32.2")
    class Kinds {

        @Test
        @DisplayName("the shipped configuration matches SPEC 32.2's table exactly")
        void shippedLayoutMatchesSpec() {
            assertEquals(WorldKind.CLAIMABLE, worlds.kindOf("world"));
            assertEquals(WorldKind.BLACKLISTED, worlds.kindOf("world_nether"));
            assertEquals(WorldKind.BLACKLISTED, worlds.kindOf("world_the_end"));
            assertEquals(WorldKind.MINING, worlds.kindOf("resource"));
            assertEquals(WorldKind.MINING, worlds.kindOf("resource_nether"));
        }

        @Test
        @DisplayName("a world nobody mentioned is plain, not claimable")
        void unknownWorldIsPlain() {
            // The safe default. An operator who adds a world and forgets to configure it gets
            // a world nobody can claim, rather than one anybody can.
            assertEquals(WorldKind.PLAIN, worlds.kindOf("skyblock"));
            assertFalse(worlds.allowsCityClaims("skyblock"));
            assertFalse(worlds.allowsMiningClaims("skyblock"));
        }

        @Test
        @DisplayName("a null world is plain rather than an exception")
        void nullIsPlain() {
            // Reachable from an event whose world has been unloaded under us.
            assertEquals(WorldKind.PLAIN, worlds.kindOf(null));
        }

        @Test
        @DisplayName("world names are matched however they are capitalised")
        void caseInsensitive() {
            assertEquals(WorldKind.CLAIMABLE, worlds.kindOf("WORLD"));
            assertEquals(WorldKind.MINING, worlds.kindOf("Resource"));
        }

        @Test
        @DisplayName("blacklisted beats city-enabled when an operator lists both")
        void blacklistWins() {
            // Of the two readings of that contradiction, refusing is the one that cannot lose
            // anyone their land.
            setCityEnabled("world", "arena");
            setBlacklisted("arena");

            assertEquals(WorldKind.BLACKLISTED, worlds.kindOf("arena"));
            assertFalse(worlds.allowsCityClaims("arena"));
        }
    }

    // ==================================================================================
    // The two open decisions SPEC 32.2 closes
    // ==================================================================================

    @Nested
    @DisplayName("SPEC 20's open decisions, answered by SPEC 32.2")
    class OpenDecisions {

        @Test
        @DisplayName("decision 1: the Nether and the End are not claimable")
        void netherAndEndAreNotClaimable() {
            assertFalse(worlds.allowsCityClaims("world_nether"));
            assertFalse(worlds.allowsCityClaims("world_the_end"));
        }

        @Test
        @DisplayName("decision 4: cities live in one world, so exactly one is claimable")
        void citiesLiveInOneWorld() {
            // SPEC 20 decision 4 originally read "yes, but contiguity is per-world". SPEC 32.2
            // supersedes it: "a city and its outposts exist in world only". Asserted on the
            // shipped configuration, because that is what a server actually runs.
            assertEquals(List.of("world"), worlds.cityEnabled());
            assertEquals(1, worlds.cityEnabled().size(),
                    "shipping a second claimable world would quietly reopen decision 4");
        }
    }

    // ==================================================================================
    // SPEC 32.3
    // ==================================================================================

    @Nested
    @DisplayName("no border, SPEC 32.3")
    class NoBorder {

        @Test
        @DisplayName("world.yml ships none of SPEC 37's border keys")
        void borderKeysAreAbsent() {
            // SPEC 37 sketched a dynamic border; SPEC 32.3 rejects it outright and SPEC 41's
            // milestone repeats that. Shipping the keys anyway would be seven settings that
            // change nothing, which is what the config sweep found nineteen of.
            var world = configs.get(ConfigFile.WORLD);

            assertFalse(world.contains("border"), "world.yml has a border block");
            for (String key : List.of("border.dynamic", "border.base-radius",
                    "border.expand-per-bracket", "border.bracket-size", "border.max-radius",
                    "border.nether-ratio", "border.announce-expansion")) {
                assertFalse(world.contains(key), key + " is shipped but nothing reads it");
            }
        }

        @Test
        @DisplayName("nothing in the registry answers a question about a border")
        void registryHasNoBorderConcept() {
            String source = readSource(
                    "src/main/java/dev/civitas/core/world/WorldRegistry.java");
            long mentions = source.lines()
                    .filter(line -> !line.strip().startsWith("*") && !line.strip().startsWith("//"))
                    .filter(line -> line.contains("border") || line.contains("Border"))
                    .count();

            assertEquals(0, mentions,
                    "SPEC 32.3: the plugin does not impose, expand, or manage a border of any "
                            + "kind. Code appeared that does.");
        }
    }

    // ==================================================================================
    // SPEC 17.2 case 21
    // ==================================================================================

    @Nested
    @DisplayName("case 21: a world removed from city-enabled while claims exist there")
    class Case21 {

        @Test
        @DisplayName("it warns, naming the world")
        void warnsOnStartup() {
            setCityEnabled("world");

            List<String> stranded = worlds.auditClaimedWorlds(
                    Set.of("world", "old_world", "retired"));

            assertEquals(List.of("old_world", "retired"), stranded);
            assertTrue(warnings.stream().anyMatch(record ->
                            record.getLevel() == Level.WARNING
                                    && List.of(record.getParameters()).contains("old_world")),
                    "the operator's only other evidence is players reporting they cannot expand");
        }

        @Test
        @DisplayName("it says nothing when every claimed world is still claimable")
        void silentWhenHealthy() {
            setCityEnabled("world", "second");

            assertTrue(worlds.auditClaimedWorlds(Set.of("world", "second")).isEmpty());
            assertTrue(warnings.isEmpty(), "a clean startup must not print a warning");
        }

        @Test
        @DisplayName("a server with no claims at all is silent")
        void noClaims() {
            assertTrue(worlds.auditClaimedWorlds(Set.of()).isEmpty());
            assertTrue(warnings.isEmpty());
        }

        @Test
        @DisplayName("the claims themselves are untouched: this only reports")
        void auditIsReadOnly() {
            // SPEC 17.2 case 21: "Existing claims persist and remain protected." An audit that
            // released them would be a config edit deleting a city's land.
            setCityEnabled("world");
            List<String> first = worlds.auditClaimedWorlds(Set.of("old_world"));
            List<String> second = worlds.auditClaimedWorlds(Set.of("old_world"));

            assertEquals(first, second, "the same world is reported every startup, not once");
        }
    }

    // ==================================================================================
    // Configurable, in the M22 shape
    // ==================================================================================

    @Nested
    @DisplayName("configurable")
    class Configurable {

        @Test
        @DisplayName("adding a world to city-enabled makes it claimable")
        void cityEnabledIsRead() {
            assertFalse(worlds.allowsCityClaims("second"));
            setCityEnabled("world", "second");
            assertTrue(worlds.allowsCityClaims("second"));
        }

        @Test
        @DisplayName("adding a world to mining-claimable makes it a resource world")
        void miningClaimableIsRead() {
            assertEquals(WorldKind.PLAIN, worlds.kindOf("quarry"));
            configs.get(ConfigFile.WORLD)
                    .set("worlds.mining-claimable", List.of("resource", "quarry"));
            assertEquals(WorldKind.MINING, worlds.kindOf("quarry"));
        }

        @Test
        @DisplayName("the named worlds are read, and fall back rather than returning null")
        void namesAreReadWithFallbacks() {
            assertEquals("world", worlds.main());
            assertEquals("resource", worlds.resource());

            configs.get(ConfigFile.WORLD).set("worlds.main", "civitas");
            assertEquals("civitas", worlds.main());

            configs.get(ConfigFile.WORLD).set("worlds.main", "");
            assertEquals("world", worlds.main(),
                    "a blank name is a misconfiguration, not a world called nothing");
        }

        @Test
        @DisplayName("worlds.claimable is not shipped, because city-enabled already means that")
        void noDeadTwin() {
            // SPEC 37 lists worlds.claimable alongside SPEC 16.1's worlds.city-enabled. Two
            // names for one concept is the twin the config sweep found three of, each of which
            // left both sides inert and the value stuck at its default.
            assertFalse(configs.get(ConfigFile.WORLD).contains("worlds.claimable"),
                    "worlds.claimable duplicates worlds.city-enabled in config.yml");
        }
    }

    private static String readSource(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
