package dev.civitas.core.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import dev.civitas.core.economy.CircuitBreaker.Action;
import dev.civitas.core.economy.CircuitBreaker.Trigger;
import dev.civitas.core.economy.CircuitBreaker.Trip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPEC 21.7's six rows.
 *
 * <p>SPEC 21.4 C6 says what this is worth: "You cannot enumerate future exploits. The circuit
 * breaker is the mitigation for the exploits you have not thought of yet, and it is <b>the single
 * most valuable safety mechanism in this section</b>."
 *
 * <p>{@link Scope} is the group that matters. SPEC 21.7 gives each row a <em>different</em> action,
 * and getting one wrong is either an overreaction that closes a healthy market or an underreaction
 * that leaves a faucet open — so the tests assert what each row does as much as when it fires.
 */
class CircuitBreakerTest {

    private static final CircuitBreaker.Thresholds DEFAULTS =
            new CircuitBreaker.Thresholds(true, 3.0, 10.0, 20.0, 15, 40, true);

    private final CircuitBreaker breaker = new CircuitBreaker(DEFAULTS);

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    @Nested
    @DisplayName("row 1: server-wide money creation in an hour")
    class HourlyCreation {

        @Test
        @DisplayName("three times the baseline is not a trip, and a hair over is")
        void boundary() {
            assertTrue(breaker.checkHourlyCreation(money("30000"), money("10000")).isEmpty(),
                    "exactly at the multiplier is inside it");
            assertTrue(breaker.checkHourlyCreation(money("30001"), money("10000")).isPresent());
        }

        @Test
        @DisplayName("it freezes sells, which is the row's whole point")
        void freezes() {
            Trip trip = breaker.checkHourlyCreation(money("100000"), money("10000")).orElseThrow();

            assertEquals(Trigger.HOURLY_CREATION, trip.trigger());
            assertEquals(Action.FREEZE_SELLS, trip.action());
            assertEquals(0, trip.ratio().compareTo(new BigDecimal("10.00")));
        }

        @Test
        @DisplayName("a server with no history yet never trips")
        void noBaseline() {
            // A new server creates its entire money supply in its first hours. Tripping on that
            // would close the market on day one, which is the single most damaging false
            // positive this mechanism can produce.
            assertTrue(breaker.checkHourlyCreation(money("5000000"), BigDecimal.ZERO).isEmpty());
        }
    }

    @Nested
    @DisplayName("row 2: one player's day against their own month")
    class PlayerIncome {

        @Test
        @DisplayName("it throttles that player and nobody else")
        void throttlesOnlyThem() {
            // SPEC 21.7 gives this row a different action on purpose. Closing the whole market
            // because one player had an extraordinary day would punish everybody for somebody
            // else's luck, and an extraordinary day is not by itself evidence of anything.
            Trip trip = breaker.checkPlayerIncome("Alice", money("500000"), money("20000"))
                    .orElseThrow();

            assertEquals(Action.THROTTLE_PLAYER, trip.action());
            assertEquals("Alice", trip.subject().orElseThrow());
            assertFalse(trip.action() == Action.FREEZE_SELLS);
        }

        @Test
        @DisplayName("a new account with no history is not flagged for its first good day")
        void noHistory() {
            assertTrue(breaker.checkPlayerIncome("New", money("50000"), BigDecimal.ZERO)
                    .isEmpty());
        }
    }

    @Nested
    @DisplayName("row 3: one item's hour against its own week")
    class ItemVolume {

        @Test
        @DisplayName("it takes that item off the buy list and leaves the market open")
        void suspendsOneItem() {
            // A new vector is usually one item — a dupe, a farm nobody costed — and closing the
            // whole market for it is the overreaction SPEC 21.7 spends a paragraph rejecting.
            Trip trip = breaker.checkItemVolume("DIAMOND", money("2000000"), money("50000"))
                    .orElseThrow();

            assertEquals(Action.SUSPEND_ITEM, trip.action());
            assertEquals("DIAMOND", trip.subject().orElseThrow());
        }

        @Test
        @DisplayName("twenty times is the line")
        void boundary() {
            assertTrue(breaker.checkItemVolume("WHEAT", money("20000"), money("1000")).isEmpty());
            assertTrue(breaker.checkItemVolume("WHEAT", money("20001"), money("1000")).isPresent());
        }
    }

    @Nested
    @DisplayName("rows 4 and 5: circulation week over week")
    class Inflation {

        @Test
        @DisplayName("15% warns and does nothing else, per SPEC 4.8")
        void warnsAtFifteen() {
            // SPEC 4.8: "There is no automatic money deletion beyond those two, deliberately,
            // because silent balance reduction destroys player trust."
            Trip trip = breaker.checkInflation(money("22")).orElseThrow();

            assertEquals(Trigger.INFLATION_WARN, trip.trigger());
            assertEquals(Action.WARN, trip.action());
        }

        @Test
        @DisplayName("40% freezes, because no legitimate week produces it")
        void freezesAtForty() {
            Trip trip = breaker.checkInflation(money("55")).orElseThrow();

            assertEquals(Trigger.INFLATION_FREEZE, trip.trigger());
            assertEquals(Action.FREEZE_SELLS, trip.action());
        }

        @Test
        @DisplayName("the higher rule wins, so a 55% week is not merely warned about")
        void freezeBeatsWarn() {
            assertEquals(Trigger.INFLATION_FREEZE,
                    breaker.checkInflation(money("100")).orElseThrow().trigger());
        }

        @Test
        @DisplayName("ordinary growth and a shrinking economy are both silent")
        void quiet() {
            assertTrue(breaker.checkInflation(money("8")).isEmpty());
            assertTrue(breaker.checkInflation(money("-12")).isEmpty());
        }
    }

    @Nested
    @DisplayName("row 6: SPEC 17.4 case 73, items that a rollback created")
    class Duplication {

        @Test
        @DisplayName("any growth at all is reported, because a rollback creates nothing")
        void anyGrowth() {
            // SPEC 11.8.3's no-drops rule is the defence; this is the check that it worked. There
            // is no tolerance because there is no legitimate amount of item creation here.
            List<Trip> trips = breaker.checkItemGrowth(
                    Map.of("DIAMOND", 100L, "STONE", 5000L),
                    Map.of("DIAMOND", 101L, "STONE", 5000L));

            assertEquals(1, trips.size());
            assertEquals(Trigger.ITEM_DUPLICATION, trips.get(0).trigger());
            assertEquals("DIAMOND", trips.get(0).subject().orElseThrow());
        }

        @Test
        @DisplayName("items destroyed by a war are not a finding")
        void shrinkingIsFine() {
            assertTrue(breaker.checkItemGrowth(
                    Map.of("DIAMOND", 100L), Map.of("DIAMOND", 40L)).isEmpty());
        }

        @Test
        @DisplayName("an item that did not exist before is counted from zero")
        void brandNew() {
            List<Trip> trips = breaker.checkItemGrowth(Map.of(), Map.of("NETHERITE_INGOT", 3L));

            assertEquals(1, trips.size());
            assertTrue(trips.get(0).detail().contains("+3"));
        }
    }

    @Nested
    @DisplayName("the scopes SPEC 21.7 assigns, which are the point of the table")
    class Scope {

        @Test
        @DisplayName("only two of the six rows can close the market")
        void whoCanFreeze() {
            // If a third row could freeze, one player's good day or one item's busy hour would
            // shut the economy — which is exactly the overreaction SPEC 21.7 argues against.
            assertEquals(Action.FREEZE_SELLS,
                    breaker.checkHourlyCreation(money("1000"), money("1")).orElseThrow().action());
            assertEquals(Action.FREEZE_SELLS,
                    breaker.checkInflation(money("99")).orElseThrow().action());

            assertEquals(Action.THROTTLE_PLAYER,
                    breaker.checkPlayerIncome("A", money("1000"), money("1")).orElseThrow()
                            .action());
            assertEquals(Action.SUSPEND_ITEM,
                    breaker.checkItemVolume("A", money("1000"), money("1")).orElseThrow()
                            .action());
            assertEquals(Action.WARN, breaker.checkInflation(money("20")).orElseThrow().action());
            assertEquals(Action.WARN, breaker.checkItemGrowth(Map.of(), Map.of("X", 1L))
                    .get(0).action());
        }
    }

    @Nested
    @DisplayName("SPEC 21.11's two switches")
    class Configuration {

        @Test
        @DisplayName("disabled means every rule is silent")
        void disabled() {
            CircuitBreaker off = new CircuitBreaker(
                    new CircuitBreaker.Thresholds(false, 3, 10, 20, 15, 40, true));

            assertTrue(off.checkHourlyCreation(money("999999"), money("1")).isEmpty());
            assertTrue(off.checkPlayerIncome("A", money("999999"), money("1")).isEmpty());
            assertTrue(off.checkItemVolume("A", money("999999"), money("1")).isEmpty());
            assertTrue(off.checkInflation(money("500")).isEmpty());
            assertTrue(off.checkItemGrowth(Map.of(), Map.of("X", 1L)).isEmpty());
        }

        @Test
        @DisplayName("WARN_ONLY still fires every rule, it just does not shut anything")
        void warnOnly() {
            // An operator who chose this chose to be TOLD rather than protected, which is a real
            // choice on a server whose staff are awake. It must not mean "detect nothing".
            CircuitBreaker warn = new CircuitBreaker(
                    new CircuitBreaker.Thresholds(true, 3, 10, 20, 15, 40, false));

            Trip trip = warn.checkHourlyCreation(money("1000"), money("1")).orElseThrow();
            assertEquals(Action.WARN, trip.action());
            assertEquals(Trigger.HOURLY_CREATION, trip.trigger(),
                    "the rule still fires and still says which one it was");

            assertEquals(Action.SUSPEND_ITEM,
                    warn.checkItemVolume("A", money("1000"), money("1")).orElseThrow().action(),
                    "and the per-item row is unaffected, because it never froze anything");
        }
    }
}
