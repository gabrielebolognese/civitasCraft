package dev.civitas.core.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How a disbanded city's treasury is divided, SPEC 21.4 F6.
 *
 * <p>SPEC 17.1 case 10 split it evenly. F6 identifies that as a laundering channel: "Create a
 * city with alts, deposit, disband, treasury splits evenly among members. Bypasses the 25%
 * withdrawal cap." The fix is to split by lifetime contribution, so "a member who contributed
 * nothing receives nothing".
 *
 * <p>In this package because {@code disbandShares} is a pure function worth testing directly,
 * and widening it to public purely so a test in another package could reach it would be
 * changing the API to suit the test.
 */
class DisbandShareTest {

    private static CityMember member(String contribution) {
        return new CityMember(UUID.randomUUID(), 1, 1, 0L, new BigDecimal(contribution));
    }

    private static BigDecimal totalOf(Map<UUID, BigDecimal> shares) {
        return shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ==================================================================================
    // The rule
    // ==================================================================================

    @Nested
    @DisplayName("by contribution, SPEC 21.4 F6")
    class ByContribution {

        @Test
        @DisplayName("a member who contributed nothing receives nothing")
        void freeloaderGetsNothing() {
            // The exploit in one assertion. Under the even split these two alts would take
            // two thirds of a treasury they never put anything into.
            CityMember worker = member("9000");
            CityMember alt = member("0");
            CityMember otherAlt = member("0");

            Map<UUID, BigDecimal> shares = CityService.disbandShares(
                    List.of(worker, alt, otherAlt), new BigDecimal("9000.00"));

            assertEquals(0, shares.getOrDefault(alt.uuid(), BigDecimal.ZERO).signum());
            assertEquals(0, shares.getOrDefault(otherAlt.uuid(), BigDecimal.ZERO).signum());
            assertEquals(0, shares.get(worker.uuid()).compareTo(new BigDecimal("9000.00")));
        }

        @Test
        @DisplayName("shares are proportional to what each member put in")
        void proportional() {
            CityMember big = member("7500");
            CityMember small = member("2500");

            Map<UUID, BigDecimal> shares = CityService.disbandShares(
                    List.of(big, small), new BigDecimal("1000.00"));

            assertEquals(0, shares.get(big.uuid()).compareTo(new BigDecimal("750.00")));
            assertEquals(0, shares.get(small.uuid()).compareTo(new BigDecimal("250.00")));
        }

        @Test
        @DisplayName("the whole treasury is handed out, to the cent")
        void nothingIsLost() {
            // Money that vanishes in a rounding gap is money somebody deposited, and SPEC 1.5
            // makes every coin auditable. Three-way splits of odd amounts are where this
            // breaks if it is going to.
            for (String treasury : List.of("1000.00", "1000.01", "0.03", "9999.99", "7.77")) {
                CityMember a = member("1");
                CityMember b = member("1");
                CityMember c = member("1");

                Map<UUID, BigDecimal> shares = CityService.disbandShares(
                        List.of(a, b, c), new BigDecimal(treasury));

                assertEquals(0, totalOf(shares).compareTo(new BigDecimal(treasury)),
                        "treasury " + treasury + " paid out " + totalOf(shares));
            }
        }

        @Test
        @DisplayName("the rounding remainder goes to the largest contributor")
        void remainderGoesToTheLargestContributor() {
            // The one recipient nobody can arrange to be by adding alts, which is the point.
            CityMember big = member("100");
            CityMember small = member("1");

            Map<UUID, BigDecimal> shares = CityService.disbandShares(
                    List.of(small, big), new BigDecimal("0.03"));

            assertEquals(0, totalOf(shares).compareTo(new BigDecimal("0.03")));
            assertTrue(shares.get(big.uuid()).compareTo(
                            shares.getOrDefault(small.uuid(), BigDecimal.ZERO)) > 0,
                    "the odd cent went to the smaller contributor");
        }
    }

    // ==================================================================================
    // The cases SPEC does not spell out
    // ==================================================================================

    @Nested
    @DisplayName("edges")
    class Edges {

        @Test
        @DisplayName("a treasury nobody contributed to is split evenly rather than burned")
        void noContributionsFallsBackToEven() {
            // Reachable: contest prizes and war payouts are paid to the city, not by a member.
            // Burning that money would destroy something that belongs to somebody.
            CityMember a = member("0");
            CityMember b = member("0");

            Map<UUID, BigDecimal> shares = CityService.disbandShares(
                    List.of(a, b), new BigDecimal("500.00"));

            assertEquals(0, shares.get(a.uuid()).compareTo(new BigDecimal("250.00")));
            assertEquals(0, shares.get(b.uuid()).compareTo(new BigDecimal("250.00")));
            assertEquals(0, totalOf(shares).compareTo(new BigDecimal("500.00")));
        }

        @Test
        @DisplayName("an empty treasury pays nobody")
        void emptyTreasury() {
            assertTrue(CityService.disbandShares(
                    List.of(member("100")), BigDecimal.ZERO).isEmpty());
        }

        @Test
        @DisplayName("a city with no members pays nobody rather than throwing")
        void noMembers() {
            assertTrue(CityService.disbandShares(List.of(), new BigDecimal("100.00")).isEmpty());
        }

        @Test
        @DisplayName("one member takes everything however little they contributed")
        void soleMember() {
            CityMember only = member("1");

            Map<UUID, BigDecimal> shares = CityService.disbandShares(
                    List.of(only), new BigDecimal("50000.00"));

            assertEquals(0, shares.get(only.uuid()).compareTo(new BigDecimal("50000.00")));
        }

        @Test
        @DisplayName("nobody is ever paid a negative share")
        void neverNegative() {
            Map<UUID, BigDecimal> shares = CityService.disbandShares(
                    List.of(member("100"), member("0")), new BigDecimal("0.01"));

            assertFalse(shares.values().stream().anyMatch(share -> share.signum() < 0));
        }
    }
}
