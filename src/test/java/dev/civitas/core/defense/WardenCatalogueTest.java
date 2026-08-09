package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 28's numbers, read from the shipped {@code defense.yml} rather than invented here.
 *
 * <p>{@link UnitShaping} is asserted rather than a live entity, for the reason
 * {@code UnitShapingTest} records at length: MockBukkit does not implement
 * {@code setRemoveWhenFarAway} and a Warden could never be spawned in a test at all, so a suite
 * that tried would skip rather than fail and print green having checked nothing.
 */
class WardenCatalogueTest {

    @TempDir
    Path directory;

    private ConfigManager configs;
    private DefenseCatalogue catalogue;

    @BeforeEach
    void setUp() {
        configs = new ConfigManager(PluginResources.ofClasspath(directory.toFile(), quiet()));
        configs.loadAll();
        catalogue = new DefenseCatalogue(configs, quiet());
        catalogue.load();
    }

    @Test
    @DisplayName("SPEC 28.3's stat table, exactly as it is written")
    void statTable() {
        DefenseUnitType warden = catalogue.warden().orElseThrow();

        assertEquals(EntityType.WARDEN, warden.mob());
        // "Health 500. Vanilla 500. Unchanged. This is the point of the unit."
        assertEquals(500.0, warden.health());
        // SPEC 28.4's whole tuning table: 10 rather than vanilla's 30, so an unarmoured
        // trespasser dies in two hits and a netherite raider needs twenty.
        assertEquals(10.0, warden.damage());
        // "A sprinting player can always escape."
        assertEquals(0.25, warden.speed());
        assertEquals(1.0, warden.knockbackResistance());
    }

    @Test
    @DisplayName("SPEC 28.2's price, upkeep, gate and zero capacity cost")
    void acquisitionTable() {
        DefenseUnitType warden = catalogue.warden().orElseThrow();

        assertEquals(new BigDecimal("750000"), warden.cost());
        assertEquals(new BigDecimal("8000"), warden.upkeepPerDay());
        assertEquals(new BigDecimal("750000"), catalogue.wardenCost());
        assertEquals(5, catalogue.wardenRequiredFortification());
        // SPEC 28.2: "Defense Capacity cost 0, excluded from the budget." Read through the same
        // pointsOf every other unit uses, so the exclusion needs no special case downstream.
        assertEquals(0, warden.points());
        assertEquals(0, catalogue.pointsOf(CityWarden.TYPE_KEY));
    }

    @Test
    @DisplayName("SPEC 28.3 and 28.6: darkness 10, leash 6, recovery 6 hours")
    void behaviourTable() {
        assertEquals(10.0, catalogue.wardenDarknessRadius());
        assertEquals(6.0, catalogue.wardenLeashBlocks());
        assertEquals(6, catalogue.wardenRecoveryHours());
        // SPEC 28.8's ten seconds against SPEC 25.4's thirty for everything else.
        assertEquals(10, catalogue.wardenCheckpointSeconds());
    }

    @Test
    @DisplayName("SPEC 28.3's six-block leash is not SPEC 27.8's eight")
    void twoLeashesTwoNumbers() {
        // The trap: reusing behaviour.leash-distance-blocks would let the Warden wander two
        // blocks further than SPEC 28.3 permits and nothing would look wrong.
        assertEquals(8, catalogue.leashDistance());
        assertEquals(6.0, catalogue.wardenLeashBlocks());
    }

    @Test
    @DisplayName("the Warden is findable by key and is not on the shop roster")
    void notInTheShop() {
        assertTrue(catalogue.byKey(CityWarden.TYPE_KEY).isPresent(),
                "materialisation, the leash and the upkeep sweep all resolve the type by key");
        assertTrue(catalogue.all().stream()
                        .noneMatch(type -> CityWarden.TYPE_KEY.equals(type.key())),
                "SPEC 30.3 makes the warden a sibling of units:, not a shop line");
        assertEquals(6, catalogue.size(), "SPEC 27's roster is six units, and the Warden is not one");
    }

    @Test
    @DisplayName("SPEC 28.3: the Fortification bonus does not reach the Warden")
    void fortificationDoesNotInflateIt() {
        DefenseUnitType warden = catalogue.warden().orElseThrow();
        DefenseUnitType guard = catalogue.byKey("city-guard").orElseThrow();

        // Every Warden that can legally exist is in a city at Fortification 5, so without the
        // exclusion 500 would silently be 625 and every figure in SPEC 28.5's time-to-kill table
        // would be a quarter wrong.
        assertEquals(500.0, UnitShaping.of(warden, 1, 5,
                catalogue.healthBonusPercentPerLevel(warden), false).maxHealth());

        // And it is the Warden alone: SPEC 5.7's bonus still reaches the roster.
        assertEquals(90.0 * 1.25, UnitShaping.of(guard, 1, 5,
                catalogue.healthBonusPercentPerLevel(guard), false).maxHealth());
    }

    @Test
    @DisplayName("SPEC 30.3's three declarations ship at the only values SPEC implements")
    void declarationsAreAtTheirSupportedValues() {
        // sonic-boom false, killable-in-peacetime false, killable-in-war true. SPEC 28.3's own
        // comment on the first is "NEVER set true", and the other two describe SPEC 28.6 rather
        // than switching it -- so the shipped file must not need a warning at startup.
        assertTrue(catalogue.unsupportedWardenSettings().isEmpty(),
                "the shipped defense.yml should trip no startup warning");
    }

    @Test
    @DisplayName("an operator who changes a declaration is told it did nothing")
    void unsupportedValuesAreReported() {
        configs.get(dev.civitas.config.ConfigFile.DEFENSE).set("warden.sonic-boom", true);
        configs.get(dev.civitas.config.ConfigFile.DEFENSE)
                .set("warden.killable-in-peacetime", true);
        configs.get(dev.civitas.config.ConfigFile.DEFENSE).set("warden.killable-in-war", false);

        assertEquals(java.util.List.of("warden.sonic-boom", "warden.killable-in-peacetime",
                        "warden.killable-in-war"),
                catalogue.unsupportedWardenSettings());
    }

    @Test
    @DisplayName("a server with the Warden turned off has no type to sell")
    void disabledMeansAbsent() {
        configs.get(dev.civitas.config.ConfigFile.DEFENSE).set("warden.enabled", false);
        catalogue.load();

        assertFalse(catalogue.warden().isPresent());
        assertFalse(catalogue.byKey(CityWarden.TYPE_KEY).isPresent());
        assertEquals(6, catalogue.size(), "turning the Warden off must not cost the roster");
    }

    private static java.util.logging.Logger quiet() {
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger(WardenCatalogueTest.class.getName());
        logger.setUseParentHandlers(false);
        return logger;
    }
}
