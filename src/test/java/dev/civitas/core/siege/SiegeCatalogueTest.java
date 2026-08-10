package dev.civitas.core.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 29.3's roster, asserted against the table SPEC prints rather than against the config file
 * that was written from it — the same reasoning as {@code OutpostCostEngineTest}: a figure that is
 * nearly right passes any test derived from the same source as the code.
 *
 * <p>{@link Breacher} is the class's reason for existing. The 2x/0.6x asymmetry is SPEC 29.3's
 * "key balance lever", and getting it backwards produces a unit that ignores garrisons and
 * slaughters players — the exact opposite of what it is for.
 */
class SiegeCatalogueTest {

    @TempDir
    Path directory;

    private SiegeCatalogue catalogue;

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("siege-catalogue-" + System.nanoTime());
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        ConfigManager configs = new ConfigManager(
                PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        catalogue = new SiegeCatalogue(configs, quiet);
        catalogue.load();
    }

    private SiegeUnitType unit(String key) {
        return catalogue.byKey(key).orElseThrow(() -> new AssertionError("no siege unit " + key));
    }

    @Nested
    @DisplayName("SPEC 29.3's published table")
    class Roster {

        @Test
        @DisplayName("four units, and only four")
        void fourUnits() {
            // "Three units plus one support. Deliberately fewer than the defensive roster."
            assertEquals(4, catalogue.size());
        }

        @Test
        @DisplayName("every stat matches the row SPEC prints")
        void statsMatchSpec() {
            SiegeUnitType beast = unit("siege-beast");
            assertEquals(180, beast.health(), 0.001);
            assertEquals(14, beast.damage(), 0.001);
            assertEquals(40, beast.points());
            assertEquals(0, beast.cost().compareTo(new BigDecimal("40000")));

            SiegeUnitType breacher = unit("breacher");
            assertEquals(70, breacher.health(), 0.001);
            assertEquals(9, breacher.damage(), 0.001);
            assertEquals(20, breacher.points());
            assertEquals(0, breacher.cost().compareTo(new BigDecimal("18000")));

            SiegeUnitType archer = unit("siege-archer");
            assertEquals(50, archer.health(), 0.001);
            assertEquals(7, archer.damage(), 0.001);
            assertEquals(16, archer.points());
            assertEquals(22, archer.range(), 0.001, "SPEC 29.3 gives it a 22-block crossbow");

            SiegeUnitType bearer = unit("banner-bearer");
            assertEquals(60, bearer.health(), 0.001);
            assertEquals(25, bearer.points());
            assertEquals(0, bearer.cost().compareTo(new BigDecimal("25000")));
        }
    }

    @Nested
    @DisplayName("the Breacher's asymmetry, SPEC 29.3's key balance lever")
    class Breacher {

        @Test
        @DisplayName("2x against a defense unit, 0.6x against a player")
        void asymmetricDamage() {
            // "It exists to break a garrison, not to kill players. It makes the attacker's mob
            // budget a tool against the defender's mob budget without turning wars into
            // mob-versus-mob spectacles."
            SiegeUnitType breacher = unit("breacher");

            assertEquals(18.0, breacher.damageAgainst(true), 0.001, "2x against a unit");
            assertEquals(5.4, breacher.damageAgainst(false), 0.001, "0.6x against a player");
            assertTrue(breacher.damageAgainst(true) > breacher.damageAgainst(false),
                    "getting this backwards makes a unit that ignores garrisons and kills people");
        }

        @Test
        @DisplayName("SPEC 29.4: it is the ONLY unit that engages defense units")
        void onlyTheBreacherFightsUnits() {
            // "Only the Breacher engages defense units directly. Others prioritise players."
            // The single carve-out from SPEC 26.4's "units never fight units", narrow on purpose
            // so a siege never becomes two mob armies resolving a war with no players present.
            assertTrue(unit("breacher").engagesDefenseUnits());

            for (SiegeUnitType type : catalogue.all()) {
                if (!"breacher".equals(type.key())) {
                    assertFalse(type.engagesDefenseUnits(),
                            type.key() + " should not fight defense units");
                }
            }
        }
    }

    @Nested
    @DisplayName("the Banner Bearer, SPEC 29.3")
    class Support {

        @Test
        @DisplayName("it deals no damage at all and buffs instead")
        void dealsNothing() {
            // "A support unit that buffs players rather than fighting keeps the emphasis where
            // it belongs, on the players."
            SiegeUnitType bearer = unit("banner-bearer");

            assertEquals(0, bearer.damage(), 0.001);
            assertEquals(0, bearer.damageAgainst(true), 0.001);
            assertEquals(0, bearer.damageAgainst(false), 0.001);
            assertTrue(bearer.isSupport());
            assertEquals(12, bearer.buffRadius(), "SPEC 29.3 buffs attackers within 12 blocks");
        }

        @Test
        @DisplayName("and it is the only support unit")
        void theOnlySupport() {
            assertEquals(1, catalogue.all().stream().filter(SiegeUnitType::isSupport).count());
        }
    }

    @Nested
    @DisplayName("the budget")
    class Points {

        @Test
        @DisplayName("a Fortification-0 defender's 70 points buys one Beast and not two")
        void whatSeventyPointsBuys() {
            // The concrete meaning of SPEC 29.2's smallest budget: 40 for a Beast leaves 30,
            // which is a Breacher and nothing else. A second Beast is out of reach.
            assertEquals(40, unit("siege-beast").points());
            assertTrue(unit("siege-beast").points() * 2 > 70);
            assertTrue(unit("siege-beast").points() + unit("breacher").points() <= 70);
        }
    }
}
