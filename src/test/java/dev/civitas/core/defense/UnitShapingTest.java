package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.city.CityColour;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SPEC 27's roster, asserted against the shipped {@code defense.yml}.
 *
 * <h2>Why this asserts a record rather than an entity</h2>
 *
 * <p>{@link DefenseSpawner} has never executed under test and cannot: MockBukkit does not
 * implement {@code setRemoveWhenFarAway}, which SPEC 30.2 case 106 requires and the spawner
 * calls on every spawn, and JUnit records an unimplemented Bukkit method as a <b>skip</b> rather
 * than a failure — so a suite in which none of SPEC 27.1's numbers was ever checked prints
 * green. {@link UnitShaping} is the pure half, and it is the half with the numbers in it, so
 * these run with no server at all.
 *
 * <p>Everything here loads the catalogue from {@code src/main/resources/defense.yml}, so it
 * asserts what a server actually gets rather than numbers invented in a test.
 */
class UnitShapingTest {

    @TempDir
    Path directory;

    private DefenseCatalogue catalogue;

    @BeforeEach
    void setUp() {
        ConfigManager configs = new ConfigManager(
                PluginResources.ofClasspath(directory.toFile(), quiet()));
        configs.loadAll();
        catalogue = new DefenseCatalogue(configs, quiet());
        catalogue.load();
    }

    private static java.util.logging.Logger quiet() {
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger("UnitShapingTest");
        logger.setUseParentHandlers(false);
        return logger;
    }

    private DefenseUnitType type(String key) {
        return catalogue.byKey(key).orElseThrow(() ->
                new AssertionError("defense.yml has no unit called " + key));
    }

    private UnitShaping shapeOf(String key) {
        return UnitShaping.of(type(key), 1, 0, catalogue.healthBonusPercentPerLevel(), false);
    }

    // ==================================================================================
    // SPEC 27.1, the stat table
    // ==================================================================================

    static Stream<Arguments> roster() {
        return Stream.of(
                Arguments.of("frost-sentry", EntityType.SNOW_GOLEM, 30d, 0d, 6000, 300),
                Arguments.of("watchtower-keeper", EntityType.ARMOR_STAND, 40d, 0d, 9000, 350),
                Arguments.of("warhound", EntityType.WOLF, 45d, 6d, 10000, 500),
                Arguments.of("archer", EntityType.SKELETON, 55d, 7d, 16000, 700),
                Arguments.of("city-guard", EntityType.ZOMBIE, 90d, 8d, 20000, 900),
                Arguments.of("colossus", EntityType.IRON_GOLEM, 220d, 16d, 55000, 2600));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roster")
    @DisplayName("SPEC 27.1's table is what a server gets")
    void statTable(String key, EntityType mob, double health, double damage, int cost,
                   int upkeep) {
        DefenseUnitType type = type(key);

        assertEquals(mob, type.mob(), key + "'s base mob");
        assertEquals(health, type.health(), 1e-9, key + "'s health");
        assertEquals(damage, type.damage(), 1e-9, key + "'s damage");
        assertEquals(0, new BigDecimal(cost).compareTo(type.cost()), key + "'s cost");
        assertEquals(0, new BigDecimal(upkeep).compareTo(type.upkeepPerDay()), key + "'s upkeep");
    }

    @Test
    @DisplayName("the roster is exactly SPEC 27's six, and none of the eight it replaced")
    void rosterIsTheNewOne() {
        assertEquals(6, catalogue.size(), "SPEC 27.1 lists seven and the seventh is M12f's "
                + "City Warden, so M12d ships six");

        for (String retired : List.of("watchman", "elite-guard", "sharpshooter", "siege-golem",
                "sentry")) {
            assertTrue(catalogue.byKey(retired).isEmpty(),
                    retired + " is from the catalogue SPEC 25.1 retired: \"Five of eight entries "
                            + "performed two jobs, and the only decision a player made was how "
                            + "much to spend.\"");
        }
    }

    @Test
    @DisplayName("cheapest first, so the shop reads as a ladder")
    void orderedByCost() {
        List<DefenseUnitType> all = catalogue.all();
        assertEquals("frost-sentry", all.get(0).key(), "6,000 C");
        assertEquals("colossus", all.get(all.size() - 1).key(), "55,000 C");
    }

    // ==================================================================================
    // The four numbers only SPEC 27.6 and 27.7 give
    // ==================================================================================

    @Test
    @DisplayName("SPEC 27.7's Colossus is 1.8x scale and cannot be knocked back")
    void colossusIsHuge() {
        UnitShaping shape = shapeOf("colossus");

        assertEquals(1.8, shape.scale(), 1e-9,
                "SPEC 25.3: \"A 1.8x Iron Golem is one attribute set, no model needed.\"");
        assertEquals(1.0, shape.knockbackResistance(), 1e-9);
        assertEquals(0.20, shape.movementSpeed(), 1e-9,
                "the slowest unit in the game, which is its counterplay");
    }

    @Test
    @DisplayName("SPEC 27.6's City Guard has 8 armour and 2 toughness, not 15 and 2")
    void guardArmourIsTheNumberInTheTable() {
        UnitShaping shape = shapeOf("city-guard");

        assertEquals(8, shape.armour(), 1e-9);
        assertEquals(2, shape.armourToughness(), 1e-9);
        // SPEC 25.3 files dyed leather under appearance. Worn armour contributes through
        // attribute modifiers, so without stripping it a full leather set adds 7 on top and a
        // 90 HP unit sits at 15 armour, which is most of the way to the unbeatable garrison
        // SPEC 25.2 Rule 1 forbids.
        assertTrue(shape.stripArmourFromEquipment(),
                "the leather must protect nothing, or SPEC 27.6's 8 is silently 15");
    }

    @Test
    @DisplayName("only units with an armour figure strip their equipment")
    void unarmouredUnitsKeepTheirEquipmentAlone() {
        assertFalse(shapeOf("archer").stripArmourFromEquipment(),
                "SPEC 27.5 gives the Archer no armour figure, so there is nothing to protect");
    }

    // ==================================================================================
    // SPEC 30.2 cases 106 to 109
    // ==================================================================================

    @Test
    @DisplayName("case 108: zombies and skeletons never burn at sunrise")
    void daylightIsSuppressedForTheUnitsThatBurn() {
        assertTrue(shapeOf("city-guard").suppressDaylightBurning(),
                "SPEC 27.6 calls setShouldBurnInDay(false) mandatory");
        assertTrue(shapeOf("archer").suppressDaylightBurning(),
                "SPEC 27.5 calls it mandatory too");
        assertFalse(shapeOf("colossus").suppressDaylightBurning(),
                "an iron golem does not burn, and pretending it does would be a lie in a flag");
    }

    @Test
    @DisplayName("case 109: the zombie unit spawns no reinforcements")
    void reinforcementsAreOff() {
        assertTrue(shapeOf("city-guard").suppressReinforcements(),
                "reinforcement spawning produces free untracked mobs no city paid for");
        assertFalse(shapeOf("warhound").suppressReinforcements());
    }

    // ==================================================================================
    // SPEC 27's city colours
    // ==================================================================================

    @Nested
    @DisplayName("the city's colour")
    class Colours {

        @Test
        @DisplayName("a unit in leather wears its city's colour")
        void leatherIsDyed() {
            UnitShaping shape = UnitShaping.of(type("city-guard"), 7, 0, 5.0, false);

            assertTrue(shape.leatherColour().isPresent(),
                    "SPEC 25.3: dyed leather is \"the single highest-value cosmetic here\"");
            assertEquals(Color.fromRGB(CityColour.of(7).value()), shape.leatherColour().get());
        }

        @Test
        @DisplayName("a unit in nothing is not given a colour it cannot show")
        void bareUnitsAreNotDyed() {
            assertTrue(shapeOf("colossus").leatherColour().isEmpty());
            assertTrue(shapeOf("frost-sentry").leatherColour().isEmpty());
        }

        @Test
        @DisplayName("SPEC 27.4's Warhound gets a dyed collar instead")
        void warhoundHasACollar() {
            assertTrue(shapeOf("warhound").collarColour().isPresent());
            assertTrue(shapeOf("city-guard").collarColour().isEmpty(),
                    "only a wolf has a collar");
        }

        @Test
        @DisplayName("the same city always gets the same colour")
        void coloursAreStable() {
            assertEquals(UnitShaping.of(type("warhound"), 12, 0, 5.0, false).collarColour(),
                    UnitShaping.of(type("warhound"), 12, 0, 5.0, false).collarColour(),
                    "a garrison that repainted itself on a restart would have taught players "
                            + "something untrue");
        }
    }

    // ==================================================================================
    // SPEC 27.3, the Watchtower Keeper
    // ==================================================================================

    @Nested
    @DisplayName("the Watchtower Keeper")
    class Keeper {

        @Test
        @DisplayName("invulnerable outside a war and 40 HP inside one")
        void invulnerabilityFollowsTheWar() {
            assertTrue(UnitShaping.of(type("watchtower-keeper"), 1, 0, 0, false).invulnerable(),
                    "SPEC 27.3: \"Invulnerable outside war\"");
            assertFalse(UnitShaping.of(type("watchtower-keeper"), 1, 0, 0, true).invulnerable(),
                    "SPEC 27.3's counterplay is that killing the Keepers first is the correct "
                            + "opening move, and it needs them to be killable");
            assertEquals(40, shapeOf("watchtower-keeper").maxHealth(), 1e-9);
        }

        @Test
        @DisplayName("nothing else in the roster is invulnerable")
        void everythingElseCanBeKilled() {
            for (DefenseUnitType type : catalogue.all()) {
                if (!type.key().equals("watchtower-keeper")) {
                    assertFalse(UnitShaping.of(type, 1, 0, 0, false).invulnerable(),
                            type.key() + " must be destructible: SPEC 25.2 Rule 3 requires "
                                    + "every unit to have a counterplay");
                }
            }
        }

        @Test
        @DisplayName("it is drawn and handed over as the same item")
        void iconAndEggAgree() {
            DefenseUnitType keeper = type("watchtower-keeper");

            assertEquals(Material.SPYGLASS, DefenseService.eggMaterialFor(keeper),
                    "an armour stand has no spawn egg, and before M12d the shop drew an iron "
                            + "golem egg and the purchase handed over a zombie one");
            assertEquals(Material.WOLF_SPAWN_EGG,
                    DefenseService.eggMaterialFor(type("warhound")),
                    "a unit whose mob has an egg still uses it");
        }
    }

    // ==================================================================================
    // SPEC 5.7's Fortification, the only thing that moves a unit after it is bought
    // ==================================================================================

    @Test
    @DisplayName("Fortification adds health and touches nothing else")
    void fortificationOnlyMovesHealth() {
        UnitShaping plain = UnitShaping.of(type("city-guard"), 1, 0, 5.0, false);
        UnitShaping maxed = UnitShaping.of(type("city-guard"), 1, 5, 5.0, false);

        assertEquals(90, plain.maxHealth(), 1e-9);
        assertEquals(90 * 1.25, maxed.maxHealth(), 1e-9, "SPEC 5.7: +5% per level, five levels");
        assertEquals(plain.attackDamage(), maxed.attackDamage(), 1e-9);
        assertEquals(plain.armour(), maxed.armour(), 1e-9);
    }

    @Test
    @DisplayName("every unit's follow range is its own, so SPEC 27.5's cap can bind")
    void followRangeIsPerUnit() {
        assertEquals(20, shapeOf("archer").followRange(), 1e-9,
                "SPEC 27.5 calls this \"hard capped\", and a vanilla skeleton follows to 16 -- "
                        + "a cap above a unit's natural reach is decoration");
        assertEquals(24, shapeOf("warhound").followRange(), 1e-9, "SPEC 27.4's chase range");
        assertEquals(32, shapeOf("watchtower-keeper").followRange(), 1e-9,
                "SPEC 27.3's detection radius");
        assertEquals(16, shapeOf("frost-sentry").followRange(), 1e-9);
    }

    @Test
    @DisplayName("the shaping is complete enough to spawn from")
    void shapingIsComplete() {
        for (DefenseUnitType type : catalogue.all()) {
            UnitShaping shape = UnitShaping.of(type, 1, 0, 5.0, false);
            assertNotNull(shape.mob(), type.key());
            assertTrue(shape.maxHealth() > 0, type.key() + " must have positive health");
            assertTrue(shape.followRange() > 0, type.key() + " must be able to see something");
        }
    }
}
