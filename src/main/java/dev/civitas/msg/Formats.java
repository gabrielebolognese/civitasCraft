package dev.civitas.msg;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * SPEC 23.7's formatters. One each, and only one each.
 *
 * <p>SPEC 23.7: "Numbers are formatted by a single central formatter… Durations use a single
 * formatter… Coordinates always as {@code (x, z)} for chunks and {@code (x, y, z)} for blocks."
 * The singular is the requirement. Two formatters means two answers to the same question, and a
 * player who reads 12,847.22 in one message and 12847.2 in the next assumes one of them is wrong.
 *
 * <h2>The abbreviation rule is about the channel, not the number</h2>
 *
 * <p>SPEC 23.7: "Large numbers abbreviate above 1,000,000 ({@code 1.25M}) <b>in action bars and
 * boss bars only, never in chat, because a player reading a transaction wants the exact
 * figure.</b>" So the same balance is {@code 1,250,000.00} in chat and {@code 1.25M} on a boss
 * bar, and that is deliberate rather than an inconsistency: one is a record and the other is a
 * glance.
 */
public final class Formats {

    private Formats() {
    }

    /** Above this, a compact rendering abbreviates. SPEC 23.7 states the figure. */
    private static final BigDecimal ABBREVIATE_ABOVE = new BigDecimal("1000000");

    /**
     * {@link Locale#ROOT}, so the separators do not move with the language.
     *
     * <p>SPEC 23.7 asks for "two decimals with thousands separators" and does not say the
     * grouping should follow the translation. Italian would write 1.234,50 where English writes
     * 1,234.50, and a server whose players read both files would see the same balance two ways.
     * A currency figure is closer to an account number than to prose, so it is formatted one way
     * everywhere and only the words around it are translated.
     */
    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(
            Locale.ROOT);

    // ==================================================================================
    // Money
    // ==================================================================================

    /**
     * A currency amount for chat: exact, two decimals, thousands separated.
     *
     * <p>Never abbreviated. SPEC 23.1: "Numbers are always shown. Never 'you sold your items'.
     * Always 'you sold 64 wheat at 3.12 C each for 199.68 C'."
     */
    public static String money(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.00", SYMBOLS);
        return format.format(amount.setScale(2, RoundingMode.DOWN));
    }

    /**
     * A currency amount for an action bar or a boss bar: abbreviated above a million.
     *
     * <p>Only these two channels, per SPEC 23.7. Anything a player may want to scroll back to
     * gets {@link #money}.
     */
    public static String moneyCompact(BigDecimal amount) {
        BigDecimal magnitude = amount.abs();
        if (magnitude.compareTo(ABBREVIATE_ABOVE) < 0) {
            return money(amount);
        }
        // Largest fitting unit, chosen outright rather than by repeated division: the loop
        // form tests one threshold and divides by another, and quietly rendered 1,250,000 as
        // "1,250M" until a test written from SPEC's own example caught it.
        BigDecimal divisor;
        String suffix;
        if (magnitude.compareTo(new BigDecimal("1000000000000")) >= 0) {
            divisor = new BigDecimal("1000000000000");
            suffix = "T";
        } else if (magnitude.compareTo(new BigDecimal("1000000000")) >= 0) {
            divisor = new BigDecimal("1000000000");
            suffix = "B";
        } else {
            divisor = ABBREVIATE_ABOVE;
            suffix = "M";
        }
        BigDecimal scaled = amount.divide(divisor, 2, RoundingMode.DOWN);
        return new DecimalFormat("#,##0.##", SYMBOLS).format(scaled) + suffix;
    }

    // ==================================================================================
    // Durations
    // ==================================================================================

    /**
     * How long, in SPEC 23.7's shape: {@code 2d 4h}, {@code 18m}, {@code 45s}.
     *
     * <p>At most two units, always the two largest that are non-zero, so the reading is a glance
     * rather than arithmetic. A duration of zero or less reads {@code 0s} rather than an empty
     * string: a message with a hole where the time should be looks broken.
     */
    public static String duration(long millis) {
        if (millis <= 0) {
            return "0s";
        }
        long seconds = millis / 1000;
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long rest = seconds % 60;

        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        if (minutes > 0) {
            return rest > 0 ? minutes + "m " + rest + "s" : minutes + "m";
        }
        return rest + "s";
    }

    // ==================================================================================
    // Coordinates
    // ==================================================================================

    /** A chunk, SPEC 23.7's {@code (x, z)}. */
    public static String chunk(int chunkX, int chunkZ) {
        return "(" + chunkX + ", " + chunkZ + ")";
    }

    /** A block, SPEC 23.7's {@code (x, y, z)}. */
    public static String block(int x, int y, int z) {
        return "(" + x + ", " + y + ", " + z + ")";
    }

    // ==================================================================================
    // Plain counts
    // ==================================================================================

    /** A count with thousands separators, for figures that are not money. */
    public static String count(long value) {
        return new DecimalFormat("#,##0", SYMBOLS).format(value);
    }
}
