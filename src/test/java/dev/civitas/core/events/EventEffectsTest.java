package dev.civitas.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.city.CityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What each SPEC 13.5 event changes, and just as importantly what it does not.
 *
 * <p>Nine systems read these multipliers. The failure that matters is not one that fails to
 * apply, which somebody notices within the hour; it is one that fails to <em>stop</em>, or one
 * that quietly changes something its event was never meant to touch. Both are tested here by
 * asserting the whole set of effects for every event rather than only the one it should move.
 */
class EventEffectsTest {

    @TempDir
    Path directory;

    private ConfigManager configs;
    private AtomicReference<ServerEvent> running;
    private EventEffects effects;

    @BeforeEach
    void setUp() {
        configs = new ConfigManager(PluginResources.ofClasspath(
                directory.resolve("plugin").toFile(), CityTestSupport.quietLogger()));
        configs.loadAll();
        running = new AtomicReference<>();
        effects = new EventEffects(configs, () -> Optional.ofNullable(running.get()));
    }

    private void activate(ServerEventType type) {
        running.set(new ServerEvent(1, type, 0L, Long.MAX_VALUE, true));
    }

    /** Every multiplier at once, so a test can assert on the whole surface. */
    private record Surface(BigDecimal sell, BigDecimal buy, Optional<Double> tax,
                           BigDecimal claim, boolean freeCity, BigDecimal upkeep,
                           BigDecimal farming, BigDecimal mining, double crops, double ore) { }

    private Surface surface() {
        return new Surface(
                effects.sellPriceMultiplier(),
                effects.buyPriceMultiplier(),
                effects.taxPercentOverride(),
                effects.claimCostMultiplier(),
                effects.isCityCreationFree(),
                effects.upkeepMultiplier(),
                effects.questRewardMultiplier("farming"),
                effects.questRewardMultiplier("mining"),
                effects.cropGrowthMultiplier(),
                effects.oreDropMultiplier());
    }

    private static final Surface NEUTRAL = new Surface(
            BigDecimal.ONE, BigDecimal.ONE, Optional.empty(), BigDecimal.ONE, false,
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1.0, 1.0);

    private void assertNeutralExcept(String... movedFields) {
        Surface now = surface();
        java.util.Set<String> moved = java.util.Set.of(movedFields);

        if (!moved.contains("sell")) {
            assertEquals(0, NEUTRAL.sell().compareTo(now.sell()), "sell price moved");
        }
        if (!moved.contains("buy")) {
            assertEquals(0, NEUTRAL.buy().compareTo(now.buy()), "buy price moved");
        }
        if (!moved.contains("tax")) {
            assertTrue(now.tax().isEmpty(), "the tax was overridden");
        }
        if (!moved.contains("claim")) {
            assertEquals(0, NEUTRAL.claim().compareTo(now.claim()), "claim cost moved");
        }
        if (!moved.contains("freeCity")) {
            assertFalse(now.freeCity(), "city creation became free");
        }
        if (!moved.contains("upkeep")) {
            assertEquals(0, NEUTRAL.upkeep().compareTo(now.upkeep()), "upkeep moved");
        }
        if (!moved.contains("farming")) {
            assertEquals(0, NEUTRAL.farming().compareTo(now.farming()), "farming rewards moved");
        }
        if (!moved.contains("mining")) {
            assertEquals(0, NEUTRAL.mining().compareTo(now.mining()), "mining rewards moved");
        }
        if (!moved.contains("crops")) {
            assertEquals(1.0, now.crops(), 1e-9, "crop growth moved");
        }
        if (!moved.contains("ore")) {
            assertEquals(1.0, now.ore(), 1e-9, "ore drops moved");
        }
    }

    // ==================================================================================
    // Nothing running
    // ==================================================================================

    @Test
    @DisplayName("with no event running, every effect is neutral")
    void neutralByDefault() {
        assertNeutralExcept();
        assertTrue(effects.activeType().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(ServerEventType.class)
    @DisplayName("an event that ended leaves nothing behind")
    void effectsStopWhenTheEventDoes(ServerEventType type) {
        // The failure this guards is the expensive one: a multiplier that outlives its event
        // is a permanent, silent change nobody can attribute to anything.
        activate(type);
        running.set(null);

        assertNeutralExcept();
    }

    // ==================================================================================
    // One test per event, SPEC 13.5
    // ==================================================================================

    @Test
    @DisplayName("Market Boom raises what the market pays, and nothing else")
    void marketBoom() {
        activate(ServerEventType.MARKET_BOOM);

        assertEquals(0, new BigDecimal("1.400000").compareTo(effects.sellPriceMultiplier()));
        assertNeutralExcept("sell");
    }

    @Test
    @DisplayName("Market Crash lowers both sides of the spread")
    void marketCrash() {
        activate(ServerEventType.MARKET_CRASH);

        assertEquals(0, new BigDecimal("0.700000").compareTo(effects.sellPriceMultiplier()));
        assertEquals(0, new BigDecimal("0.700000").compareTo(effects.buyPriceMultiplier()));
        assertNeutralExcept("sell", "buy");
    }

    @Test
    @DisplayName("Harvest Festival speeds crops and pays farming quests double")
    void harvestFestival() {
        activate(ServerEventType.HARVEST_FESTIVAL);

        assertEquals(2.0, effects.cropGrowthMultiplier(), 1e-9);
        assertEquals(0, BigDecimal.valueOf(2.0).compareTo(effects.questRewardMultiplier("farming")));
        assertNeutralExcept("crops", "farming");
    }

    @Test
    @DisplayName("Gold Rush enriches ore and pays mining quests double")
    void goldRush() {
        activate(ServerEventType.GOLD_RUSH);

        assertEquals(2.0, effects.oreDropMultiplier(), 1e-9);
        assertEquals(0, BigDecimal.valueOf(2.0).compareTo(effects.questRewardMultiplier("mining")));
        assertNeutralExcept("ore", "mining");
    }

    @Test
    @DisplayName("Founders' Week discounts land and makes founding free")
    void foundersWeek() {
        activate(ServerEventType.FOUNDERS_WEEK);

        assertEquals(0, new BigDecimal("0.750000").compareTo(effects.claimCostMultiplier()));
        assertTrue(effects.isCityCreationFree());
        assertNeutralExcept("claim", "freeCity");
    }

    @Test
    @DisplayName("Double Upkeep doubles upkeep and touches nothing else")
    void doubleUpkeep() {
        activate(ServerEventType.DOUBLE_UPKEEP);

        assertEquals(0, BigDecimal.valueOf(2.0).compareTo(effects.upkeepMultiplier()));
        assertNeutralExcept("upkeep");
    }

    @Test
    @DisplayName("Tax Holiday overrides the tax rate rather than multiplying it")
    void taxHoliday() {
        activate(ServerEventType.TAX_HOLIDAY);

        // An override, so a city that bought the SPEC 5.7 Market Access discount still has it
        // when the holiday ends.
        assertEquals(0.0, effects.taxPercentOverride().orElseThrow(), 1e-9);
        assertNeutralExcept("tax");
    }

    @Test
    @DisplayName("Invasion changes no multiplier at all; it spawns things instead")
    void invasion() {
        activate(ServerEventType.INVASION);

        assertTrue(effects.isInvasionActive());
        assertNeutralExcept();
    }

    // ==================================================================================
    // Reading the definitions
    // ==================================================================================

    @Test
    @DisplayName("a quest category no event touches is never multiplied")
    void unrelatedCategoriesAreUntouched() {
        activate(ServerEventType.HARVEST_FESTIVAL);

        assertEquals(0, BigDecimal.ONE.compareTo(effects.questRewardMultiplier("mining")));
        assertEquals(0, BigDecimal.ONE.compareTo(effects.questRewardMultiplier("building")));
        assertEquals(0, BigDecimal.ONE.compareTo(effects.questRewardMultiplier(null)));
    }

    @ParameterizedTest
    @EnumSource(ServerEventType.class)
    @DisplayName("every event has a duration in the shipped config")
    void everyEventHasADuration(ServerEventType type) {
        assertTrue(effects.durationMillis(type) > 0, type + " has no duration");
    }

    @Test
    @DisplayName("Double Upkeep is announced 48 hours ahead, the rest 30 minutes")
    void announcementLead() {
        // SPEC 13.5 singles this one out: "a rare sink event, announced 48h in advance".
        assertEquals(48L * 3_600_000L,
                effects.announceLeadMillis(ServerEventType.DOUBLE_UPKEEP));
        assertEquals(30L * 60_000L,
                effects.announceLeadMillis(ServerEventType.MARKET_BOOM));
    }

    @Test
    @DisplayName("event keys are unique and resolve from what an operator types")
    void keysResolve() {
        for (ServerEventType type : ServerEventType.all()) {
            assertEquals(type, ServerEventType.parse(type.key()).orElseThrow());
            assertEquals(type, ServerEventType.parse(type.key().toUpperCase(java.util.Locale.ROOT))
                    .orElseThrow());
        }
        assertTrue(ServerEventType.parse("not-an-event").isEmpty());
        assertTrue(ServerEventType.parse(null).isEmpty());
        assertEquals(8, ServerEventType.all().size(), "SPEC 13.5 lists eight events");
    }
}
