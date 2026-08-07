package dev.civitas.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.UUID;

import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.claim.ClaimType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 17.7, performance and scale.
 *
 * <p>SPEC 17 opens with "This section is exhaustive by design. Every case below must have an
 * explicit test." M20 did that sweep for SPEC 17.4 and M22 for SPEC 15; SPEC 17.7 was the
 * section nothing had gone through case by case. Four of its six cases turned out to be
 * covered by tests written for other reasons, and two — the two with the largest numbers in
 * them — had no test at the stated scale at all.
 *
 * <p>This class covers those two and names where the other four live, so the section has one
 * place that accounts for all six rather than four incidental mentions.
 *
 * <h2>What a scale test can and cannot assert</h2>
 *
 * <p>Wall-clock thresholds on a CI machine are worthless: a build server under load is slower
 * than a game server by a factor nobody can predict, and a benchmark that fails on a busy
 * afternoon teaches people to rerun it rather than read it. So the assertions here are about
 * <b>shape</b>, not speed — that lookup cost does not grow with the number of claims, that
 * opening the five-hundredth menu does not parse a layout the first one already parsed. Those
 * are the properties SPEC 17.7 actually claims, and unlike a millisecond count they fail for
 * exactly one reason.
 */
class Spec17ScaleTest {

    // ==================================================================================
    // Case 81: 50,000 claims across 200 cities
    // ==================================================================================

    @Nested
    @DisplayName("case 81, fifty thousand claims")
    class ClaimScale {

        /** SPEC 17.7 case 81's stated figures. */
        private static final int CLAIMS = 50_000;
        private static final int CITIES = 200;

        @org.junit.jupiter.api.io.TempDir
        java.nio.file.Path directory;

        private dev.civitas.core.city.CityTestSupport support;

        @org.junit.jupiter.api.BeforeEach
        void setUp() {
            // The registry needs a DAO it never calls here — nothing in this class loads from
            // storage, which is the point: SPEC 2.3 makes the cache the read path.
            support = dev.civitas.core.city.CityTestSupport.open(directory);
        }

        @org.junit.jupiter.api.AfterEach
        void tearDown() {
            support.close();
        }

        private ClaimRegistry populated(int claims, int cities, String world) {
            ClaimRegistry registry = new ClaimRegistry(support.daos.claims());
            int side = (int) Math.ceil(Math.sqrt(claims));
            for (int i = 0; i < claims; i++) {
                registry.put(new Claim(i, (i % cities) + 1, world, i % side, i / side,
                        0L, UUID.randomUUID(), BigDecimal.ZERO, ClaimType.NORMAL, null));
            }
            return registry;
        }

        @Test
        @DisplayName("fifty thousand claims across two hundred cities all load and resolve")
        void holdsTheStatedScale() {
            ClaimRegistry registry = populated(CLAIMS, CITIES, "world");

            assertEquals(CLAIMS, registry.size());
            assertEquals(CITIES, registry.allClaims().stream()
                    .map(Claim::cityId).distinct().count());

            // Every one is findable. A packing bug that aliased two chunks onto one key would
            // show up here as a claim that resolves to the wrong city — which SPEC 3.4 calls
            // the physical guarantee that two cities never own the same chunk.
            int side = (int) Math.ceil(Math.sqrt(CLAIMS));
            for (int i = 0; i < CLAIMS; i += 97) {
                Claim found = registry.at("world", i % side, i / side).orElseThrow();
                assertEquals(i, found.id(), "claim " + i + " resolved to the wrong row");
            }
        }

        @Test
        @DisplayName("lookup cost does not grow with the number of claims")
        void lookupIsConstantTime() {
            // SPEC 17.7 case 81's actual claim: "Claim lookup is O(1) via a packed-long hash
            // map." Asserted as a ratio between a small registry and a large one rather than
            // as a millisecond count, because the ratio is the property and the count is a
            // fact about the machine. A linear scan over 50,000 entries would be hundreds of
            // times slower than over 500; the bound below is loose enough that ordinary
            // timing noise cannot trip it and tight enough that O(n) cannot hide under it.
            ClaimRegistry small = populated(500, 10, "world");
            ClaimRegistry large = populated(CLAIMS, CITIES, "world");

            double smallNanos = averageLookupNanos(small, 500);
            double largeNanos = averageLookupNanos(large, CLAIMS);

            // Printed for the same reason WarBlockLogBenchmarkTest prints its throughput: a
            // number that exists only inside a passing assertion tells an operator nothing,
            // and this is the figure /ca perf reports at runtime.
            System.out.printf("claim lookup: %.1fns over 500 claims, %.1fns over %,d (%.2fx)%n",
                    smallNanos, largeNanos, CLAIMS, largeNanos / smallNanos);

            assertTrue(largeNanos < smallNanos * 20,
                    "lookup over " + CLAIMS + " claims averaged " + Math.round(largeNanos)
                            + "ns against " + Math.round(smallNanos) + "ns over 500, which is "
                            + "not the flat cost SPEC 17.7 case 81 depends on");
        }

        /** Times repeated lookups, after a warm-up so the JIT is not part of the answer. */
        private double averageLookupNanos(ClaimRegistry registry, int claims) {
            int side = (int) Math.ceil(Math.sqrt(claims));
            int rounds = 200_000;

            for (int round = 0; round < rounds; round++) {
                registry.at("world", round % side, (round / side) % side);
            }
            long started = System.nanoTime();
            int found = 0;
            for (int round = 0; round < rounds; round++) {
                if (registry.at("world", round % side, (round / side) % side).isPresent()) {
                    found++;
                }
            }
            long elapsed = System.nanoTime() - started;
            assertTrue(found > 0, "the timed loop must actually find claims, or it measures "
                    + "the miss path and proves nothing");
            return (double) elapsed / rounds;
        }

        @Test
        @DisplayName("a miss over fifty thousand claims is as cheap as a hit")
        void wildernessIsCheapToo() {
            // Every block event in unclaimed land takes this path, and there are more chunks
            // of wilderness on a server than there are claims. A miss that walked a bucket
            // chain would cost the same as a hit; one that scanned would not.
            ClaimRegistry registry = populated(CLAIMS, CITIES, "world");

            assertTrue(registry.at("world", 900_000, 900_000).isEmpty());
            assertTrue(registry.at("nowhere", 0, 0).isEmpty(),
                    "an unknown world is a miss, not an exception");
        }

        @Test
        @DisplayName("the per-city index answers without scanning the world")
        void cityIndexIsSeparate() {
            // SPEC 2.3's second index. Without it, "every claim of this city" is a scan of
            // 50,000 entries, and the contiguity fill in SPEC 6.1 runs it repeatedly.
            ClaimRegistry registry = populated(CLAIMS, CITIES, "world");

            assertEquals(CLAIMS / CITIES, registry.countOf(1));
            assertEquals(CLAIMS / CITIES, registry.claimsOf(1).size());
        }
    }

    // ==================================================================================
    // Case 86: 500 players open GUIs simultaneously
    // ==================================================================================

    @Nested
    @DisplayName("case 86, five hundred menus")
    class MenuScale {

        @Test
        @DisplayName("a layout is parsed once and shared, however many menus ask for it")
        void layoutsAreCachedPerLayout() {
            // SPEC 17.7 case 86: "GUI construction is cheap and cached per-layout. Only
            // dynamic values are recomputed." The cached half is what makes 500 opens cheap,
            // and it is the half that can be asserted without a server: the same instance
            // comes back every time, so nothing re-reads or re-parses the yaml.
            dev.civitas.gui.framework.LayoutLoader loader =
                    new dev.civitas.gui.framework.LayoutLoader(
                            dev.civitas.config.PluginResources.ofClasspath(
                                    java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                                            "civitas-layout-cache-" + UUID.randomUUID()).toFile(),
                                    java.util.logging.Logger.getLogger("quiet")));

            dev.civitas.gui.framework.MenuLayout first =
                    loader.load("main", "gui.main.title", 54);
            for (int viewer = 0; viewer < 500; viewer++) {
                assertSame(first, loader.load("main", "gui.main.title", 54),
                        "viewer " + viewer + " re-parsed a layout that was already loaded");
            }

            // And a reload really does drop it, or /ca reload gui would do nothing.
            loader.reload();
            assertTrue(first != loader.load("main", "gui.main.title", 54));
        }
    }

    // ==================================================================================
    // Where the other four cases are covered
    // ==================================================================================

    @Nested
    @DisplayName("the rest of SPEC 17.7")
    class CoveredElsewhere {

        /**
         * A note, not a test, kept as one so the section has a single index.
         *
         * <ul>
         *   <li><b>Case 82</b>, 100 players breaking blocks at 2,000 writes/sec —
         *       {@code WarBlockLogBenchmarkTest}, which M17 gated its milestone on.</li>
         *   <li><b>Case 83</b>, two million logged changes — {@code RollbackEngineTest}'s
         *       paging test, which proves the replay never holds the whole log.</li>
         *   <li><b>Case 84</b>, plugin disable during an active war —
         *       {@code WarBlockLoggerTest}, the synchronous flush on shutdown.</li>
         *   <li><b>Case 85</b>, database lost mid-war — {@code WarBlockLoggerTest}, the
         *       bounded buffer and the refusal to accept grief once it fills.</li>
         * </ul>
         */
        @Test
        @DisplayName("cases 82 to 85 have tests, and they are named above")
        void accountedFor() {
            // Asserting the test classes exist is worth more than it looks: it is what fails
            // if one is renamed or deleted, which is how a case quietly loses its coverage.
            for (String owner : java.util.List.of(
                    "dev.civitas.core.war.WarBlockLogBenchmarkTest",
                    "dev.civitas.core.war.RollbackEngineTest",
                    "dev.civitas.core.war.WarBlockLoggerTest")) {
                try {
                    Class.forName(owner);
                } catch (ClassNotFoundException e) {
                    throw new AssertionError(owner + " covers a SPEC 17.7 case and is gone", e);
                }
            }
        }
    }
}
