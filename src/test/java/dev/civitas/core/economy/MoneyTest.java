package dev.civitas.core.economy;

import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.util.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Amount parsing and rounding: SPEC 17.3 cases 26 and 27, and SPEC 17.5 case 68.
 *
 * <p>Everything a player types as an amount goes through {@link Money}, so this is the one
 * place a bad input can be stopped before it reaches a balance.
 */
class MoneyTest {

    @TempDir
    Path directory;

    private ConfigManager configs;

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("money-test");
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        configs = new ConfigManager(PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
    }

    private static BigDecimal parsed(String input) {
        Result<BigDecimal> result = Money.parse(input);
        assertTrue(result.isSuccess(), input + ": " + reasonOf(result));
        return result.asOptional().orElseThrow();
    }

    // ==================================================================================
    // SPEC 17.3 case 26: more than two decimal places is floored
    // ==================================================================================

    @Test
    @DisplayName("SPEC 17.3 case 26: extra decimal places are floored, never rounded")
    void extraDecimalsAreFloored() {
        assertEquals(0, new BigDecimal("1500.99").compareTo(parsed("1500.999")));
        assertEquals(0, new BigDecimal("0.01").compareTo(parsed("0.019999")));
        assertEquals(0, new BigDecimal("7.12").compareTo(parsed("7.128")));
    }

    @Test
    @DisplayName("rounding down, so no transaction ever mints a fraction of a coin")
    void flooringNeverRoundsUp() {
        // 0.005 would round to 0.01 under HALF_UP. Repeated across a server's lifetime
        // that is real, unaccounted inflation.
        assertEquals(0, BigDecimal.ZERO.compareTo(Money.floor(new BigDecimal("0.009"))));
        assertEquals(0, new BigDecimal("99.99").compareTo(Money.floor(new BigDecimal("99.999"))));
    }

    @Test
    @DisplayName("an amount that floors to nothing is refused rather than silently zero")
    void flooringToZeroIsRefused() {
        assertEquals("AMOUNT_NOT_POSITIVE", reasonOf(Money.parse("0.001")));
        assertEquals("AMOUNT_NOT_POSITIVE", reasonOf(Money.parse("0")));
        assertEquals("AMOUNT_NOT_POSITIVE", reasonOf(Money.parse("0.00")));
    }

    // ==================================================================================
    // SPEC 17.5 case 68: negatives and scientific notation
    // ==================================================================================

    @ParameterizedTest(name = "\"{0}\" is refused")
    @ValueSource(strings = {
            "-100",          // a negative would invert the direction of the transfer
            "-0.01",
            "1e9",           // BigDecimal parses this happily as a billion
            "1E+12",
            "1.5e3",
            "+500",          // signed, and the sign could just as easily have been minus
            "NaN",
            "Infinity",
            "abc",
            "1000 C",
            "1_000",
            "",
            "   ",
            "..",
            "1.2.3",
            "0x1F"})
    @DisplayName("SPEC 17.5 case 68: anything that is not a plain decimal is refused")
    void nonPlainDecimalsAreRefused(String input) {
        assertTrue(Money.parse(input).isFailure(), input + " should not have parsed");
    }

    @Test
    @DisplayName("a missing amount is named as missing, not as invalid")
    void missingIsDistinct() {
        assertEquals("AMOUNT_MISSING", reasonOf(Money.parse(null)));
        assertEquals("AMOUNT_MISSING", reasonOf(Money.parse("  ")));
        assertEquals("AMOUNT_INVALID", reasonOf(Money.parse("nonsense")));
    }

    @Test
    @DisplayName("plain amounts parse, with or without thousands separators")
    void plainAmountsParse() {
        assertEquals(0, new BigDecimal("1500").compareTo(parsed("1500")));
        assertEquals(0, new BigDecimal("1500.50").compareTo(parsed("1500.50")));
        assertEquals(0, new BigDecimal("1500.50").compareTo(parsed(" 1500.50 ")));
        assertEquals(0, new BigDecimal("1500000").compareTo(parsed("1,500,000")),
                "players copy figures out of the GUI, which groups them");
    }

    @Test
    @DisplayName("an absurdly long number is refused rather than parsed into a huge balance")
    void absurdlyLongIsRefused() {
        assertTrue(Money.parse("9".repeat(19)).isFailure(), "19 digits is past the pattern");
        assertTrue(Money.parse("9".repeat(18)).isSuccess(), "18 digits still parses");
    }

    // ==================================================================================
    // SPEC 17.3 case 27: the balance ceiling
    // ==================================================================================

    @Test
    @DisplayName("SPEC 17.3 case 27: a balance past economy.max-balance is refused")
    void ceilingIsEnforced() {
        BigDecimal max = Money.maxBalance(configs);
        assertEquals(0, new BigDecimal("1000000000000").compareTo(max));

        assertTrue(Money.checkCeiling(max, configs).isSuccess(), "exactly the limit is allowed");

        Result<BigDecimal> over = Money.checkCeiling(max.add(BigDecimal.ONE), configs);
        assertEquals("MAX_BALANCE", reasonOf(over));
        assertEquals("1000000000000",
                ((Result.Failure<BigDecimal>) over).placeholders().get("max"),
                "the refusal tells the player what the limit is");
    }

    @Test
    @DisplayName("the ceiling is a config key, not a constant")
    void ceilingIsConfigurable() {
        configs.get(ConfigFile.ECONOMY).set("max-balance", "5000");

        assertEquals(0, new BigDecimal("5000").compareTo(Money.maxBalance(configs)));
        assertTrue(Money.checkCeiling(new BigDecimal("5001"), configs).isFailure());
    }

    // ==================================================================================
    // Percentages and formatting
    // ==================================================================================

    @Test
    @DisplayName("percentages floor too, so caps and refunds never round in the player's favour")
    void percentagesFloor() {
        assertEquals(0, new BigDecimal("25000.00")
                .compareTo(Money.percentOf(new BigDecimal("100000"), 25)));
        assertEquals(0, new BigDecimal("0.40")
                .compareTo(Money.percentOf(new BigDecimal("100"), 0.4)));
        assertEquals(0, new BigDecimal("0.33")
                .compareTo(Money.percentOf(new BigDecimal("1"), 33.333)));
        assertEquals(0, BigDecimal.ZERO.compareTo(Money.percentOf(new BigDecimal("1"), 0.4)),
                "0.004 floors to nothing rather than becoming a cent");
    }

    @Test
    @DisplayName("amounts are shown plainly, never in scientific notation")
    void formattingIsPlain() {
        assertEquals("1000000000000.00 C", Money.format(new BigDecimal("1e12"), configs));
        assertEquals("1500.50 C", Money.format(new BigDecimal("1500.5"), configs));

        configs.get(ConfigFile.ECONOMY).set("currency-symbol", "¤");
        assertEquals("10.00 ¤", Money.format(new BigDecimal("10"), configs));
    }
}
