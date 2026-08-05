package dev.civitas.core.contest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.city.CityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 13.4's anti-abuse rules, one test each.
 *
 * <p>These are the rules that decide whether the contest ladder is worth climbing, so each is
 * tested on its own rather than through a vote that happens to exercise several at once.
 */
class VoteWeightingTest {

    @TempDir
    Path directory;

    private VoteWeighting weighting;

    @BeforeEach
    void setUp() {
        ConfigManager configs = new ConfigManager(PluginResources.ofClasspath(
                directory.resolve("plugin").toFile(), CityTestSupport.quietLogger()));
        configs.loadAll();
        weighting = new VoteWeighting(configs);
    }

    @Test
    @DisplayName("an established account votes at full weight")
    void establishedAccountCountsFully() {
        assertEquals(VoteWeighting.FULL,
                weighting.weigh(TimeUnit.HOURS.toMillis(50), false), 1e-9);
    }

    @Test
    @DisplayName("under five hours of playtime, a vote is weighted down but still counts")
    void newAccountIsWeightedDown() {
        // SPEC 13.4: "Votes from accounts with under 5 hours playtime are weighted 0.25x".
        double weight = weighting.weigh(TimeUnit.HOURS.toMillis(4), false);

        assertEquals(0.25, weight, 1e-9);
        assertTrue(weight > 0, "a new account's vote is reduced, not thrown away");
    }

    @Test
    @DisplayName("exactly five hours is enough")
    void theBarIsInclusive() {
        assertEquals(VoteWeighting.FULL,
                weighting.weigh(weighting.lowPlaytimeMs(), false), 1e-9);
    }

    @Test
    @DisplayName("a vote from a shared connection is discarded outright")
    void sharedConnectionIsDiscarded() {
        // SPEC 13.4: "Votes from accounts sharing an IP with a member of the entered city are
        // discarded". Zero, not reduced.
        assertEquals(VoteWeighting.DISCARDED,
                weighting.weigh(TimeUnit.HOURS.toMillis(500), true), 1e-9);
    }

    @Test
    @DisplayName("the shared-connection rule outranks the playtime one")
    void discardBeatsWeighting() {
        assertEquals(VoteWeighting.DISCARDED,
                weighting.weigh(TimeUnit.MINUTES.toMillis(1), true), 1e-9);
    }

    @Test
    @DisplayName("voting for your own city is a self vote; voting for another is not")
    void selfVote() {
        assertTrue(weighting.isSelfVote(7, 7));
        assertFalse(weighting.isSelfVote(7, 8));
        assertFalse(weighting.isSelfVote(null, 8), "a player with no city cannot self-vote");
    }

    @Test
    @DisplayName("scores outside one to ten are refused")
    void scoreRange() {
        assertTrue(weighting.isScoreInRange(1));
        assertTrue(weighting.isScoreInRange(10));
        assertFalse(weighting.isScoreInRange(0));
        assertFalse(weighting.isScoreInRange(11));
        assertFalse(weighting.isScoreInRange(-5));
    }

    @Test
    @DisplayName("the shipped config asks for a build-window check")
    void buildWindowCheckIsRequested() {
        // The operator asks for it; ContestService is where the honest answer lives about
        // whether it can be performed.
        assertTrue(weighting.verifiesBuildWindow());
    }
}
