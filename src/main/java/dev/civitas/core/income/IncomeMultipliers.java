package dev.civitas.core.income;

import java.math.BigDecimal;
import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.Money;
import dev.civitas.storage.row.PlayerRow;

/**
 * The two rules every income source obeys, in one place.
 *
 * <h2>The newcomer bonus, SPEC 4.2 and 15.1</h2>
 * All personal income is multiplied by 1.5 for a player's first 14 days. SPEC 1.3 is what it
 * serves: a player joining on day 90 has to be able to matter, and the fastest way to make
 * that false is to have them earn at the same rate as somebody with a 90-day head start.
 *
 * <h2>The alt floor, SPEC 17.6 case 70</h2>
 * A brand-new account earns nothing at all until it has thirty minutes of active playtime.
 * This is the rule that makes alt farms not worth running: an alt collecting a daily login
 * costs half an hour of somebody genuinely playing on it, which is more than the login is
 * worth. Applied to every source rather than to logins alone, because otherwise the farm
 * simply moves to quests.
 *
 * <p>Both live here rather than in each source, so "does this income respect the rules" has
 * one answer instead of four.
 */
public final class IncomeMultipliers {

    private final ConfigManager configs;

    public IncomeMultipliers(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /**
     * Whether this account has earned the right to earn anything, SPEC 17.6 case 70.
     *
     * @param activePlaytimeMs filtered active playtime, including the live session
     */
    public boolean mayEarn(long activePlaytimeMs) {
        return activePlaytimeMs >= minimumPlaytimeMillis();
    }

    /** How long a new account must play before any income reaches it. */
    public long minimumPlaytimeMillis() {
        return configs.get(ConfigFile.ECONOMY)
                .getLong("income.stipend.min-active-playtime-minutes", 30) * 60_000L;
    }

    /** Whether this player is still inside their SPEC 15.1 newcomer window. */
    public boolean isNewcomer(PlayerRow row, long now) {
        return row.newcomerUntil() > now;
    }

    /** The SPEC 4.2 multiplier: 1.5 for a newcomer, 1 for everybody else. */
    public BigDecimal multiplierFor(PlayerRow row, long now) {
        if (!isNewcomer(row, now)) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(configs.get(ConfigFile.ECONOMY)
                .getDouble("income.newcomer.multiplier", 1.5));
    }

    /**
     * Applies both rules to an amount.
     *
     * @return what should actually be paid, floored to the currency scale, or zero if this
     *         account has not played long enough to earn anything at all
     */
    public BigDecimal apply(BigDecimal base, PlayerRow row, long activePlaytimeMs, long now) {
        if (!mayEarn(activePlaytimeMs)) {
            return BigDecimal.ZERO;
        }
        return Money.floor(base.multiply(multiplierFor(row, now)));
    }
}
