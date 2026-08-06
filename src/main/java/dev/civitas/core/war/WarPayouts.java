package dev.civitas.core.war;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * The SPEC 11.9 payout table, as arithmetic with no I/O in it.
 *
 * <p>SPEC 18.1 asks for this specifically: "Payout math for win, loss, draw, decline, and ally
 * splits." It is a pure function of the wager and the config so that it can be.
 *
 * <h2>SPEC 11.9 does not add up, and this is how it is resolved</h2>
 * The table says the winner receives "80% of the loser's wager" and that the loser "receives
 * 20% of their own wager back". The paragraph after it says "The remaining 20% of the loser's
 * wager is <strong>deleted from circulation</strong>". Those describe the same 20% twice, once
 * as refunded and once as burned, and it cannot be both.
 *
 * <p>Resolved in favour of the later statement: the burn is taken first, and the loser's
 * refund gets whatever is left of their wager after the winner's share and the burn. With
 * SPEC 16.3's shipped numbers (80 and 20) that means the loser is refunded nothing and 20% is
 * destroyed, which is the reading that makes SPEC's own sentence about an economic sink true.
 * An operator who wants the refund instead sets {@code rewards.burn-percent} to zero and gets
 * it. <strong>Recorded in OPEN_QUESTIONS.md as needing a developer decision.</strong>
 *
 * <p>Whatever the split, the money always balances: the three shares are carved out of the two
 * wagers and never exceed them, so a war can neither create nor quietly lose coins.
 */
public final class WarPayouts {

    private final ConfigManager configs;

    public WarPayouts(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /**
     * What each side receives, and what is destroyed.
     *
     * @param winnerReturn what the winning city's treasury receives in total
     * @param loserReturn  what the losing city's treasury receives in total
     * @param burned       destroyed, per SPEC 11.9's economic sink
     */
    public record Split(BigDecimal winnerReturn, BigDecimal loserReturn, BigDecimal burned) {

        /** The pot this split came out of, for the balance check. */
        public BigDecimal total() {
            return winnerReturn.add(loserReturn).add(burned);
        }
    }

    /**
     * A decided war, SPEC 11.9's Win and Loss rows.
     *
     * @param wager one side's stake; both staked the same, so the pot is twice this
     */
    public Split decided(BigDecimal wager) {
        BigDecimal stake = wager.setScale(2, RoundingMode.DOWN);

        BigDecimal winnerShare = percentOf(stake, "rewards.winner-wager-share-percent", 80);
        BigDecimal burn = percentOf(stake, "rewards.burn-percent", 20);

        // The winner's share and the burn both come out of the loser's stake, and together
        // they can never take more than there is.
        BigDecimal remaining = stake.subtract(winnerShare);
        BigDecimal burned = burn.min(remaining).max(BigDecimal.ZERO);

        // Whatever survives both goes back to the loser, which is where SPEC's refund row
        // lands once the burn has been honoured.
        BigDecimal loserRefundConfigured =
                percentOf(stake, "rewards.loser-refund-percent", 20);
        BigDecimal loserReturn = loserRefundConfigured.min(remaining.subtract(burned))
                .max(BigDecimal.ZERO);

        // Anything neither the winner, the loser nor the configured burn claimed is destroyed
        // too, so the arithmetic closes rather than leaking.
        BigDecimal unclaimed = remaining.subtract(burned).subtract(loserReturn);

        return new Split(
                stake.add(winnerShare),
                loserReturn,
                burned.add(unclaimed));
    }

    /** SPEC 11.9's Draw row: "Both wagers refunded in full." */
    public Split drawn(BigDecimal wager) {
        BigDecimal stake = wager.setScale(2, RoundingMode.DOWN);
        return new Split(stake, stake, BigDecimal.ZERO);
    }

    /** SPEC 11.9's Admin cancelled row, and the same shape as a draw. */
    public Split cancelled(BigDecimal wager) {
        return drawn(wager);
    }

    /**
     * SPEC 11.3's decline: the attacker gets their stake back plus a share of the defender's.
     *
     * @return the split with the attacker as "winner"
     */
    public Split declined(BigDecimal wager) {
        BigDecimal stake = wager.setScale(2, RoundingMode.DOWN);
        BigDecimal penalty = percentOf(stake, "declaration.decline-penalty-percent", 30);
        return new Split(stake.add(penalty), stake.subtract(penalty), BigDecimal.ZERO);
    }

    /**
     * SPEC 11.10: allies "split 30% of the payout pool proportional to their score
     * contribution".
     *
     * @param allyScore  what this ally contributed
     * @param sideScore  what the whole side scored; zero means nobody scored, so nobody splits
     * @return this ally's cut of the winner's return
     */
    public BigDecimal allyShare(BigDecimal winnerReturn, int allyScore, int sideScore) {
        if (sideScore <= 0 || allyScore <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal pool = percentOf(winnerReturn, "rewards.ally-payout-share-percent", 30);
        return pool.multiply(BigDecimal.valueOf(allyScore))
                .divide(BigDecimal.valueOf(sideScore), 2, RoundingMode.DOWN);
    }

    /**
     * Whether two scores are close enough to be a draw.
     *
     * <p>SPEC 11.9: "A draw occurs if scores are within 5% of each other." Measured against
     * the larger score, so two sides on zero are a draw rather than a division by nothing,
     * which SPEC 17.4 case 55 requires directly.
     */
    public boolean isDraw(int attackerScore, int defenderScore) {
        int higher = Math.max(attackerScore, defenderScore);
        if (higher <= 0) {
            return true;
        }
        double threshold = configs.get(ConfigFile.WAR)
                .getDouble("rewards.draw-threshold-percent",
                        configs.get(ConfigFile.WAR).getDouble("scoring.draw-threshold-percent", 5));
        double difference = Math.abs(attackerScore - defenderScore) * 100.0 / higher;
        return difference <= threshold;
    }

    /** SPEC 11.9's winner bonus and loser immunity, both in days. */
    public long immunityDays() {
        return war().getLong("rewards.immunity-days", 7);
    }

    public long marketBonusDays() {
        return war().getLong("rewards.winner-market-bonus-days", 7);
    }

    public double marketBonusPercent() {
        return war().getDouble("rewards.winner-market-bonus-percent", 10);
    }

    private BigDecimal percentOf(BigDecimal amount, String key, double fallback) {
        double percent = war().getDouble(key, fallback);
        return amount.multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
    }

    private FileConfiguration war() {
        return configs.get(ConfigFile.WAR);
    }
}
