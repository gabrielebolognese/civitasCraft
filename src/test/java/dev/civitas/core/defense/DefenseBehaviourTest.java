package dev.civitas.core.defense;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SPEC 12.3 behaviour table, one row per test.
 *
 * <p>The first row is the one this whole class exists for: <b>a visitor in a claim during
 * peacetime is ignored completely</b>. SPEC 1.4 says combat is a scheduled event rather than
 * the default state of the world, and a guard that attacks passers-by would quietly undo
 * that for every defended city on the server.
 */
class DefenseBehaviourTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private DefenseCatalogue catalogue;
    private DefenseBehaviour behaviour;
    private City city;
    private UUID member;
    private UUID stranger;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        catalogue = new DefenseCatalogue(support.configs, CityTestSupport.quietLogger());
        catalogue.load();
        behaviour = new DefenseBehaviour(catalogue, support.registry);

        UUID mayor = support.givenEligiblePlayer("Romulus");
        city = support.givenCity(mayor, "Roma", 0, 0);
        member = support.givenMember(city, "Titus");
        stranger = support.givenEligiblePlayer("Outsider");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    // ==================================================================================
    // SPEC 12.3, players
    // ==================================================================================

    // The SPEC 12.3 player and hostile-mob rows moved to TargetingRuleTest with the decision
    // itself: SPEC 30.1 requires one handler and "no unit-specific targeting logic anywhere
    // else", so a second table here would be the thing it forbids. Every row they covered is
    // asserted there — a visitor ignored in peacetime, a member ignored always, a hostile mob
    // attacked — against the rule that actually runs.
    //
    // Two config toggles went with them and are not coming back. attack-players-in-peacetime
    // had no equivalent in SPEC 30.1's table and, switched on, would break SPEC 13.4's contest
    // voting outright, which SPEC 25.2 Rule 2 exists to protect: "peacetime is safe". The same
    // reasoning the config sweep applied to bounties.claimable-only-during-war — a switch that
    // disables a rule SPEC calls deliberate is not something to ship.

    @Nested
    @DisplayName("SPEC 12.3: the leash")
    class Leash {

        @Test
        @DisplayName("a unit more than eight blocks past its claim goes home")
        void leashDistance() {
            assertFalse(behaviour.shouldReturn(0));
            assertFalse(behaviour.shouldReturn(8), "exactly eight is still fine");
            assertTrue(behaviour.shouldReturn(8.1));
            assertTrue(behaviour.shouldReturn(40));
        }

        @Test
        @DisplayName("the distance is a config key")
        void configurable() {
            support.configs.get(ConfigFile.DEFENSE).set("behaviour.leash-distance-blocks", 2);

            assertTrue(behaviour.shouldReturn(3));
        }
    }

    // ==================================================================================
    // SPEC 12.5
    // ==================================================================================

    @Test
    @DisplayName("SPEC 12.5: a unit's name is readable only from close up")
    void nameVisibility() {
        assertTrue(behaviour.nameVisibleAt(16), "the configured range");
        assertFalse(behaviour.nameVisibleAt(17),
                "so a defended city is not a wall of floating text");
    }

    @Test
    @DisplayName("SPEC 12.3: the war target range is 24 blocks, from config")
    void warRange() {
        assertEquals(24, catalogue.warTargetRange());

        support.configs.get(ConfigFile.DEFENSE).set("behaviour.war-target-range", 12);
        assertEquals(12, catalogue.warTargetRange());
    }
}
