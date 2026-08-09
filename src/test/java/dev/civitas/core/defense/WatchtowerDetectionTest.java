package dev.civitas.core.defense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.core.defense.DefenseUnitType.Ability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 27.3's Watchtower Keeper, the one unit that cannot fight.
 *
 * <p>"Detection radius 32 blocks. Applies Glowing to non-members and non-allies within radius,
 * 3-second refresh. Posts a message to city chat when an unknown player enters, rate-limited to
 * once per player per 5 minutes."
 */
class WatchtowerDetectionTest {

    private static final UUID VISITOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final long FIVE_MINUTES = 300_000L;

    @TempDir
    Path directory;

    private DefenseCatalogue catalogue;
    private WatchtowerDetection detection;

    @BeforeEach
    void setUp() {
        ConfigManager configs = new ConfigManager(
                PluginResources.ofClasspath(directory.toFile(), quiet()));
        configs.loadAll();
        catalogue = new DefenseCatalogue(configs, quiet());
        catalogue.load();
        detection = new WatchtowerDetection();
    }

    private static java.util.logging.Logger quiet() {
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger("WatchtowerDetectionTest");
        logger.setUseParentHandlers(false);
        return logger;
    }

    @Test
    @DisplayName("the Keeper's detection radius is 32 blocks and comes from its own range")
    void radiusIsThirtyTwo() {
        DefenseUnitType keeper = catalogue.byKey("watchtower-keeper").orElseThrow();

        assertEquals(32, keeper.range(), 1e-9);
        assertTrue(WatchtowerDetection.withinRadius(32, keeper.range()));
        assertFalse(WatchtowerDetection.withinRadius(32.01, keeper.range()));
    }

    @Test
    @DisplayName("it refreshes every three seconds and announces every five minutes")
    void bothIntervalsAreShipped() {
        DefenseUnitType keeper = catalogue.byKey("watchtower-keeper").orElseThrow();

        assertEquals(3, keeper.ability(Ability.GLOW_REFRESH_SECONDS, -1), 1e-9);
        assertEquals(5, keeper.ability(Ability.CHAT_ALERT_COOLDOWN_MINUTES, -1), 1e-9);
    }

    @Test
    @DisplayName("a stranger is announced once, then not again for five minutes")
    void chatIsRateLimited() {
        assertTrue(detection.claimAnnouncement(1, VISITOR, 0L, FIVE_MINUTES));
        assertFalse(detection.claimAnnouncement(1, VISITOR, FIVE_MINUTES - 1, FIVE_MINUTES),
                "a city with three towers announcing the same visitor three times is exactly "
                        + "the spam the limit exists to stop");
        assertTrue(detection.claimAnnouncement(1, VISITOR, FIVE_MINUTES, FIVE_MINUTES));
    }

    @Test
    @DisplayName("the limit is per player and per city, not global")
    void limitIsPerSighting() {
        assertTrue(detection.claimAnnouncement(1, VISITOR, 0L, FIVE_MINUTES));
        assertTrue(detection.claimAnnouncement(1, OTHER, 0L, FIVE_MINUTES),
                "a second visitor is news");
        assertTrue(detection.claimAnnouncement(2, VISITOR, 0L, FIVE_MINUTES),
                "another city's towers saw them too, and that city has not been told");
    }

    @Test
    @DisplayName("a city only ever dims the players it lit up")
    void glowIsOwnedNotGuessed() {
        detection.startGlowing(1, VISITOR);
        assertTrue(detection.isGlowing(1, VISITOR));
        assertFalse(detection.isGlowing(1, OTHER),
                "a player glowing for some other reason must not be dimmed by a Keeper losing "
                        + "sight of somebody else");

        assertEquals(Set.of(VISITOR), detection.stopGlowing(1, Set.of()));
        assertFalse(detection.isGlowing(1, VISITOR));
    }

    @Test
    @DisplayName("a player still in radius keeps glowing")
    void stillVisibleStaysLit() {
        detection.startGlowing(1, VISITOR);
        detection.startGlowing(1, OTHER);

        assertEquals(Set.of(OTHER), detection.stopGlowing(1, Set.of(VISITOR)));
        assertTrue(detection.isGlowing(1, VISITOR));
    }

    @Test
    @DisplayName("a disbanded city forgets everything it had seen")
    void forgettingACity() {
        detection.startGlowing(1, VISITOR);
        detection.claimAnnouncement(1, VISITOR, 0L, FIVE_MINUTES);

        detection.forgetCity(1);

        assertFalse(detection.isGlowing(1, VISITOR));
        assertTrue(detection.claimAnnouncement(1, VISITOR, 1L, FIVE_MINUTES));
    }

    @Test
    @DisplayName("nothing else in the roster detects anything")
    void onlyTheKeeper() {
        for (DefenseUnitType type : catalogue.all()) {
            if (!type.key().equals("watchtower-keeper")) {
                assertFalse(type.hasAbility(Ability.GLOW_REFRESH_SECONDS),
                        type.key() + " is not a detection unit, and SPEC 25.1 retired the "
                                + "catalogue where five of eight units did two jobs");
            }
        }
    }
}
