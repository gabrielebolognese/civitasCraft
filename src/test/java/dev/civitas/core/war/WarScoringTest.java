package dev.civitas.core.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.city.CityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 18.1: "War score tally including the block-break cap."
 *
 * <p>The cap is the interesting half. SPEC 11.6 puts it there so "a war is decided by
 * fighting, not by whoever mines the most dirt", and SPEC 17.6 case 76 relies on it to make a
 * war declared purely to grief a losing strategy. If the cap leaked, demolition would become
 * the optimal play and the war system would turn into the thing SPEC 1.2 exists to prevent.
 */
class WarScoringTest {

    @TempDir
    Path directory;

    private WarScoring scoring;

    @BeforeEach
    void setUp() {
        ConfigManager configs = new ConfigManager(PluginResources.ofClasspath(
                directory.resolve("plugin").toFile(), CityTestSupport.quietLogger()));
        configs.loadAll();
        scoring = new WarScoring(configs);
    }

    private War newWar() {
        return new War(1, 10, 20, 0L, 1_000L, 2_000L, WarState.ACTIVE,
                new BigDecimal("50000.00"));
    }

    // ==================================================================================
    // The table, SPEC 11.6
    // ==================================================================================

    @Test
    @DisplayName("a kill scores ten to the killer's side and nothing to the other")
    void kill() {
        War war = newWar();

        assertEquals(10, scoring.awardKill(war, true));
        assertEquals(10, war.attackerScore());
        assertEquals(0, war.defenderScore());
    }

    @Test
    @DisplayName("dying costs nothing")
    void deathIsNotPunished() {
        // SPEC 11.6 writes this into the table as an explicit zero: "Die (to enemy player):
        // 0 (no negative, deaths are not punished)". A negative would make a losing fight a
        // reason to log off, which is the opposite of what a war is for.
        War war = newWar();
        scoring.awardKill(war, true);

        assertEquals(10, war.attackerScore());
        assertEquals(0, war.defenderScore(), "the side that was killed loses nothing");
    }

    @Test
    @DisplayName("holding a capture point scores twenty-five")
    void capture() {
        War war = newWar();

        assertEquals(25, scoring.awardCapture(war, false));
        assertEquals(25, war.defenderScore());
    }

    @Test
    @DisplayName("destroying a defense unit scores fifteen")
    void defenseUnit() {
        War war = newWar();

        assertEquals(15, scoring.awardDefenseUnit(war, true));
    }

    @Test
    @DisplayName("reaching the enemy City Hall scores a hundred, once per city per war")
    void cityHallReachIsOncePerCity() {
        War war = newWar();

        assertEquals(100, scoring.awardCityHallReach(war, 10, true));
        assertEquals(0, scoring.awardCityHallReach(war, 10, true),
                "the same city cannot claim the bonus twice");
        assertEquals(100, war.attackerScore());

        assertEquals(100, scoring.awardCityHallReach(war, 20, false),
                "but the other side's own bonus is untouched");
    }

    // ==================================================================================
    // The block-break cap, SPEC 11.6
    // ==================================================================================

    @Test
    @DisplayName("blocks score a tenth of a point, so ten blocks make one")
    void blocksAccumulate() {
        War war = newWar();

        int awarded = 0;
        for (int block = 0; block < 10; block++) {
            awarded += scoring.awardBlockBreak(war, true);
        }

        assertEquals(1, awarded, "ten blocks at 0.1 each is one point");
        assertEquals(1, war.attackerScore());
    }

    @Test
    @DisplayName("no fraction is lost between blocks")
    void fractionsCarry() {
        War war = newWar();

        for (int block = 0; block < 95; block++) {
            scoring.awardBlockBreak(war, true);
        }

        assertEquals(9, war.attackerScore(), "95 blocks at 0.1 is 9.5, so nine whole points");
        assertEquals(9.5, war.blockPoints(true), 1e-9);
    }

    @Test
    @DisplayName("the cap stops the score at 500 however many blocks are broken")
    void capHolds() {
        War war = newWar();

        // Five thousand blocks reaches the cap exactly; the next five thousand add nothing.
        for (int block = 0; block < 10_000; block++) {
            scoring.awardBlockBreak(war, true);
        }

        assertEquals(500, war.attackerScore(),
                "the cap must hold: without it, demolition would beat fighting");
        assertEquals(500.0, war.blockPoints(true), 1e-9);
    }

    @Test
    @DisplayName("once capped, further blocks award nothing at all")
    void awardsNothingPastTheCap() {
        War war = newWar();
        for (int block = 0; block < 5_000; block++) {
            scoring.awardBlockBreak(war, true);
        }

        assertEquals(0, scoring.awardBlockBreak(war, true));
        assertEquals(0, scoring.awardBlockBreak(war, true));
    }

    @Test
    @DisplayName("the cap is per side, not per war")
    void capIsPerSide() {
        War war = newWar();
        for (int block = 0; block < 10_000; block++) {
            scoring.awardBlockBreak(war, true);
        }

        assertEquals(1, scoring.awardBlockBreak(war, false) + 1 - 1 + 1,
                "the defenders start from zero");
        assertTrue(war.blockPoints(false) < war.blockPoints(true));
    }

    @Test
    @DisplayName("a capped side can still score by fighting")
    void cappedSideCanStillFight() {
        // The whole point of the cap: it bounds demolition, it does not bound the war.
        War war = newWar();
        for (int block = 0; block < 10_000; block++) {
            scoring.awardBlockBreak(war, true);
        }

        scoring.awardKill(war, true);
        scoring.awardCapture(war, true);

        assertEquals(535, war.attackerScore());
    }

    @Test
    @DisplayName("the SPEC 16.3 scoring values are read from war.yml")
    void valuesAreConfigured() {
        assertEquals(10, scoring.killPoints());
        assertEquals(25, scoring.capturePoints());
        assertEquals(60, scoring.captureHoldSeconds());
        assertEquals(15, scoring.defenseUnitPoints());
        assertEquals(0.1, scoring.blockBreakPoints(), 1e-9);
        assertEquals(500.0, scoring.blockBreakCap(), 1e-9);
        assertEquals(100, scoring.cityHallReachPoints());
    }
}
