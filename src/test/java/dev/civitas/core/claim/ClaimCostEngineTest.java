package dev.civitas.core.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SPEC 18.1: "Claim cost formula: all reference values in the Section 6.2 table, to within
 * 1 C", plus the member divisor and distance multiplier cases.
 *
 * <p>This is the test that guards design pillar 1.1. If the curve is ever changed to
 * something exponential the whole plugin's promise breaks, and this file is what says so.
 */
class ClaimCostEngineTest {

    private static final BigDecimal ONE_COIN = BigDecimal.ONE;
    private static final long NOT_NEW = TimeUnit.DAYS.toMillis(365);

    @TempDir
    Path directory;

    private ClaimCostEngine engine;
    private FileConfiguration cities;

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("cost-test");
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        ConfigManager configs = new ConfigManager(
                PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        this.cities = configs.get(ConfigFile.CITIES);
        this.engine = new ClaimCostEngine(configs);
    }

    private static void assertWithinOneCoin(BigDecimal expected, BigDecimal actual, String what) {
        BigDecimal difference = expected.subtract(actual).abs();
        assertTrue(difference.compareTo(ONE_COIN) <= 0,
                what + ": expected " + expected + " but got " + actual
                        + ", which is " + difference + " out");
    }

    // ==================================================================================
    // The SPEC 6.2 reference table
    // ==================================================================================

    /** Every row of the SPEC 6.2 "Cost" column, at distance_mult = 1, solo, established city. */
    static Stream<Arguments> referenceTable() {
        return Stream.of(
                Arguments.of(1, "500"),
                Arguments.of(2, "500"),
                Arguments.of(8, "500"),
                Arguments.of(9, "6235"),
                Arguments.of(15, "11808"),
                Arguments.of(20, "16918"),
                Arguments.of(30, "28084"),
                Arguments.of(50, "53183"),
                Arguments.of(75, "88285"),
                Arguments.of(100, "126491"),
                Arguments.of(150, "209978"),
                Arguments.of(200, "300848"),
                Arguments.of(300, "499415"),
                Arguments.of(500, "945742"));
    }

    @ParameterizedTest(name = "chunk {0} costs {1} C")
    @MethodSource("referenceTable")
    @DisplayName("every price in the SPEC 6.2 table matches to within 1 C")
    void referenceTableMatches(int chunkIndex, String expected) {
        BigDecimal actual = engine.totalFor(chunkIndex, 0, 1, NOT_NEW);

        assertWithinOneCoin(new BigDecimal(expected), actual, "chunk " + chunkIndex);
    }

    @Test
    @DisplayName("the first eight chunks are the flat starter plot")
    void starterPlotIsFlat() {
        for (int index = 1; index <= 8; index++) {
            assertEquals(0, new BigDecimal("500.00").compareTo(engine.totalFor(index, 0, 1, NOT_NEW)),
                    "chunk " + index + " should be the flat starter price");
        }
        // The ninth leaves the flat band and joins the curve.
        assertWithinOneCoin(new BigDecimal("6235"), engine.totalFor(9, 0, 1, NOT_NEW), "chunk 9");
    }

    @Test
    @DisplayName("the curve is polynomial, not exponential, which is design pillar 1.1")
    void curveIsPolynomial() {
        BigDecimal first = engine.totalFor(1, 0, 1, NOT_NEW);
        BigDecimal fiveHundredth = engine.totalFor(500, 0, 1, NOT_NEW);

        BigDecimal ratio = fiveHundredth.divide(first, 2, java.math.RoundingMode.HALF_UP);

        // SPEC 6.2: "chunk 500 costs only 1,890x chunk 1". Exponential growth would put this
        // in the hundreds of millions, which is the wall the whole formula exists to avoid.
        assertTrue(ratio.compareTo(new BigDecimal("1800")) > 0
                        && ratio.compareTo(new BigDecimal("2000")) < 0,
                "chunk 500 should cost roughly 1,890 times chunk 1, but the ratio was " + ratio);
    }

    @Test
    @DisplayName("price rises monotonically, so growing never gets cheaper")
    void priceIsMonotonic() {
        BigDecimal previous = BigDecimal.ZERO;
        for (int index = 1; index <= 200; index++) {
            BigDecimal price = engine.totalFor(index, 0, 1, NOT_NEW);
            assertTrue(price.compareTo(previous) >= 0,
                    "chunk " + index + " cost " + price + ", less than chunk " + (index - 1));
            previous = price;
        }
    }

    // ==================================================================================
    // SPEC 18.1: member divisor at 1, 5, 10, 25
    // ==================================================================================

    @ParameterizedTest(name = "{0} members divide by {1}")
    @CsvSource({"1, 1.00", "5, 1.72", "10, 2.62", "25, 5.32"})
    @DisplayName("the member divisor follows 1 + 0.18 * (members - 1)")
    void memberDivisor(int members, double expected) {
        assertEquals(expected, engine.activeMemberDivisor(members, cities), 1e-9);
    }

    @Test
    @DisplayName("ten members expand about 2.6 times cheaper, which is SPEC 4.1's promise")
    void tenMembersAreCheaper() {
        BigDecimal solo = engine.totalFor(50, 0, 1, NOT_NEW);
        BigDecimal ten = engine.totalFor(50, 0, 10, NOT_NEW);

        double ratio = solo.doubleValue() / ten.doubleValue();
        assertTrue(ratio > 2.5 && ratio < 2.7,
                "expected roughly 2.6x cheaper for ten members, got " + ratio);
    }

    @Test
    @DisplayName("a 15-member city pays roughly 268,000 C for chunk 500, per the SPEC 6.2 note")
    void fifteenMemberCityAtChunk500() {
        BigDecimal price = engine.totalFor(500, 0, 15, NOT_NEW);

        assertTrue(price.compareTo(new BigDecimal("265000")) > 0
                        && price.compareTo(new BigDecimal("272000")) < 0,
                "expected roughly 268,000 C, got " + price);
    }

    @Test
    @DisplayName("a member count below one cannot make land free or negative")
    void divisorNeverCollapses() {
        assertEquals(1.0, engine.activeMemberDivisor(0, cities), 1e-9);
        assertEquals(1.0, engine.activeMemberDivisor(-5, cities), 1e-9);
    }

    // ==================================================================================
    // SPEC 18.1: distance multiplier at 0, 4, 5, 20
    // ==================================================================================

    @ParameterizedTest(name = "distance {0} multiplies by {1}")
    @CsvSource({"0, 1.00", "1, 1.00", "4, 1.00", "5, 1.05", "6, 1.10", "20, 1.80"})
    @DisplayName("distance is free inside the radius, then adds 5% per chunk")
    void distanceMultiplier(int distance, double expected) {
        assertEquals(expected, engine.distanceMultiplier(distance, cities), 1e-9);
    }

    @Test
    @DisplayName("distance is applied to the price, not only computed")
    void distanceReachesThePrice() {
        BigDecimal atCore = engine.totalFor(50, 0, 1, NOT_NEW);
        BigDecimal farOut = engine.totalFor(50, 20, 1, NOT_NEW);

        assertWithinOneCoin(atCore.multiply(new BigDecimal("1.80"))
                .setScale(2, java.math.RoundingMode.HALF_UP), farOut, "distance 20");
    }

    // ==================================================================================
    // SPEC 15.1: the young-city discount
    // ==================================================================================

    @Test
    @DisplayName("a city under 14 days old pays three quarters")
    void newCityDiscount() {
        long oneDay = TimeUnit.DAYS.toMillis(1);
        long fifteenDays = TimeUnit.DAYS.toMillis(15);

        assertEquals(0.75, engine.newcomerMultiplier(oneDay, cities), 1e-9);
        assertEquals(0.75, engine.newcomerMultiplier(TimeUnit.DAYS.toMillis(13), cities), 1e-9);
        assertEquals(1.0, engine.newcomerMultiplier(fifteenDays, cities), 1e-9);

        BigDecimal full = engine.totalFor(50, 0, 1, NOT_NEW);
        BigDecimal discounted = engine.totalFor(50, 0, 1, oneDay);
        assertWithinOneCoin(full.multiply(new BigDecimal("0.75"))
                .setScale(2, java.math.RoundingMode.HALF_UP), discounted, "young city");
    }

    @Test
    @DisplayName("the discount boundary is exactly 14 days, not 13 or 15")
    void discountBoundary() {
        long justUnder = TimeUnit.DAYS.toMillis(14) - 1;
        long exactly = TimeUnit.DAYS.toMillis(14);

        assertEquals(0.75, engine.newcomerMultiplier(justUnder, cities), 1e-9);
        assertEquals(1.0, engine.newcomerMultiplier(exactly, cities), 1e-9);
    }

    // ==================================================================================
    // Modifiers together, and the refund
    // ==================================================================================

    @Test
    @DisplayName("all three modifiers compose as SPEC 6.2 writes them")
    void modifiersCompose() {
        int index = 50;
        int distance = 20;
        int members = 10;
        long age = TimeUnit.DAYS.toMillis(1);

        ClaimCostEngine.Breakdown breakdown = engine.price(index, distance, members, age);

        BigDecimal expected = breakdown.base()
                .multiply(BigDecimal.valueOf(1.80))
                .multiply(BigDecimal.valueOf(0.75))
                .divide(BigDecimal.valueOf(2.62), 2, java.math.RoundingMode.HALF_UP);

        assertWithinOneCoin(expected, breakdown.total(), "composed modifiers");
        assertEquals(index, breakdown.chunkIndex());
        assertEquals(1.80, breakdown.distanceMultiplier(), 1e-9);
        assertEquals(0.75, breakdown.newcomerMultiplier(), 1e-9);
        assertEquals(2.62, breakdown.memberDivisor(), 1e-9);
    }

    @Test
    @DisplayName("the refund is half of what was paid, rounded down")
    void refund() {
        assertEquals(0, new BigDecimal("250.00").compareTo(engine.refundFor(new BigDecimal("500.00"))));
        assertEquals(0, new BigDecimal("3117.69").compareTo(engine.refundFor(new BigDecimal("6235.38"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(engine.refundFor(BigDecimal.ZERO)));

        // Rounded down, so a city can never extract more than it paid by churning land.
        assertEquals(0, new BigDecimal("0.00").compareTo(engine.refundFor(new BigDecimal("0.01"))));
    }

    @Test
    @DisplayName("SPEC 17.6 case 74: claim-flipping is always a strict loss")
    void flippingLoses() {
        BigDecimal paid = engine.totalFor(20, 0, 1, NOT_NEW);
        BigDecimal back = engine.refundFor(paid);

        assertTrue(back.compareTo(paid) < 0, "a refund must never match or beat the price");
        // And buying it back costs at least as much again, because the index does not fall.
        assertTrue(engine.totalFor(20, 0, 1, NOT_NEW).compareTo(back) > 0);
    }

    @Test
    @DisplayName("land value is the sum of what was actually paid, which upkeep is based on")
    void landValue() {
        java.util.List<Claim> claims = java.util.List.of(
                claimCosting("500.00"), claimCosting("6235.38"), claimCosting("0.00"));

        assertEquals(0, new BigDecimal("6735.38").compareTo(ClaimCostEngine.landValue(claims)));
        assertEquals(0, BigDecimal.ZERO.compareTo(ClaimCostEngine.landValue(java.util.List.of())));
    }

    private static Claim claimCosting(String cost) {
        return new Claim(1, 1, "world", 0, 0, 0L, java.util.UUID.randomUUID(),
                new BigDecimal(cost), ClaimType.NORMAL, null);
    }
}
