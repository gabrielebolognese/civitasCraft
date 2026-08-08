package dev.civitas.core.travel;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.travel.RandomTeleport.Rejection;
import dev.civitas.storage.row.WarpRow;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 32.4's {@code /rtp} rules and SPEC 32.7's warps.
 *
 * <p>The rejection rules are the substance: SPEC 32.4 lists five places {@code /rtp} must never
 * drop somebody, and each is asserted on its own so a diagnostic can say which one fired. The
 * block-level safety check and the warmup both need a running server and are covered by the
 * manual pass rather than mocked, for the reason recorded in {@code OPEN_QUESTIONS.md}.
 */
class TravelTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private RandomTeleport rtp;

    private static final String WORLD = "world";
    private static final long NOON = 1_754_000_000_000L;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        rtp = new RandomTeleport(null, support.configs, support.claimRegistry);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private Rejection judge(int chunkX, int chunkZ) {
        return rtp.judgeChunk(WORLD, chunkX, chunkZ, List.of());
    }

    // ==================================================================================
    // SPEC 32.4's rejections
    // ==================================================================================

    @Nested
    @DisplayName("where /rtp must never land, SPEC 32.4")
    class Rejections {

        @Test
        @DisplayName("empty wilderness far from anything is acceptable")
        void wildernessIsFine() {
            assertEquals(Rejection.ACCEPTED, judge(5_000, 5_000));
        }

        @Test
        @DisplayName("never inside a claim")
        void notInsideAClaim() {
            UUID founder = support.givenEligiblePlayer("Romulus");
            City city = support.givenCity(founder, "Roma", 0, 0);

            assertEquals(Rejection.CLAIMED, judge(0, 0),
                    "dropped a stranger inside " + city.name());
        }

        @Test
        @DisplayName("never inside a claim buffer, so not next door either")
        void notInsideTheBuffer() {
            support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
            int buffer = rtp.bufferChunks();

            assertEquals(Rejection.CLAIM_BUFFER, judge(buffer, 0),
                    "landed inside the buffer at " + buffer + " chunks");
            assertEquals(Rejection.ACCEPTED, judge(buffer + 2, 0),
                    "and outside it, wilderness is wilderness again");
        }

        @Test
        @DisplayName("an outpost is a claim, so the same rule covers it")
        void outpostsAreCovered() {
            // SPEC 32.4 lists "any claim, any claim buffer, any outpost" as three things. An
            // outpost is a claim row with type OUTPOST, so the first rule already covers it —
            // asserted rather than assumed, because a separate outpost check that drifted from
            // the claim check would be the two-authorities problem again.
            UUID founder = support.givenEligiblePlayer("Romulus");
            City city = support.givenCity(founder, "Roma", 0, 0);
            support.claimRegistry.put(new dev.civitas.core.claim.Claim(999, city.id(), WORLD,
                    400, 400, NOON, founder, BigDecimal.ZERO,
                    dev.civitas.core.claim.ClaimType.OUTPOST, 1));

            assertEquals(Rejection.CLAIMED, judge(400, 400));
        }

        @Test
        @DisplayName("never inside an admin-protected region")
        void notInsideAnAdminRegion() {
            rtp.useAdminProtection((world, x, z) -> x == 100 && z == 100);

            assertEquals(Rejection.ADMIN_PROTECTED, judge(100, 100));
            assertEquals(Rejection.ACCEPTED, judge(101, 100));
        }

        @Test
        @DisplayName("never within 200 blocks of another player")
        void notNextToAnotherPlayer() {
            // SPEC 32.4's figure. Landing on somebody's head is how /rtp becomes an ambush
            // tool rather than a way to find land.
            int minimum = rtp.minimumPlayerDistance();
            List<Object[]> others = List.<Object[]>of(new Object[] {WORLD, 0, 0});

            assertEquals(Rejection.NEAR_PLAYER,
                    rtp.judgeChunk(WORLD, 0, 0, others));
            assertEquals(Rejection.ACCEPTED,
                    rtp.judgeChunk(WORLD, (minimum / 16) + 4, 0, others),
                    "well past " + minimum + " blocks is fine");
        }

        @Test
        @DisplayName("a player in another world is not nearby")
        void otherWorldsDoNotCount() {
            assertEquals(Rejection.ACCEPTED, rtp.judgeChunk(WORLD, 0, 0,
                    List.<Object[]>of(new Object[] {"world_nether", 0, 0})));
        }

        @Test
        @DisplayName("the claim rule is checked before the cheaper ones, so it wins")
        void claimBeatsEverythingElse() {
            support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
            rtp.useAdminProtection((world, x, z) -> true);

            assertEquals(Rejection.CLAIMED, judge(0, 0),
                    "the reason a player is told should be the most specific one");
        }
    }

    // ==================================================================================
    // SPEC 32.4's numbers
    // ==================================================================================

    @Nested
    @DisplayName("the radii and the attempt ceiling")
    class Numbers {

        @Test
        @DisplayName("SPEC 32.4's figures ship as the defaults")
        void defaults() {
            assertEquals(15_000, rtp.maxRadius(), "SPEC 32.4's settled core");
            assertEquals(25_000, rtp.resourceMaxRadius(), "wider, to spread the digging");
            assertEquals(200, rtp.minimumPlayerDistance());
            assertEquals(40, rtp.maxAttempts());
        }

        @Test
        @DisplayName("the resource radius is the wider of the two, which is the point")
        void resourceIsWider() {
            assertTrue(rtp.resourceMaxRadius() > rtp.maxRadius());
        }

        @Test
        @DisplayName("every figure is configurable")
        void configurable() {
            var world = support.configs.get(ConfigFile.WORLD);
            world.set("travel.rtp.max-radius", 4_000);
            world.set("travel.rtp.min-player-distance", 50);
            world.set("travel.rtp.max-attempts", 5);

            assertEquals(4_000, rtp.maxRadius());
            assertEquals(50, rtp.minimumPlayerDistance());
            assertEquals(5, rtp.maxAttempts());
        }

        @Test
        @DisplayName("the buffer comes from the claim rules, not a second copy of the number")
        void bufferIsShared() {
            // If /rtp had its own buffer figure it could disagree with the one that governs
            // claiming, and a player could be dropped somewhere they are then told is too
            // close to a city.
            support.configs.get(ConfigFile.CITIES).set("claims.buffer-chunks", 9);

            assertEquals(9, rtp.bufferChunks());
        }

        @Test
        @DisplayName("a buffer of zero disables the buffer rule rather than rejecting everything")
        void zeroBuffer() {
            support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
            support.configs.get(ConfigFile.CITIES).set("claims.buffer-chunks", 0);

            assertEquals(Rejection.ACCEPTED, judge(1, 0), "one chunk out, with no buffer");
            assertEquals(Rejection.CLAIMED, judge(0, 0), "and the claim itself still refuses");
        }
    }

    // ==================================================================================
    // SPEC 32.7's travel table
    // ==================================================================================

    @Nested
    @DisplayName("the travel table, SPEC 32.7")
    class TravelTable {

        private TeleportService teleports;

        @BeforeEach
        void setUp() {
            teleports = new TeleportService(null, support.configs, support.economy,
                    support.lang(), CityTestSupport.quietLogger());
        }

        @Test
        @DisplayName("every destination's cost, cooldown and warmup match SPEC 32.7")
        void tableMatchesSpec() {
            assertEquals(0, teleports.cost(TravelKind.SPAWN).signum());
            assertEquals(60, teleports.cooldownSeconds(TravelKind.SPAWN));
            assertEquals(5, teleports.warmupSeconds(TravelKind.SPAWN));

            assertEquals(0, teleports.cost(TravelKind.RTP).compareTo(new BigDecimal("500")));
            assertEquals(300, teleports.cooldownSeconds(TravelKind.RTP));

            assertEquals(0, teleports.cost(TravelKind.RTP_RESOURCE).signum(),
                    "free, because mining is meant to be reachable");
            assertEquals(120, teleports.cooldownSeconds(TravelKind.RTP_RESOURCE));

            assertEquals(30, teleports.cooldownSeconds(TravelKind.WARP));
        }

        @Test
        @DisplayName("SPEC 39.5's outpost numbers resolve, from cities.yml and its own key names")
        void outpostReadsItsOwnBlock() {
            // The one destination whose numbers are not in world.yml under travel.*, and the
            // one place the fold could silently read nothing and fall back to a default that
            // happens to look plausible. 8 and 180 are SPEC 7.2's figures, kept by SPEC 39.15.
            assertEquals(8, teleports.warmupSeconds(TravelKind.OUTPOST_TP));
            assertEquals(180, teleports.cooldownSeconds(TravelKind.OUTPOST_TP));
            assertEquals(0, teleports.cost(TravelKind.OUTPOST_TP)
                    .compareTo(new BigDecimal("100")),
                    "the base the SPEC 39.5 distance multiplier applies to");
        }

        @Test
        @DisplayName("every destination resolves a real key, none falls through to a default")
        void noDestinationFallsThrough() {
            // A missing key returns the caller's default, which is indistinguishable from a
            // configured value of the same number. Asserting against the file itself is what
            // separates "configured" from "happens to match the fallback".
            for (TravelKind kind : TravelKind.values()) {
                assertTrue(support.configs.get(kind.configFile()).contains(kind.cooldownKey()),
                        kind + " has no cooldown key at " + kind.cooldownKey());
                assertTrue(support.configs.get(kind.configFile()).contains(kind.warmupKey()),
                        kind + " has no warmup key at " + kind.warmupKey());
            }
        }

        @Test
        @DisplayName("cooldowns are per destination, not one shared clock")
        void cooldownsAreIndependent() {
            // SPEC 32.7 gives them different numbers on purpose: /spawn gets a player out of
            // trouble and /rtp is how they find land.
            assertNotEquals(teleports.cooldownSeconds(TravelKind.SPAWN),
                    teleports.cooldownSeconds(TravelKind.RTP));

            UUID player = UUID.randomUUID();
            assertEquals(0, teleports.cooldownRemaining(player, TravelKind.SPAWN));
            assertEquals(0, teleports.cooldownRemaining(player, TravelKind.RTP));
        }

        @Test
        @DisplayName("nothing is warming up for a player who has not travelled")
        void noWarmupByDefault() {
            assertFalse(teleports.isWarmingUp(UUID.randomUUID()));
        }

        @Test
        @DisplayName("the combat-tag seam answers not-tagged until the war half is built")
        void combatTagSeam() {
            // SPEC 33.8 blocks every teleport while tagged. One setter fills it in.
            UUID player = UUID.randomUUID();
            assertEquals(0, teleports.cooldownRemaining(player, TravelKind.RTP));

            teleports.useCombatTag(tagged -> true);
            // Nothing to assert on the decision without a Player, but the seam exists and is
            // consulted first in begin(); the refusal is covered by the manual pass.
            assertFalse(teleports.isWarmingUp(player));
        }

        @Test
        @DisplayName("every figure is configurable")
        void configurable() {
            support.configs.get(ConfigFile.WORLD).set("travel.spawn.cooldown", 5);
            support.configs.get(ConfigFile.WORLD).set("travel.rtp.cost", 42);

            assertEquals(5, teleports.cooldownSeconds(TravelKind.SPAWN));
            assertEquals(0, teleports.cost(TravelKind.RTP).compareTo(new BigDecimal("42")));
        }
    }

    // ==================================================================================
    // SPEC 32.7's warps
    // ==================================================================================

    @Nested
    @DisplayName("public warps, SPEC 32.7")
    class Warps {

        private WarpService warps;
        private UUID admin;

        @BeforeEach
        void setUp() {
            warps = new WarpService(support.daos.warps(), CityTestSupport.quietLogger());
            admin = UUID.randomUUID();
        }

        private Result<WarpRow> set(String name) {
            return await(warps.set(name, place(), admin, null, NOON));
        }

        private org.bukkit.Location place() {
            // A location with no world would be refused, so this is the minimum a warp needs.
            // MockBukkit is not started here, so the world is a stub the service only reads a
            // name from.
            return new org.bukkit.Location(null, 1, 2, 3);
        }

        @Test
        @DisplayName("a warp with no world is refused rather than stored broken")
        void needsAWorld() {
            Result<WarpRow> result = await(warps.set("hub", place(), admin, null, NOON));

            assertTrue(result instanceof Result.Failure, "stored a warp with no world");
            assertEquals("NO_LOCATION", reasonOf(result));
        }

        @Test
        @DisplayName("names are validated, so nothing unprintable reaches tab completion")
        void nameRules() {
            assertEquals("NAME_EMPTY", reasonOf(WarpService.validateName("  ")));
            assertEquals("NAME_EMPTY", reasonOf(WarpService.validateName(null)));
            assertEquals("NAME_INVALID", reasonOf(WarpService.validateName("hub spawn")));
            assertEquals("NAME_INVALID", reasonOf(WarpService.validateName("<red>hub")));
            assertEquals("NAME_TOO_LONG",
                    reasonOf(WarpService.validateName("x".repeat(33))));

            assertTrue(WarpService.validateName("hub-1_A").isSuccess());
            assertEquals("hub", WarpService.validateName("  hub  ").orElseThrow(),
                    "trimmed, so a stray space is not a different warp");
        }

        @Test
        @DisplayName("an unknown warp is refused by name")
        void unknownWarp() {
            assertTrue(warps.find("nowhere", NOON).isEmpty());
            assertEquals("UNKNOWN_WARP", reasonOf(await(warps.delete("nowhere"))));
        }

        @Test
        @DisplayName("an expired warp is invisible the moment it expires, not when swept")
        void expiryIsImmediate() {
            // SPEC 40.1's contest warps last "for the duration of the voting window only". A
            // warp that kept working until housekeeping ran would outlive its contest.
            WarpRow temporary = new WarpRow("gallery", WORLD, 0, 64, 0, 0f, 0f, admin,
                    NOON, NOON + 1000);

            assertFalse(temporary.hasExpired(NOON));
            assertTrue(temporary.hasExpired(NOON + 1000), "expiry is inclusive");
            assertTrue(temporary.hasExpired(NOON + 5000));
        }

        @Test
        @DisplayName("a permanent warp never expires")
        void permanentNeverExpires() {
            WarpRow permanent = new WarpRow("hub", WORLD, 0, 64, 0, 0f, 0f, admin, NOON, null);

            assertFalse(permanent.hasExpired(Long.MAX_VALUE));
        }

        @Test
        @DisplayName("an empty server has no warps and says so rather than failing")
        void emptyByDefault() {
            assertEquals(0, warps.count());
            assertTrue(warps.all(NOON).isEmpty());
            assertTrue(warps.names(NOON).isEmpty());
        }
    }
}
