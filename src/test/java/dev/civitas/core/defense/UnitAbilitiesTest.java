package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.defense.DefenseUnitType.Ability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 27.2 to 27.7's abilities, and the two numbers that <b>are</b> a unit's counterplay.
 *
 * <p>SPEC 25.2 Rule 3 makes counterplay a shipping gate: "A unit without a written counterplay
 * does not ship." Two of these are one arithmetic slip from being removed while the code still
 * reads correctly, so they are asserted here in isolation rather than inferred from a fight.
 */
class UnitAbilitiesTest {

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
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("UnitAbilitiesTest");
        logger.setUseParentHandlers(false);
        return logger;
    }

    private DefenseUnitType type(String key) {
        return catalogue.byKey(key).orElseThrow();
    }

    // ==================================================================================
    // SPEC 27.7, and the counterplay it would be easiest to delete
    // ==================================================================================

    @Nested
    @DisplayName("the Colossus's arrow resistance")
    class ArrowResistance {

        @Test
        @DisplayName("an arrow at the threshold is not reduced at all")
        void atThresholdIsUntouched() {
            // SPEC 27.7's counterplay, in full: the resistance is "explicitly capped at 8
            // damage so a fully charged Power V bow still hurts it". Reduce everything by 80%
            // instead -- the obvious reading, and the one that looks fine in code -- and the
            // tank has no ranged answer, which is a SPEC 25.2 Rule 1 failure.
            assertEquals(8.0, UnitAbilities.arrowDamageAfterResist(type("colossus"), 8.0), 1e-9);
            assertEquals(25.0, UnitAbilities.arrowDamageAfterResist(type("colossus"), 25.0),
                    1e-9, "a Power V shot lands whole");
        }

        @Test
        @DisplayName("an arrow just under the threshold is reduced by 80%")
        void underThresholdIsReduced() {
            assertEquals(1.58, UnitAbilities.arrowDamageAfterResist(type("colossus"), 7.9),
                    1e-9);
            assertEquals(1.0, UnitAbilities.arrowDamageAfterResist(type("colossus"), 5.0), 1e-9);
        }

        @Test
        @DisplayName("the step at exactly 8 is deliberate and is nearly five times")
        void theDiscontinuityIsReal() {
            double under = UnitAbilities.arrowDamageAfterResist(type("colossus"), 7.999);
            double over = UnitAbilities.arrowDamageAfterResist(type("colossus"), 8.0);

            assertTrue(over > under * 4,
                    "a threshold, not a scale. SPEC 27.7 rewards a charged bow and refuses a "
                            + "stream of weak arrows, and a smooth curve would do neither");
        }

        @Test
        @DisplayName("a unit with no resistance takes an arrow whole")
        void everythingElseIsUnprotected() {
            assertEquals(3.0, UnitAbilities.arrowDamageAfterResist(type("city-guard"), 3.0),
                    1e-9);
        }
    }

    @Nested
    @DisplayName("the Colossus's slam")
    class Slam {

        @Test
        @DisplayName("three blocks, and four damage")
        void radiusAndDamage() {
            assertTrue(UnitAbilities.withinSlam(type("colossus"), 3.0));
            assertFalse(UnitAbilities.withinSlam(type("colossus"), 3.01));
            assertEquals(4.0, UnitAbilities.slamDamage(type("colossus")), 1e-9);
        }

        @Test
        @DisplayName("nothing else slams")
        void onlyTheColossus() {
            assertFalse(UnitAbilities.withinSlam(type("city-guard"), 0.5));
        }
    }

    // ==================================================================================
    // SPEC 27.5, the Archer
    // ==================================================================================

    @Nested
    @DisplayName("the Archer in close quarters")
    class FireRate {

        @Test
        @DisplayName("fire rate halves while an enemy is within five blocks")
        void halvedInMelee() {
            assertEquals(2.0, UnitAbilities.fireDelayMultiplier(type("archer"), 4.0), 1e-9,
                    "half the rate is twice the delay, which is what the caller waits");
            assertEquals(2.0, UnitAbilities.fireDelayMultiplier(type("archer"), 5.0), 1e-9);
        }

        @Test
        @DisplayName("and is untouched at range, which is where it is meant to be")
        void unpenalisedAtRange() {
            assertEquals(1.0, UnitAbilities.fireDelayMultiplier(type("archer"), 5.01), 1e-9);
            assertEquals(1.0, UnitAbilities.fireDelayMultiplier(type("archer"),
                    Double.MAX_VALUE), 1e-9, "nobody nearby at all");
        }

        @Test
        @DisplayName("a unit with no penalty is never slowed")
        void onlyTheArcher() {
            assertEquals(1.0, UnitAbilities.fireDelayMultiplier(type("city-guard"), 0.1), 1e-9);
        }
    }

    // ==================================================================================
    // SPEC 27.6, the City Guard
    // ==================================================================================

    @Test
    @DisplayName("the alert network reaches three chunks, stated in blocks")
    void alertNetwork() {
        assertTrue(UnitAbilities.hasAlertNetwork(type("city-guard")));
        assertEquals(48.0, UnitAbilities.alertNetworkRadiusBlocks(type("city-guard")), 1e-9,
                "SPEC 27.6 states it in chunks and every distance a listener has is in blocks");
        assertEquals(20_000L, UnitAbilities.alertNetworkMillis(type("city-guard")));

        assertFalse(UnitAbilities.hasAlertNetwork(type("archer")),
                "SPEC 27.6 gives the network to the City Guard and to nothing else");
    }

    // ==================================================================================
    // SPEC 30.3's levels against Bukkit's amplifiers
    // ==================================================================================

    @Test
    @DisplayName("SPEC's levels become Bukkit's amplifiers exactly once")
    void levelsBecomeAmplifiers() {
        DefenseUnitType sentry = type("frost-sentry");

        // Slowness II is amplifier 1. Two vocabularies for one number, silently off by one, is
        // how the superseded catalogue ended up shipping amplifier 1 under a key called
        // amplifier while SPEC called the same thing level 2.
        assertEquals(1, UnitAbilities.amplifierOf(sentry, Ability.SLOWNESS_LEVEL));
        assertEquals(0, UnitAbilities.amplifierOf(sentry, Ability.MINING_FATIGUE_LEVEL),
                "Mining Fatigue I is amplifier 0");
        assertEquals(60, UnitAbilities.ticksOf(sentry, Ability.SLOWNESS_SECONDS),
                "three seconds");
        assertEquals(40, UnitAbilities.ticksOf(sentry, Ability.MINING_FATIGUE_SECONDS));
    }

    @Test
    @DisplayName("the Frost Sentry debuffs and the Warhound bites, and nothing else does either")
    void whoAppliesWhat() {
        assertTrue(UnitAbilities.isFrostProjectile(type("frost-sentry")));
        assertFalse(UnitAbilities.isFrostProjectile(type("archer")),
                "an archer's arrow is an arrow");

        assertTrue(UnitAbilities.bites(type("warhound")));
        assertEquals(0, UnitAbilities.amplifierOf(type("warhound"),
                Ability.BITE_SLOWNESS_LEVEL), "Slowness I");
        assertEquals(40, UnitAbilities.ticksOf(type("warhound"),
                Ability.BITE_SLOWNESS_SECONDS));
        assertFalse(UnitAbilities.bites(type("city-guard")));
    }

    @Test
    @DisplayName("SPEC 27.4's Warhound hunts the weakest, not the nearest")
    void warhoundPriority() {
        assertEquals(DefenseUnitType.TargetPriority.LOWEST_HEALTH,
                type("warhound").targetPriority(),
                "\"Prioritises the lowest-health valid target rather than the nearest\" is what "
                        + "makes it feel like a hunting animal and punishes retreating unhealed");
        assertEquals(DefenseUnitType.TargetPriority.NEAREST,
                type("city-guard").targetPriority());
    }
}
