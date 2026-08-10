package dev.civitas.core.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.defense.DefenseCapacity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 29.2's siege budget.
 *
 * <p>{@link Published#specsOwnTable} is the substance. A ratio that is nearly right produces a
 * curve of the right shape and the wrong numbers, and would pass any test written from the
 * formula rather than from SPEC's own published figures — the same reasoning as
 * {@code OutpostCostEngineTest} and {@code WaystationCostEngineTest}.
 *
 * <p>{@link AntiFortress} asserts the property the ratio exists for rather than the arithmetic:
 * fortifying must always be worth doing, and must never put a city out of reach.
 */
class SiegeCapacityTest {

    @TempDir
    Path directory;

    private ConfigManager configs;
    private SiegeCapacity siege;
    private DefenseCapacity defense;

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("siege-capacity-" + System.nanoTime());
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        configs = new ConfigManager(PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        defense = new DefenseCapacity(100, 25, 5);
        siege = new SiegeCapacity(configs, defense);
    }

    @Nested
    @DisplayName("SPEC 29.2's published table")
    class Published {

        @Test
        @DisplayName("70, 105 and 157 at Fortification 0, 2 and 5")
        void specsOwnTable() {
            // SPEC 29.2 publishes exactly these three rows. 157 and not 157.5 is why the
            // formula says round(): 225 * 0.70 is 157.5, and SPEC's table says 157.
            assertEquals(70, siege.against(0));
            assertEquals(105, siege.against(2));
            assertEquals(157, siege.against(5));
        }

        @Test
        @DisplayName("it reads a defender capacity directly too, for a war that stored one")
        void fromAStoredCapacity() {
            assertEquals(70, siege.forDefenderCapacity(100));
            assertEquals(157, siege.forDefenderCapacity(225));
        }

        @Test
        @DisplayName("a defender with no capacity hands over no siege")
        void zeroIsZero() {
            assertEquals(0, siege.forDefenderCapacity(0));
            assertEquals(0, siege.forDefenderCapacity(-50), "and a negative cannot invert it");
        }
    }

    @Nested
    @DisplayName("the anti-fortress property, SPEC 29.2")
    class AntiFortress {

        @Test
        @DisplayName("fortifying always hands the attacker more siege")
        void fortifyingRaisesTheCounter() {
            // "The more a city fortifies, the more siege its attacker is permitted to field.
            // A city cannot outbuild the counter."
            for (int level = 0; level < 5; level++) {
                assertTrue(siege.against(level + 1) > siege.against(level),
                        "level " + (level + 1) + " did not raise the attacker's budget");
            }
        }

        @Test
        @DisplayName("but fortifying is still worth doing: the defender always keeps the edge")
        void fortifyingIsStillWorthIt() {
            // The other half, and the reason the ratio is 0.70 rather than 1.0. If the attacker
            // matched the defender, upgrading would be pure cost with no benefit.
            for (int level = 0; level <= 5; level++) {
                assertTrue(siege.against(level) < defense.capacityAt(level),
                        "the attacker matched or beat the defender at level " + level);
            }
        }

        @Test
        @DisplayName("the gap widens with every level, so upgrading compounds")
        void theEdgeGrows() {
            int atZero = defense.capacityAt(0) - siege.against(0);
            int atFive = defense.capacityAt(5) - siege.against(5);

            assertTrue(atFive > atZero,
                    "a maxed city should be further ahead than an unupgraded one, not merely ahead");
        }

        @Test
        @DisplayName("the ratio is configurable, per the hard rule on hardcoded numbers")
        void configurable() {
            configs.get(ConfigFile.DEFENSE).set("siege.budget-ratio", 1.0);

            assertEquals(defense.capacityAt(5), siege.against(5),
                    "an operator who wants siege to match defense can have it");
        }
    }

    @Nested
    @DisplayName("fitting units into the budget")
    class Fitting {

        @Test
        @DisplayName("it uses the same fits rule the defensive budget does")
        void sharesTheDefensiveRule() {
            // Two budgets that answered "does this fit" differently would be two rules, and the
            // difference would only ever show at the boundary.
            assertTrue(siege.fits(30, 40, 70), "exactly full should fit");
            assertTrue(!siege.fits(31, 40, 70), "one over should not");
        }

        @Test
        @DisplayName("SPEC 29.5's camp figures ship as SPEC states them")
        void campFigures() {
            assertEquals(12, siege.maxCampDistanceChunks());
            assertEquals(200, siege.campHealth());
            assertEquals(40, siege.campDestroyPoints());
            assertEquals(50, siege.campRebuildCostPercent());
        }
    }
}
