package dev.civitas.core.events;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.market.MarketItem;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SPEC 13.5 effects seen from the systems they change, not from the event that changes them.
 *
 * <p>{@code EventEffectsTest} proves the multipliers are right. These prove they are actually
 * read: an effect wired to nothing is the failure that no amount of testing the effect itself
 * would catch, and it has already happened once in this project, when M11 stored a
 * Fortification level nothing read.
 */
class EventHooksTest {

    @TempDir
    Path directory;

    private static final long NOW = 5_000_000_000L;

    private CityTestSupport support;
    private EventService events;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        events = new EventService(support.daos.serverEvents(), support.configs);

        // Exactly what CivitasPlugin does at startup.
        support.pricing.useEvents(events.effects());
        support.costs.useEvents(events.effects());
        support.cities.useEvents(events.effects());
        support.upkeep.useEvents(events.effects());
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private void start(ServerEventType type) {
        Result<ServerEvent> started = await(events.start(type, NOW));
        assertTrue(started.isSuccess(), reasonOf(started));
    }

    private MarketItem anyItem() {
        return support.marketRegistry.catalogue().stream().findFirst().orElseThrow();
    }

    // ==================================================================================
    // Market
    // ==================================================================================

    @Test
    @DisplayName("Market Boom reaches the price a player is actually quoted")
    void boomChangesTheQuotedPrice() {
        MarketItem item = anyItem();
        BigDecimal before = support.pricing.unitSellPrice(item, item.targetStock());

        start(ServerEventType.MARKET_BOOM);
        BigDecimal during = support.pricing.unitSellPrice(item, item.targetStock());

        assertTrue(during.compareTo(before) > 0,
                "the boom did not reach the price: was " + before + ", now " + during);
    }

    @Test
    @DisplayName("and the price goes back when it ends")
    void boomStopsWhenItEnds() {
        MarketItem item = anyItem();
        BigDecimal before = support.pricing.unitSellPrice(item, item.targetStock());

        start(ServerEventType.MARKET_BOOM);
        await(events.stop(NOW + TimeUnit.HOURS.toMillis(6)));

        assertEquals(0, before.compareTo(support.pricing.unitSellPrice(item, item.targetStock())));
    }

    @Test
    @DisplayName("Market Crash lowers what the market charges as well as what it pays")
    void crashLowersBothSides() {
        MarketItem item = anyItem();
        BigDecimal sellBefore = support.pricing.unitSellPrice(item, item.targetStock());
        BigDecimal buyBefore = support.pricing.unitBuyPrice(item, item.targetStock());

        start(ServerEventType.MARKET_CRASH);

        assertTrue(support.pricing.unitSellPrice(item, item.targetStock()).compareTo(sellBefore) < 0);
        assertTrue(support.pricing.unitBuyPrice(item, item.targetStock()).compareTo(buyBefore) < 0);
    }

    @Test
    @DisplayName("Tax Holiday takes the SPEC 4.3 tax to zero, for every city")
    void taxHolidayZeroesTheTax() {
        assertTrue(support.pricing.taxPercent(0) > 0);

        start(ServerEventType.TAX_HOLIDAY);

        assertEquals(0.0, support.pricing.taxPercent(0), 1e-9);
        assertEquals(0.0, support.pricing.taxPercent(5), 1e-9);
    }

    @Test
    @DisplayName("and a city's bought Market Access discount survives the holiday")
    void taxHolidayDoesNotEatTheUpgrade() {
        double withUpgrade = support.pricing.taxPercent(3);

        start(ServerEventType.TAX_HOLIDAY);
        await(events.stop(NOW + TimeUnit.HOURS.toMillis(24)));

        assertEquals(withUpgrade, support.pricing.taxPercent(3), 1e-9,
                "the SPEC 5.7 discount must still be there afterwards");
    }

    // ==================================================================================
    // Land
    // ==================================================================================

    @Test
    @DisplayName("Founders' Week discounts what a chunk actually costs")
    void foundersWeekDiscountsClaims() {
        BigDecimal before = support.costs.totalFor(20, 0, 1, 0L);

        start(ServerEventType.FOUNDERS_WEEK);
        BigDecimal during = support.costs.totalFor(20, 0, 1, 0L);

        // 75% of the normal price, per the shipped config. Compared to the nearest coin
        // rather than exactly: the cost engine rounds once at the end, so applying the
        // discount inside the formula and applying it to the result can differ by a cent.
        BigDecimal expected = before.multiply(new BigDecimal("0.75"));
        assertTrue(expected.subtract(during).abs().compareTo(BigDecimal.ONE) <= 0,
                "expected about " + expected + " but the engine charged " + during);
        assertTrue(during.compareTo(before) < 0, "the discount did not reach the price");
    }

    @Test
    @DisplayName("and founding a city is free while it runs")
    void foundersWeekMakesFoundingFree() {
        UUID founder = support.givenPlayer("Aeneas", new BigDecimal("100.00"),
                TimeUnit.HOURS.toMillis(10));

        // 100 C is nowhere near the 10,000 C fee, so this can only succeed if it is free.
        start(ServerEventType.FOUNDERS_WEEK);
        Result<City> founded = await(support.cities.create(founder, "Lavinium",
                CityTestSupport.placement(60, 60)));

        assertTrue(founded.isSuccess(), reasonOf(founded));
        assertEquals(0, new BigDecimal("100.00")
                .compareTo(support.playerRow(founder).balance()),
                "the founder should not have been charged");
    }

    @Test
    @DisplayName("without the event, founding on a hundred coins is refused")
    void withoutTheEventFoundingCostsMoney() {
        UUID founder = support.givenPlayer("Aeneas", new BigDecimal("100.00"),
                TimeUnit.HOURS.toMillis(10));

        Result<City> founded = await(support.cities.create(founder, "Lavinium",
                CityTestSupport.placement(60, 60)));

        assertEquals("INSUFFICIENT_FUNDS", reasonOf(founded));
    }

    // ==================================================================================
    // Upkeep
    // ==================================================================================

    @Test
    @DisplayName("Double Upkeep doubles what a city is charged")
    void doubleUpkeepDoubles() {
        BigDecimal landValue = new BigDecimal("100000.00");
        BigDecimal before = support.upkeep.dailyUpkeep(landValue);

        start(ServerEventType.DOUBLE_UPKEEP);

        assertEquals(0, before.multiply(BigDecimal.valueOf(2))
                .compareTo(support.upkeep.dailyUpkeep(landValue)));
    }

    @Test
    @DisplayName("and it doubles the discounted figure, not the undiscounted one")
    void doubleUpkeepRespectsTheUpgrade() {
        BigDecimal landValue = new BigDecimal("100000.00");
        BigDecimal discounted = support.upkeep.dailyUpkeep(landValue, dev.civitas.storage.SqlDialect.zero(),
                dev.civitas.storage.SqlDialect.zero(), 5);

        start(ServerEventType.DOUBLE_UPKEEP);
        BigDecimal during = support.upkeep.dailyUpkeep(landValue, dev.civitas.storage.SqlDialect.zero(),
                dev.civitas.storage.SqlDialect.zero(), 5);

        // A city that paid for cheaper upkeep keeps its discount and pays double the
        // discounted figure, rather than the upgrade being cancelled by the event.
        assertEquals(0, discounted.multiply(BigDecimal.valueOf(2)).compareTo(during));
        assertTrue(during.compareTo(support.upkeep.dailyUpkeep(landValue)) < 0,
                "a maxed Treasury Interest city should still pay less than an unupgraded one");
    }

    // ==================================================================================
    // Nothing running
    // ==================================================================================

    @Test
    @DisplayName("with no event, every hooked system behaves exactly as before")
    void neutralWhenNothingRuns() {
        MarketItem item = anyItem();

        assertEquals(0, BigDecimal.ONE.compareTo(events.effects().sellPriceMultiplier()));
        assertTrue(support.pricing.taxPercent(0) > 0);
        assertEquals(0, support.costs.totalFor(20, 0, 1, 0L)
                .compareTo(support.costs.totalFor(20, 0, 1, 0L)));
        assertTrue(support.pricing.unitSellPrice(item, item.targetStock()).signum() > 0);
    }
}
