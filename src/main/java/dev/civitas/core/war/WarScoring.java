package dev.civitas.core.war;

import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;

/**
 * The SPEC 11.6 score table, as decisions with no I/O.
 *
 * <p>SPEC 18.1 asks for "war score tally including the block-break cap", so the cap lives here
 * with the rest rather than in whichever listener happens to award points.
 *
 * <h2>The cap is a design statement</h2>
 * SPEC 11.6 explains it: "the cap exists so that a war is decided by fighting, not by whoever
 * mines the most dirt. Breaking blocks contributes a little to score but a team that only
 * demolishes will lose to a team that fights and captures." SPEC 17.6 case 76 leans on the
 * same number to make a purely destructive war a losing strategy. At the shipped values a side
 * would have to break 5,000 blocks to reach a cap worth ten kills.
 *
 * <p>Note also what is <em>not</em> here: SPEC 11.6 awards nothing for dying. "Deaths are not
 * punished" is written into the table as a zero, and a negative would turn a losing fight into
 * a reason to log off.
 */
public final class WarScoring {

    private final ConfigManager configs;

    public WarScoring(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /** SPEC 11.6: killing an enemy player. */
    public int killPoints() {
        return scoring().getInt("kill", 10);
    }

    /** SPEC 11.6: holding a capture point for the required time. */
    public int capturePoints() {
        return scoring().getInt("capture-point-hold", 25);
    }

    /** How long a point must be held continuously to score, in seconds. */
    public int captureHoldSeconds() {
        return scoring().getInt("capture-hold-seconds", 60);
    }

    /** SPEC 11.6: destroying an enemy defense unit. */
    public int defenseUnitPoints() {
        return scoring().getInt("destroy-defense-unit", 15);
    }

    /** SPEC 11.6: per block broken inside enemy claims, before the cap. */
    public double blockBreakPoints() {
        return scoring().getDouble("block-break", 0.1);
    }

    /** SPEC 11.6: the total a side can earn from breaking blocks in one war. */
    public double blockBreakCap() {
        return scoring().getDouble("block-break-score-cap", 500);
    }

    /** SPEC 11.6: standing in the enemy City Hall chunk, once per war per city. */
    public int cityHallReachPoints() {
        return scoring().getInt("city-hall-reach", 100);
    }

    /** How long that stand must last, in seconds. */
    public int cityHallReachSeconds() {
        // "city-hall-hold-seconds", which is the name war.yml has always shipped. The
        // code read "city-hall-reach-seconds", which the file does not contain — so the
        // operator's key did nothing and this one always fell through to 30.
        return scoring().getInt("city-hall-hold-seconds", 30);
    }

    /**
     * Awards a kill.
     *
     * @return the points given, so the caller can report them
     */
    public int awardKill(War war, boolean attackerSide) {
        int points = killPoints();
        war.addScore(attackerSide, points);
        return points;
    }

    public int awardCapture(War war, boolean attackerSide) {
        int points = capturePoints();
        war.addScore(attackerSide, points);
        return points;
    }

    public int awardDefenseUnit(War war, boolean attackerSide) {
        int points = defenseUnitPoints();
        war.addScore(attackerSide, points);
        return points;
    }

    /**
     * Awards one broken block, subject to the cap.
     *
     * @return the whole points awarded, which is usually zero: at 0.1 a block, nine blocks in
     *         ten award nothing and the tenth awards one
     */
    public int awardBlockBreak(War war, boolean attackerSide) {
        return war.addBlockPoints(attackerSide, blockBreakPoints(), blockBreakCap());
    }

    /**
     * Awards the City Hall bonus, once per war per city.
     *
     * @return the points given, or zero if that city has already had it
     */
    public int awardCityHallReach(War war, int cityId, boolean attackerSide) {
        if (!war.claimCityHallReach(cityId)) {
            return 0;
        }
        int points = cityHallReachPoints();
        war.addScore(attackerSide, points);
        return points;
    }

    private org.bukkit.configuration.ConfigurationSection scoring() {
        org.bukkit.configuration.ConfigurationSection section =
                configs.get(ConfigFile.WAR).getConfigurationSection("scoring");
        return section != null ? section
                : configs.get(ConfigFile.WAR).createSection("scoring-missing");
    }
}
