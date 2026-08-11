package dev.civitas.core.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.CityTestSupport;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 34.3's starter chain.
 *
 * <p>{@link Idempotency} is the group that matters. Three of the five steps hang off events a
 * player can repeat freely — selling, walking into a city, teleporting — so a chain that paid on
 * every trigger would be the best income source in the game for a player who ran back and forth
 * across a claim border.
 */
class OnboardingServiceTest {

    @TempDir
    Path directory;

    private static final long NOW = 1_700_000_000_000L;

    private CityTestSupport cities;
    private OnboardingService onboarding;
    private UUID player;

    @BeforeEach
    void setUp() {
        cities = CityTestSupport.open(directory);
        onboarding = new OnboardingService(cities.daos.onboarding(), cities.economy,
                cities.configs, quiet());
        player = cities.givenEligiblePlayer("Newcomer");
    }

    @AfterEach
    void tearDown() {
        cities.close();
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("onboarding-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private OnboardingService.Completion complete(StarterStep step) {
        Result<OnboardingService.Completion> result =
                await(onboarding.complete(player, step, NOW));
        return result.orElseThrow();
    }

    private BigDecimal balance() {
        return cities.economy.balanceOrZero(player);
    }

    @Nested
    @DisplayName("paying a step")
    class Paying {

        @Test
        @DisplayName("the reward lands and the step is recorded")
        void pays() {
            BigDecimal before = balance();

            OnboardingService.Completion done = complete(StarterStep.SELL_SOMETHING);

            assertTrue(done.wasNew());
            assertEquals(0, done.reward().compareTo(new BigDecimal("500")));
            assertEquals(0, balance().subtract(before).compareTo(new BigDecimal("500")));
            assertEquals(Set.of(StarterStep.SELL_SOMETHING), await(onboarding.completed(player)));
        }

        @Test
        @DisplayName("SPEC 34.3's five rewards total 5,000 C")
        void theWholeChain() {
            // 5,000 on top of SPEC 4.2's 2,000 starting balance is 70% of a city founding fee,
            // which is the pace SPEC 34.3 is aiming at: a motivated new player can found one in
            // their first session or two.
            BigDecimal before = balance();
            for (StarterStep step : StarterStep.values()) {
                complete(step);
            }

            assertEquals(0, balance().subtract(before).compareTo(new BigDecimal("5000")));
        }
    }

    @Nested
    @DisplayName("once per account, whatever fires the trigger")
    class Idempotency {

        @Test
        @DisplayName("a second completion pays nothing and says so")
        void secondIsFree() {
            complete(StarterStep.VISIT_CITY);
            BigDecimal afterFirst = balance();

            OnboardingService.Completion again = complete(StarterStep.VISIT_CITY);

            assertFalse(again.wasNew());
            assertEquals(0, again.reward().signum());
            assertEquals(0, balance().compareTo(afterFirst), "and nothing else moved");
        }

        @Test
        @DisplayName("walking a border back and forth is not an income source")
        void repeatedTriggers() {
            // The realistic abuse: SPEC 34.3's visit step fires on entering a city, and a player
            // standing on a claim boundary can fire it as fast as they can walk.
            BigDecimal before = balance();
            for (int i = 0; i < 50; i++) {
                complete(StarterStep.VISIT_CITY);
            }

            assertEquals(0, balance().subtract(before).compareTo(new BigDecimal("750")),
                    "fifty crossings, one payment");
        }
    }

    @Nested
    @DisplayName("progress")
    class Progress {

        @Test
        @DisplayName("the next step is the first one not done, in SPEC 34.3's order")
        void nextStep() {
            assertEquals(StarterStep.SELL_SOMETHING,
                    await(onboarding.nextStep(player)).orElseThrow());

            complete(StarterStep.SELL_SOMETHING);
            assertEquals(StarterStep.TRAVEL, await(onboarding.nextStep(player)).orElseThrow());
        }

        @Test
        @DisplayName("out-of-order completion is fine, because nothing here is a gate")
        void outOfOrder() {
            // SPEC 34.2: "No forced tutorial, ever." A player who founds a city in their first
            // ten minutes has done step five, and refusing to pay for it because they skipped
            // step two would be the gate SPEC forbids.
            complete(StarterStep.SETTLE);

            assertEquals(StarterStep.SELL_SOMETHING,
                    await(onboarding.nextStep(player)).orElseThrow());
            assertTrue(await(onboarding.completed(player)).contains(StarterStep.SETTLE));
        }

        @Test
        @DisplayName("the last step reports the chain finished, whichever it is")
        void finished() {
            for (StarterStep step : StarterStep.values()) {
                if (step != StarterStep.TRAVEL) {
                    complete(step);
                }
            }
            assertTrue(await(onboarding.nextStep(player)).isPresent());

            assertTrue(complete(StarterStep.TRAVEL).chainFinished());
            assertTrue(await(onboarding.nextStep(player)).isEmpty());
        }
    }

    @Nested
    @DisplayName("SPEC 34.3's fifth step branches, and that is the point")
    class Branch {

        @Test
        @DisplayName("one step covers both a city and a mining claim")
        void oneStepTwoRoutes() {
            // SPEC 34.1: "A player may play indefinitely without a city... Roughly 30% of players
            // on any server will never join a group, and designing them out is designing out 30%
            // of the server." Two steps here would make one of the two routes the wrong answer.
            assertEquals(5, StarterStep.values().length);
            assertEquals(StarterStep.SETTLE,
                    StarterStep.values()[StarterStep.values().length - 1]);
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("every step's reward is a config key, per the hard rule")
        void rewardsAreConfigurable() {
            for (StarterStep step : StarterStep.values()) {
                cities.configs.get(dev.civitas.config.ConfigFile.ONBOARDING)
                        .set(step.rewardKey(), "1");
                assertEquals(0, onboarding.rewardFor(step).compareTo(BigDecimal.ONE),
                        step + " ignored its config key");
            }
        }

        @Test
        @DisplayName("switched off, nothing is recorded and nothing is paid")
        void disabled() {
            cities.configs.get(dev.civitas.config.ConfigFile.ONBOARDING)
                    .set("onboarding.starter-quests-enabled", false);
            BigDecimal before = balance();

            complete(StarterStep.SELL_SOMETHING);

            assertEquals(0, balance().compareTo(before));
            assertTrue(await(onboarding.completed(player)).isEmpty());
        }

        @Test
        @DisplayName("a reward that is not a number falls back to SPEC 34.3's figure")
        void badConfig() {
            cities.configs.get(dev.civitas.config.ConfigFile.ONBOARDING)
                    .set(StarterStep.SETTLE.rewardKey(), "not a number");

            assertEquals(0, onboarding.rewardFor(StarterStep.SETTLE)
                    .compareTo(new BigDecimal("2500")));
        }
    }
}
