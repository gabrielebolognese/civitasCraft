package dev.civitas.core.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.storage.SqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SPEC 18.1: "Upkeep calculation for cities of 8, 50, 200 claims."
 *
 * <p>The three land values come from the cumulative column of the SPEC 6.2 reference table,
 * which is what a solo founder would actually have paid.
 */
class UpkeepCalculatorTest {

    @TempDir
    Path directory;

    private ConfigManager configs;
    private UpkeepCalculator calculator;

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("upkeep-test");
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        configs = new ConfigManager(PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        calculator = new UpkeepCalculator(configs);
    }

    // ==================================================================================
    // SPEC 18.1: the three city sizes
    // ==================================================================================

    @ParameterizedTest(name = "{2} claims worth {0} cost {1} a day")
    @CsvSource({
            // 8 claims at the flat 500 starter price.
            "4000.00,     16.00,   8",
            // 50 claims, cumulative from the SPEC 6.2 table.
            "1190649.00,  4762.59, 50",
            // 200 claims, likewise.
            "26874751.00, 107499.00, 200"})
    @DisplayName("upkeep is 0.4% of land value a day")
    void upkeepForCitySizes(String landValue, String expected, int claims) {
        BigDecimal upkeep = calculator.dailyUpkeep(new BigDecimal(landValue));

        assertEquals(0, new BigDecimal(expected).compareTo(upkeep),
                claims + " claims: expected " + expected + " but got " + upkeep);
    }

    @Test
    @DisplayName("SPEC 4.3's worked example holds, at the solo prices it assumes")
    void hundredChunkCity() {
        // "total daily sink for a mature 100-chunk, 10-member city is roughly 22,700 C".
        // 100 chunks cumulative is 5,667,308 at solo prices.
        BigDecimal upkeep = calculator.dailyUpkeep(new BigDecimal("5667308.00"));

        assertTrue(upkeep.compareTo(new BigDecimal("22600")) > 0
                        && upkeep.compareTo(new BigDecimal("22800")) < 0,
                "expected roughly 22,700 C, got " + upkeep);
    }

    @Test
    @DisplayName("a ten-member city really pays less, because it paid less for the land")
    void memberDividedLandIsCheaperToKeep() {
        // SPEC 4.3's example prices land at solo rates. A ten-member city buying the same
        // hundred chunks pays the SPEC 6.2 member-divided price, so its land value, and
        // therefore its upkeep, is about 2.6 times lower. Worth knowing before tuning.
        BigDecimal soloValue = new BigDecimal("5667308.00");
        BigDecimal tenMemberValue = soloValue.divide(new BigDecimal("2.62"), 2,
                java.math.RoundingMode.DOWN);

        BigDecimal solo = calculator.dailyUpkeep(soloValue);
        BigDecimal ten = calculator.dailyUpkeep(tenMemberValue);

        assertTrue(ten.compareTo(solo) < 0);
        assertTrue(ten.compareTo(new BigDecimal("8000")) > 0
                        && ten.compareTo(new BigDecimal("9000")) < 0,
                "expected roughly 8,650 C, got " + ten);
    }

    @Test
    @DisplayName("a city with no land owes nothing")
    void noLandNoUpkeep() {
        assertEquals(0, BigDecimal.ZERO.compareTo(calculator.dailyUpkeep(SqlDialect.zero())));
    }

    @Test
    @DisplayName("upkeep is floored, so it can never round up into money from nothing")
    void upkeepIsFloored() {
        // 0.4% of 1 is 0.004, which floors to zero rather than rounding to a cent.
        assertEquals(0, BigDecimal.ZERO.compareTo(calculator.dailyUpkeep(new BigDecimal("1.00"))));
        assertEquals(0, new BigDecimal("0.40")
                .compareTo(calculator.dailyUpkeep(new BigDecimal("100.00"))));
    }

    // ==================================================================================
    // The parts M10, M11 and M12 will supply
    // ==================================================================================

    @Test
    @DisplayName("outpost upkeep is added on top, SPEC 39.5")
    void outpostsAddUpkeep() {
        // A figure, not a count. Part I 7.2 charged every outpost a flat 2,000, so this test
        // used to multiply. SPEC 39.5 scales the bill by distance and by chunks held, so what
        // an outpost costs is the cost engine's question and OutpostCostEngineTest asserts it
        // against SPEC 39.5's own table. What belongs here is only that the figure lands.
        BigDecimal none = calculator.dailyUpkeep(new BigDecimal("100000.00"), SqlDialect.zero(),
                SqlDialect.zero(), 0);
        BigDecimal withOutposts = calculator.dailyUpkeep(new BigDecimal("100000.00"),
                new BigDecimal("9448.00"), SqlDialect.zero(), 0);

        assertEquals(0, none.add(new BigDecimal("9448")).compareTo(withOutposts));
    }

    @Test
    @DisplayName("a negative outpost figure cannot pay down the land bill")
    void outpostUpkeepNeverSubtracts() {
        BigDecimal land = calculator.dailyUpkeep(new BigDecimal("100000.00"), SqlDialect.zero(),
                SqlDialect.zero(), 0);

        assertEquals(0, land.compareTo(calculator.dailyUpkeep(new BigDecimal("100000.00"),
                new BigDecimal("-5000.00"), SqlDialect.zero(), 0)));
    }

    @Test
    @DisplayName("defense unit upkeep is added on top, SPEC 12.2")
    void defenseUnitsAddUpkeep() {
        BigDecimal with = calculator.dailyUpkeep(new BigDecimal("100000.00"), SqlDialect.zero(),
                new BigDecimal("1600.00"), 0);

        assertEquals(0, new BigDecimal("2000.00").compareTo(with), "400 from land plus 1,600");
    }

    @Test
    @DisplayName("Treasury Interest takes 4% off per level, SPEC 5.7")
    void treasuryInterestReducesUpkeep() {
        BigDecimal base = calculator.dailyUpkeep(new BigDecimal("1000000.00"), SqlDialect.zero(),
                SqlDialect.zero(), 0);
        assertEquals(0, new BigDecimal("4000.00").compareTo(base));

        assertEquals(0, new BigDecimal("3840.00").compareTo(
                calculator.dailyUpkeep(new BigDecimal("1000000.00"), SqlDialect.zero(), SqlDialect.zero(), 1)));
        assertEquals(0, new BigDecimal("3200.00").compareTo(
                calculator.dailyUpkeep(new BigDecimal("1000000.00"), SqlDialect.zero(), SqlDialect.zero(), 5)),
                "five levels is 20% off");
    }

    @Test
    @DisplayName("upkeep never goes negative, however much is discounted")
    void neverNegative() {
        assertTrue(calculator.dailyUpkeep(new BigDecimal("1000.00"), SqlDialect.zero(), SqlDialect.zero(), 99)
                .signum() >= 0);
    }

    // ==================================================================================
    // Runway and scheduling
    // ==================================================================================

    @Test
    @DisplayName("runway is whole days the treasury can cover, rounded down")
    void runway() {
        assertEquals(10, calculator.daysOfRunway(new BigDecimal("1000"), new BigDecimal("100")));
        assertEquals(9, calculator.daysOfRunway(new BigDecimal("999"), new BigDecimal("100")));
        assertEquals(0, calculator.daysOfRunway(new BigDecimal("50"), new BigDecimal("100")));
        assertEquals(Long.MAX_VALUE,
                calculator.daysOfRunway(new BigDecimal("50"), BigDecimal.ZERO),
                "a city that owes nothing never runs out");
    }

    @Test
    @DisplayName("the next charge lands on the configured hour, never on the current minute")
    void nextChargeIsAnchoredToTheHour() {
        ZoneId zone = ZoneId.of("UTC");
        long noon = ZonedDateTime.of(2026, 8, 1, 12, 34, 56, 0, zone).toInstant().toEpochMilli();

        long next = calculator.nextChargeAfter(noon, zone);
        ZonedDateTime moment = java.time.Instant.ofEpochMilli(next).atZone(zone);

        // cities.yml sets upkeep.charge-hour to 4, which is already past at noon.
        assertEquals(4, moment.getHour());
        assertEquals(0, moment.getMinute());
        assertEquals(0, moment.getSecond());
        assertEquals(2, moment.getDayOfMonth(), "the next 04:00 is tomorrow's");
    }

    @Test
    @DisplayName("a moment before the charge hour is billed the same day")
    void nextChargeSameDay() {
        ZoneId zone = ZoneId.of("UTC");
        long earlyMorning =
                ZonedDateTime.of(2026, 8, 1, 2, 0, 0, 0, zone).toInstant().toEpochMilli();

        ZonedDateTime next = java.time.Instant
                .ofEpochMilli(calculator.nextChargeAfter(earlyMorning, zone)).atZone(zone);

        assertEquals(1, next.getDayOfMonth());
        assertEquals(4, next.getHour());
    }

    @Test
    @DisplayName("every knob the sweep reads comes from config")
    void configuredValues() {
        assertTrue(calculator.enabled());
        assertEquals(3, calculator.gracePeriodDays());
        assertTrue(calculator.autoUnclaimEnabled());
        assertEquals(3, calculator.unclaimsPerDay());
        assertEquals(7, calculator.maxCatchupCycles());

        configs.get(ConfigFile.CITIES).set("upkeep.enabled", false);
        assertTrue(!calculator.enabled());
    }
}
