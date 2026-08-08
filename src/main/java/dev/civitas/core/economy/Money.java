package dev.civitas.core.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.regex.Pattern;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.storage.SqlDialect;
import dev.civitas.util.Result;

/**
 * Reading and rounding money, in one place.
 *
 * <p>Every amount a player types passes through here. SPEC 17.3 case 26 requires rounding
 * <em>down</em> rather than to nearest, case 27 a hard ceiling, and SPEC 17.5 case 68 that
 * negatives and scientific notation are refused rather than parsed into something surprising.
 * Doing that per command would eventually get one of them wrong.
 */
public final class Money {

    /**
     * Plain decimal only: optional digits, optional single point, optional digits.
     *
     * <p>{@link BigDecimal} happily parses {@code 1e9}, {@code +5} and {@code 0x1F}-adjacent
     * shapes. A player typing {@code 1e9} into {@code /pay} means nothing sensible, and
     * silently treating it as a billion is worse than refusing it.
     */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("\\d{1,18}(\\.\\d{1,10})?");

    private Money() {
    }

    /**
     * Parses an amount a player typed.
     *
     * @return the amount, floored to the currency scale, or a failure naming what was wrong
     */
    public static Result<BigDecimal> parse(String input) {
        if (input == null || input.isBlank()) {
            return Result.failure("AMOUNT_MISSING", "economy.amount-invalid");
        }
        String trimmed = input.trim().replace(",", "");

        if (!PLAIN_DECIMAL.matcher(trimmed).matches()) {
            return Result.failure("AMOUNT_INVALID", "economy.amount-invalid");
        }

        BigDecimal parsed;
        try {
            parsed = new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            return Result.failure("AMOUNT_INVALID", "economy.amount-invalid");
        }

        BigDecimal floored = floor(parsed);
        if (floored.signum() <= 0) {
            return Result.failure("AMOUNT_NOT_POSITIVE", "economy.amount-not-positive");
        }
        return Result.success(floored);
    }

    /**
     * Rounds down to the currency scale, SPEC 17.3 case 26.
     *
     * <p>Down, never to nearest: rounding up would mint a fraction of a coin out of nothing
     * on every transaction, and over a server's lifetime that is real inflation.
     */
    public static BigDecimal floor(BigDecimal amount) {
        return amount.setScale(SqlDialect.MONEY_SCALE, RoundingMode.DOWN);
    }

    /** The SPEC 17.3 case 27 ceiling, from {@code economy.max-balance}. */
    public static BigDecimal maxBalance(ConfigManager configs) {
        return new BigDecimal(configs.get(ConfigFile.ECONOMY)
                .getString("max-balance", "1000000000000"));
    }

    /**
     * Whether a balance would breach the ceiling.
     *
     * @return a failure carrying the limit, or success
     */
    public static Result<BigDecimal> checkCeiling(BigDecimal proposed, ConfigManager configs) {
        BigDecimal max = maxBalance(configs);
        if (proposed.compareTo(max) > 0) {
            return Result.failure("MAX_BALANCE", "economy.max-balance",
                    Map.of("max", max.toPlainString()));
        }
        return Result.success(proposed);
    }

    /** The currency suffix shown to players, from {@code economy.currency-symbol}. */
    public static String symbol(ConfigManager configs) {
        return configs.get(ConfigFile.ECONOMY).getString("currency-symbol", "C");
    }

    /**
     * An amount as a player should see it: plain digits and the configured symbol.
     *
     * <p>Plain rather than scientific, because {@code 1E+12} is not a number anyone wants to
     * read as a bank balance.
     */
    public static String format(BigDecimal amount, ConfigManager configs) {
        // SPEC 23.7: "Currency always shows two decimals with thousands separators." Was a
        // bare toPlainString until M7a, so 12847.22 read as 12847.22 rather than 12,847.22 —
        // which is the exact figure SPEC 23.1 uses in its worked example of a good message.
        return dev.civitas.msg.Formats.money(floor(amount)) + " " + symbol(configs);
    }

    /** A percentage of an amount, floored, used by every cap and refund in the economy. */
    public static BigDecimal percentOf(BigDecimal amount, double percent) {
        return amount.multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), SqlDialect.MONEY_SCALE, RoundingMode.DOWN);
    }
}
