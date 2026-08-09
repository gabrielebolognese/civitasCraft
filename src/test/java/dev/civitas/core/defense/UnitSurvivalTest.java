package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 30.2 cases 107 and 112, and the one place in this milestone where a stated counterplay
 * can be removed by an implementation that looks entirely correct.
 *
 * <p>Case 107 says to cancel a snow golem's water and melting damage. Case 112 says a unit that
 * walks into lava is put back at its post rather than deleted. Read together, the obvious
 * implementation cancels environmental damage — and SPEC 27.2's counterplay for the Frost Sentry
 * is, in full: <b>"Melts in lava or near fire."</b> SPEC 25.2 Rule 3 makes that a shipping gate.
 */
class UnitSurvivalTest {

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
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("UnitSurvivalTest");
        logger.setUseParentHandlers(false);
        return logger;
    }

    private DefenseUnitType type(String key) {
        return catalogue.byKey(key).orElseThrow();
    }

    // ==================================================================================
    // SPEC 30.2 case 107, against SPEC 27.2's counterplay
    // ==================================================================================

    @Nested
    @DisplayName("case 107, the Frost Sentry in weather")
    class Weather {

        @Test
        @DisplayName("rain and water do not kill it")
        void weatherIsCancelled() {
            assertTrue(UnitSurvival.isWeatherDeath(EntityType.SNOW_GOLEM, DamageCause.MELTING),
                    "SPEC 30.2 case 107: \"or every sentry dies in the first storm\"");
            assertTrue(UnitSurvival.isWeatherDeath(EntityType.SNOW_GOLEM,
                    DamageCause.DROWNING));
        }

        @Test
        @DisplayName("and fire and lava still do, which is the whole of its counterplay")
        void heatIsNotCancelled() {
            // SPEC 27.2: "Melts in lava or near fire. Any attacker who spends five seconds on
            // it removes it." Cancel these and the unit becomes immortal to exactly the thing
            // SPEC names as the way to beat it, which is a Rule 3 violation and a milestone
            // blocker -- and it would look like a correct reading of case 107.
            assertFalse(UnitSurvival.isWeatherDeath(EntityType.SNOW_GOLEM, DamageCause.LAVA));
            assertFalse(UnitSurvival.isWeatherDeath(EntityType.SNOW_GOLEM, DamageCause.FIRE));
            assertFalse(UnitSurvival.isWeatherDeath(EntityType.SNOW_GOLEM,
                    DamageCause.FIRE_TICK));
            assertFalse(UnitSurvival.isWeatherDeath(EntityType.SNOW_GOLEM,
                    DamageCause.ENTITY_ATTACK), "two arrows still kill it");
        }

        @Test
        @DisplayName("the rule is about snow golems, not about being one of ours")
        void byMobNotByKey() {
            assertFalse(UnitSurvival.isWeatherDeath(EntityType.ZOMBIE, DamageCause.DROWNING),
                    "a City Guard immune to water would be a different bug");
        }
    }

    // ==================================================================================
    // SPEC 30.2 case 112
    // ==================================================================================

    @Nested
    @DisplayName("case 112, terrain")
    class DeathSave {

        @Test
        @DisplayName("only a unit that can walk is saved from where it walked")
        void onlyUnitsThatPathfind() {
            // Case 112's premise is a unit that "pathfinds into lava or off a cliff". A unit at
            // zero speed pathfinds nowhere, so nothing it is standing in got there by accident.
            // This is the guard that keeps SPEC 27.2's counterplay alive.
            assertTrue(UnitSurvival.canBeSaved(type("city-guard")));
            assertTrue(UnitSurvival.canBeSaved(type("warhound")));
            assertTrue(UnitSurvival.canBeSaved(type("colossus")),
                    "SPEC 27.7 makes it 1.8x and it does not fit where a normal golem walks");

            assertFalse(UnitSurvival.canBeSaved(type("frost-sentry")),
                    "static, and its stated counterplay is that it melts");
            assertFalse(UnitSurvival.canBeSaved(type("watchtower-keeper")),
                    "static, and killing the Keepers first is the attacker's opening move");
        }

        @Test
        @DisplayName("lava, fire, falling and the void count; a player's sword does not")
        void whatCountsAsTerrain() {
            assertTrue(UnitSurvival.isTerrain(DamageCause.LAVA));
            assertTrue(UnitSurvival.isTerrain(DamageCause.FALL));
            assertTrue(UnitSurvival.isTerrain(DamageCause.VOID));
            assertTrue(UnitSurvival.isTerrain(DamageCause.SUFFOCATION));

            assertFalse(UnitSurvival.isTerrain(DamageCause.ENTITY_ATTACK));
            assertFalse(UnitSurvival.isTerrain(DamageCause.PROJECTILE));
            assertFalse(UnitSurvival.isTerrain(DamageCause.ENTITY_EXPLOSION),
                    "a player's TNT is a player's, and SPEC 26.4 says only players kill units");
        }

        @Test
        @DisplayName("once an hour, so pushing one in twice still works")
        void oncePerHour() {
            UnitSurvival survival = new UnitSurvival();
            long hour = 3_600_000L;

            assertTrue(survival.claimSave(1, 0L, hour));
            assertFalse(survival.claimSave(1, hour - 1, hour),
                    "a unit saved a second time in the hour would be terrain-proof, and a "
                            + "player who keeps at it has to be able to win");
            assertTrue(survival.claimSave(1, hour, hour));
        }

        @Test
        @DisplayName("the ledger is per unit, not per server")
        void perUnit() {
            UnitSurvival survival = new UnitSurvival();

            assertTrue(survival.claimSave(1, 0L, 3_600_000L));
            assertTrue(survival.claimSave(2, 0L, 3_600_000L),
                    "one guard falling off a cliff must not condemn the one beside it");
        }

        @Test
        @DisplayName("it comes back at a fifth of its health, never at none")
        void savedHealth() {
            assertEquals(18.0, UnitSurvival.savedHealth(90, 20), 1e-9);
            assertEquals(44.0, UnitSurvival.savedHealth(220, 20), 1e-9);
            assertTrue(UnitSurvival.savedHealth(90, 0) >= 1.0,
                    "a save that set health to zero would be a death with extra steps");
        }

        @Test
        @DisplayName("a forgotten unit starts its hour again")
        void forgetting() {
            UnitSurvival survival = new UnitSurvival();

            assertTrue(survival.claimSave(1, 0L, 3_600_000L));
            survival.forget(1);
            assertTrue(survival.claimSave(1, 1L, 3_600_000L));
        }
    }

    @Test
    @DisplayName("both of case 112's numbers are config, not constants")
    void numbersAreConfigured() {
        // Neither has a home in SPEC 30.3; both are stated in case 112 itself, so they are
        // shipped rather than hardcoded.
        assertEquals(20.0, catalogue.deathSaveHealthPercent(), 1e-9);
        assertEquals(3_600_000L, catalogue.deathSaveCooldownMillis());
    }
}
