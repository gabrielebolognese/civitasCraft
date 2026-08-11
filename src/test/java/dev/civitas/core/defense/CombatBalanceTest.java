package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.defense.CombatModel.Defender;
import dev.civitas.core.defense.CombatModel.Engagement;
import dev.civitas.core.defense.CombatModel.Gear;
import dev.civitas.core.defense.CombatModel.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 25.2 Rule 1, checked over every garrison the budget allows.
 *
 * <p>"A defending city's full garrison, at any Fortification level, must be beatable by an
 * attacking force equal in size to the defender's active member count, equipped with good gear and
 * coordinating. <b>If a configuration exists where this is false, that configuration is a bug.</b>"
 *
 * <p>The last sentence is why this enumerates rather than samples. PLAN's M20a asks for three live
 * trials at each of three levels; three trials check three compositions, and the rule is stated
 * over all of them.
 *
 * <h2>The finding</h2>
 *
 * <p>Rule 1 holds — and only under {@link Engagement#KITE}. A garrison that an attacker chooses to
 * stand and trade with, on the defender's own ground, beats an equal-numbered force at every
 * Fortification level. That is not a bug in the numbers: SPEC 27 writes a counterplay for each unit
 * and SPEC 25.2 Rule 3 makes having one a shipping gate, so "coordinating" in Rule 1 is load-bearing
 * rather than decorative. Both regimes are asserted here so the distinction cannot quietly erode.
 */
class CombatBalanceTest {

    @TempDir
    Path directory;

    private DefenseCatalogue catalogue;
    private DefenseCapacity capacity;

    /** SPEC 11.3's floor: a city needs three members to be at war at all. */
    private static final int SMALLEST_DEFENDER = 3;

    /**
     * How many defenders can reach the attackers at one place.
     *
     * <p>Derived rather than chosen: SPEC 27.8 caps a chunk at
     * {@code placement.max-units-per-chunk}, and a fight happens across roughly the chunk the
     * attackers stand in and its immediate neighbours. Three chunks' worth is the figure, and it
     * is a model constant that moves with the config rather than a number in a test.
     */
    private int concurrent() {
        return catalogue.maxUnitsPerChunk() * 3;
    }

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("combat-balance-" + System.nanoTime());
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        ConfigManager configs = new ConfigManager(
                PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        catalogue = new DefenseCatalogue(configs, quiet);
        catalogue.load();
        capacity = new DefenseCapacity(catalogue.baseCapacity(),
                catalogue.capacityPerFortificationLevel(), 5);
    }

    @Nested
    @DisplayName("the model reproduces SPEC's own published arithmetic")
    class Calibration {

        @Test
        @DisplayName("every cell of SPEC 28.4's damage table")
        void spec284() {
            // A model that disagreed with the only combat arithmetic SPEC publishes would be
            // measuring something else, so this is the first thing asserted rather than the last.
            assertEquals(10.0, CombatModel.afterArmour(10, Gear.unarmored()), 0.05);
            assertEquals(6.0, CombatModel.afterArmour(10, Gear.fullIron()), 0.05);
            assertEquals(2.0, CombatModel.afterArmour(10, Gear.fullDiamondProtTwo()), 0.05);
            assertEquals(1.0, CombatModel.afterArmour(10, Gear.fullNetheriteProtFour()), 0.05);

            // And the vanilla row, which is the one SPEC 28.4 rejects.
            assertEquals(30.0, CombatModel.afterArmour(30, Gear.unarmored()), 0.05);
            assertEquals(26.4, CombatModel.afterArmour(30, Gear.fullIron()), 0.1);
            assertEquals(10.2, CombatModel.afterArmour(30, Gear.fullDiamondProtTwo()), 0.1);
            assertEquals(4.8, CombatModel.afterArmour(30, Gear.fullNetheriteProtFour()), 0.1);
        }

        @Test
        @DisplayName("SPEC 28.5's sword rows: 28.4s and 34.7s against a 500 HP Warden")
        void spec285() {
            assertEquals(28.4, 500 / CombatModel.dps(Gear.fullNetheriteProtFour()), 0.1);
            assertEquals(34.7, 500 / CombatModel.dps(Gear.fullDiamondProtTwo()), 0.1);
        }
    }

    @Nested
    @DisplayName("SPEC 25.2 Rule 1, at Fortification 0, 2 and 5")
    class RuleOne {

        @Test
        @DisplayName("every single-unit garrison falls to a coordinating equal force")
        void monoculturesFall() {
            for (int level : new int[] {0, 2, 5}) {
                int budget = capacity.capacityAt(level);
                for (List<Defender> garrison
                        : CombatModel.monocultures(budget, catalogue)) {
                    assertTrue(CombatModel.clearsGarrison(SMALLEST_DEFENDER,
                                    Gear.fullDiamondProtTwo(), garrison, Engagement.KITE,
                                    concurrent()),
                            "Fortification " + level + ": " + garrison.size() + "x "
                                    + garrison.get(0).key() + " beat " + SMALLEST_DEFENDER
                                    + " attackers");
                }
            }
        }

        @Test
        @DisplayName("and so does the deadliest mix the budget can buy")
        void deadliestFalls() {
            // The composition a defender optimising for the fight would actually build, which is
            // the one the rule has to survive rather than the one that is convenient to test.
            for (int level : new int[] {0, 2, 5}) {
                List<Defender> garrison =
                        CombatModel.deadliest(capacity.capacityAt(level), catalogue);

                assertTrue(CombatModel.clearsGarrison(SMALLEST_DEFENDER,
                                Gear.fullDiamondProtTwo(), garrison, Engagement.KITE,
                                concurrent()),
                        "Fortification " + level + ": the deadliest " + garrison.size()
                                + "-unit garrison beat " + SMALLEST_DEFENDER + " attackers");
            }
        }

        @Test
        @DisplayName("a larger city's own member count scales with it, so the rule holds there too")
        void largerDefenders() {
            // Rule 1 pins the force to the defender's member count, and a bigger city does not
            // get a bigger garrison — Fortification does. So more members is strictly easier and
            // the three-member case above is the binding one. Asserted rather than argued.
            for (int members : new int[] {3, 5, 10, 15}) {
                List<Defender> garrison = CombatModel.deadliest(capacity.capacityAt(5), catalogue);

                assertTrue(CombatModel.clearsGarrison(members, Gear.fullDiamondProtTwo(),
                                garrison, Engagement.KITE, concurrent()),
                        members + " members could not take a maxed garrison");
            }
        }

        @Test
        @DisplayName("fortifying still buys the defender time, or it would be worth nothing")
        void fortifyingBuysTime() {
            double previous = 0;
            for (int level = 0; level <= 5; level++) {
                Outcome outcome = CombatModel.resolve(SMALLEST_DEFENDER,
                        Gear.fullDiamondProtTwo(),
                        CombatModel.deadliest(capacity.capacityAt(level), catalogue),
                        Engagement.KITE);

                assertTrue(outcome.secondsToClear() > previous,
                        "Fortification " + level + " did not take longer to clear than "
                                + (level - 1));
                previous = outcome.secondsToClear();
            }
        }
    }

    @Nested
    @DisplayName("the measured margins, recorded so drift is visible")
    class Margins {

        @Test
        @DisplayName("the numbers M20a actually measured, at each level")
        void recordedMargins() {
            // These are the figures in COMBAT_BALANCE.md. Asserted rather than only written down,
            // because a balance result nothing checks is a balance result that stops being true
            // the first time somebody retunes a unit.
            //
            //  Level  garrison            per-engagement clear  survive  margin
            //  0      8x warhound         8.3s                  32.6s    3.9x
            //  2      12x warhound        9.4s                  25.3s    2.7x
            //  5      19 mixed            9.4s                  25.3s    2.7x
            double[] expectedClear = {8.3, 9.4, 9.4};
            int[] levels = {0, 2, 5};

            for (int i = 0; i < levels.length; i++) {
                List<Defender> garrison =
                        CombatModel.deadliest(capacity.capacityAt(levels[i]), catalogue);
                List<Defender> group =
                        garrison.subList(0, Math.min(garrison.size(), concurrent()));
                Outcome outcome = CombatModel.resolve(SMALLEST_DEFENDER,
                        Gear.fullDiamondProtTwo(), group, Engagement.KITE);

                assertEquals(expectedClear[i], outcome.secondsToClear(), 0.2,
                        "the clear time at Fortification " + levels[i] + " has moved");
                assertTrue(outcome.secondsToDie() / outcome.secondsToClear() >= 2.5,
                        "Fortification " + levels[i] + " left less than a 2.5x margin");
            }
        }

        @Test
        @DisplayName("the deadliest build is Warhound spam, and it is the un-kiteable one")
        void warhoundSpam() {
            // The structural fact worth knowing, even though Rule 1 survives it. The Warhound has
            // the best damage per point in the roster (6/12) AND is the only unit SPEC 27.4 makes
            // faster than a sprinting player — so the optimal defensive build is also the one
            // immune to the counterplay every other unit has.
            List<Defender> deadliest =
                    CombatModel.deadliest(capacity.capacityAt(5), catalogue);

            assertEquals("warhound", deadliest.get(0).key());
            assertTrue(deadliest.get(0).speed() > CombatModel.SPRINT_SPEED,
                    "and it cannot be walked away from");
        }

        @Test
        @DisplayName("without healing the rule still holds, but on a much thinner margin")
        void healingIsLoadBearing() {
            // The model's most consequential assumption, made visible. Dry, an equal force still
            // wins each engagement — 12.6s of survival against 9.4s of work at Fortification 5 —
            // but 1.3x is close enough that a single mistake loses it, where 2.7x is not.
            List<Defender> garrison = CombatModel.deadliest(capacity.capacityAt(5), catalogue);
            List<Defender> group = garrison.subList(0, Math.min(garrison.size(), concurrent()));

            Outcome dry = CombatModel.resolve(SMALLEST_DEFENDER,
                    Gear.fullDiamondProtTwo().withoutHealing(), group, Engagement.KITE);

            assertTrue(dry.attackersWin(), "Rule 1 must not depend on golden apples alone");
            assertTrue(dry.secondsToDie() / dry.secondsToClear() < 2.0,
                    "and the margin without them should be visibly thinner");
        }
    }

    @Nested
    @DisplayName("the other regime, and why the rule needs the word coordinating")
    class StandAndFight {

        @Test
        @DisplayName("a maxed garrison fought head-on beats an equal force")
        void headOnLoses() {
            // This is the finding, recorded rather than smoothed. SPEC 25.2 Rule 1 says the
            // garrison must be beatable by a force "equipped with good gear AND COORDINATING",
            // and this is what the second half of that phrase is buying: three players who walk
            // into five Colossi and trade blows lose, and SPEC 27.7's counterplay is to not.
            Outcome outcome = CombatModel.resolve(SMALLEST_DEFENDER, Gear.fullDiamondProtTwo(),
                    CombatModel.deadliest(capacity.capacityAt(5), catalogue),
                    Engagement.STAND_AND_FIGHT);

            assertFalse(outcome.attackersWin(),
                    "if this passes, the roster has drifted and Rule 1 no longer needs "
                            + "coordination — which would mean the counterplay SPEC 27 writes "
                            + "for every unit has stopped mattering");
        }

        @Test
        @DisplayName("the slow units are exactly the ones that can be out-run")
        void whoCanBeKited() {
            // SPEC 27's speeds are the counterplay made numeric. If a future retune pushed the
            // Colossus above a sprint, the kiting regime above would silently stop applying and
            // Rule 1 would fail with no test naming why.
            List<String> outrunnable = new ArrayList<>();
            for (DefenseUnitType type : catalogue.all()) {
                if (type.speed() < CombatModel.SPRINT_SPEED) {
                    outrunnable.add(type.key());
                }
            }

            assertTrue(outrunnable.contains("colossus"),
                    "SPEC 27.7 names walking away as the Colossus's counterplay");
            assertTrue(outrunnable.contains("city-guard"),
                    "SPEC 27.6: the City Guard is 'slow enough to kite'");
            assertFalse(outrunnable.contains("warhound"),
                    "SPEC 27.4 makes the Warhound faster than a sprinting player on purpose");
        }
    }

    @Nested
    @DisplayName("the City Warden, SPEC 28")
    class Warden {

        @Test
        @DisplayName("it does not make a maxed city unbeatable, which is its whole design")
        void wardenIsNotAWall() {
            // SPEC 28.1: the Warden is "a prestige unlock and a landmark, not a weapon", and
            // SPEC 28.5 puts a solo netherite raider at 28.4 seconds to remove it. Added on top
            // of a full garrison, because it costs nothing against SPEC 25.5's budget.
            List<Defender> garrison =
                    new ArrayList<>(CombatModel.deadliest(capacity.capacityAt(5), catalogue));
            catalogue.warden().ifPresent(warden -> garrison.add(Defender.of(warden)));

            assertTrue(CombatModel.clearsGarrison(SMALLEST_DEFENDER, Gear.fullDiamondProtTwo(),
                            garrison, Engagement.KITE, concurrent()),
                    "a maxed city plus its Warden must still be takeable, or nobody declares");
        }

        @Test
        @DisplayName("SPEC 28.4's asymmetry: lethal to a trespasser, nearly harmless to a raider")
        void asymmetry() {
            // The entire argument of SPEC 28.4 in one assertion: "It punishes casual intrusion
            // and does not decide wars."
            double raw = catalogue.warden().map(DefenseUnitType::damage).orElse(10.0);

            assertTrue(20 / CombatModel.afterArmour(raw, Gear.unarmored()) <= 2.5,
                    "an unarmored trespasser should die in about two hits");
            assertTrue(20 / CombatModel.afterArmour(raw, Gear.fullNetheriteProtFour()) >= 15,
                    "a netherite raider should need fifteen or more");
        }
    }

}
