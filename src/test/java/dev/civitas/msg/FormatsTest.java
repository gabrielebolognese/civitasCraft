package dev.civitas.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 23.7's formatters, against the examples SPEC itself gives.
 */
class FormatsTest {

    @Nested
    @DisplayName("money, SPEC 23.7")
    class Money {

        @Test
        @DisplayName("two decimals and thousands separators, always")
        void exactInChat() {
            assertEquals("0.00", Formats.money(BigDecimal.ZERO));
            assertEquals("3.12", Formats.money(new BigDecimal("3.12")));
            assertEquals("199.68", Formats.money(new BigDecimal("199.68")));
            assertEquals("12,847.22", Formats.money(new BigDecimal("12847.22")));
            assertEquals("1,250,000.00", Formats.money(new BigDecimal("1250000")));
        }

        @Test
        @DisplayName("SPEC 23.1's worked example renders exactly as written")
        void specExample() {
            // "you sold 64 wheat at 3.12 C each for 199.68 C, minus 9.98 C tax, new balance
            // 12,847.22 C" — the separator in that last figure is the requirement.
            assertEquals("12,847.22", Formats.money(new BigDecimal("12847.2200")));
            assertEquals("9.98", Formats.money(new BigDecimal("9.98")));
        }

        @Test
        @DisplayName("chat never abbreviates, however large the number")
        void chatNeverAbbreviates() {
            String huge = Formats.money(new BigDecimal("987654321.99"));

            assertEquals("987,654,321.99", huge);
            assertTrue(huge.chars().noneMatch(c -> c == 'M' || c == 'B'),
                    "SPEC 23.7: a player reading a transaction wants the exact figure");
        }

        @Test
        @DisplayName("negative amounts keep their sign and their separators")
        void negatives() {
            assertEquals("-1,500.00", Formats.money(new BigDecimal("-1500")));
        }

        @Test
        @DisplayName("fractions of a cent are floored, never rounded up")
        void flooring() {
            // Rounding up would mint a fraction of a coin every time a figure was displayed,
            // and SPEC 17.3 case 26 already floors amounts a player types.
            assertEquals("0.99", Formats.money(new BigDecimal("0.999")));
        }
    }

    @Nested
    @DisplayName("compact money, for action bars and boss bars only")
    class CompactMoney {

        @Test
        @DisplayName("under a million it is the ordinary figure")
        void belowThreshold() {
            assertEquals("999,999.00", Formats.moneyCompact(new BigDecimal("999999")));
        }

        @Test
        @DisplayName("above a million it abbreviates, SPEC 23.7's own example")
        void abbreviates() {
            assertEquals("1.25M", Formats.moneyCompact(new BigDecimal("1250000")));
            assertEquals("1M", Formats.moneyCompact(new BigDecimal("1000000")));
        }

        @Test
        @DisplayName("it keeps going past a billion")
        void largerSuffixes() {
            assertEquals("2.5B", Formats.moneyCompact(new BigDecimal("2500000000")));
            assertEquals("1.5T", Formats.moneyCompact(new BigDecimal("1500000000000")));
        }

        @Test
        @DisplayName("the two renderings differ, which is the whole point of having both")
        void differsFromChat() {
            BigDecimal amount = new BigDecimal("1250000");

            assertNotEquals(Formats.money(amount), Formats.moneyCompact(amount));
        }
    }

    @Nested
    @DisplayName("durations, SPEC 23.7")
    class Durations {

        @Test
        @DisplayName("SPEC's own three examples")
        void specExamples() {
            assertEquals("2d 4h", Formats.duration((2 * 86_400 + 4 * 3_600) * 1000L));
            assertEquals("18m", Formats.duration(18 * 60_000L));
            assertEquals("45s", Formats.duration(45_000L));
        }

        @Test
        @DisplayName("at most two units, so it reads as a glance")
        void twoUnitsAtMost() {
            String value = Formats.duration((3 * 86_400 + 5 * 3_600 + 42 * 60 + 9) * 1000L);

            assertEquals("3d 5h", value);
            assertEquals(2, value.split(" ").length);
        }

        @Test
        @DisplayName("a whole unit drops the empty second one")
        void dropsZeroes() {
            assertEquals("2d", Formats.duration(2 * 86_400_000L));
            assertEquals("3h", Formats.duration(3 * 3_600_000L));
            assertEquals("5m", Formats.duration(5 * 60_000L));
        }

        @Test
        @DisplayName("zero and negative read 0s rather than an empty string")
        void nonPositive() {
            // A message with a hole where the time should be looks broken.
            assertEquals("0s", Formats.duration(0));
            assertEquals("0s", Formats.duration(-5000));
        }
    }

    @Nested
    @DisplayName("coordinates, SPEC 23.7")
    class Coordinates {

        @Test
        @DisplayName("chunks are (x, z) and blocks are (x, y, z)")
        void shapes() {
            assertEquals("(12, -34)", Formats.chunk(12, -34));
            assertEquals("(100, 64, -200)", Formats.block(100, 64, -200));
        }
    }

    @Nested
    @DisplayName("counts")
    class Counts {

        @Test
        @DisplayName("thousands separated, no decimals")
        void separated() {
            assertEquals("1,234", Formats.count(1234));
            assertEquals("0", Formats.count(0));
        }
    }
}
