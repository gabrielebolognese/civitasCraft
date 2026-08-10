package dev.civitas.core.abuse;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.economy.BountyService;
import dev.civitas.core.income.ActivityKind;
import dev.civitas.core.income.ActivityTracker;
import dev.civitas.core.income.DailyLoginService;
import dev.civitas.core.income.IncomeMultipliers;
import dev.civitas.storage.row.WarRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 21.4's Class F mitigations, the exploits internal to this plugin.
 *
 * <p>Part I built each of these systems with a rule that looked sufficient. Part II audited
 * them adversarially and found a way around every one. Two of the fixes here <b>supersede</b>
 * a rule the codebase previously stated on purpose — F7 overrides what M19 read into SPEC 4.7's
 * silence about self-placed bounties, and F6 overrides SPEC 17.1 case 10's even split — so
 * those two carry a test that names the older rule, to stop a later reader restoring it.
 *
 * <p>Each rule is asserted twice, in the shape M22's audit established: that it is
 * <b>enforced</b>, and that changing its config key <b>changes the behaviour</b>.
 */
class AntiAbuseTest {

    @TempDir
    Path directory;

    private CityTestSupport support;

    private static final long NOON = 1_754_000_000_000L;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    // ==================================================================================
    // F11, the stipend
    // ==================================================================================

    @Nested
    @DisplayName("F11: a macro cannot pass the activity check")
    class Stipend {

        private ActivityTracker tracker;
        private UUID player;

        @BeforeEach
        void setUp() {
            tracker = new ActivityTracker(support.configs);
            player = UUID.randomUUID();
        }

        @Test
        @DisplayName("three action types in one burst is not activity")
        void burstFails() {
            // The exploit. SPEC 4.2.1's "three distinct actions" was already enforced, and a
            // macro that fires three different keys in one second satisfied it.
            tracker.record(player, ActivityKind.BROKE_BLOCK, NOON);
            tracker.record(player, ActivityKind.PLACED_BLOCK, NOON + 100);
            tracker.record(player, ActivityKind.OPENED_INVENTORY, NOON + 200);

            assertEquals(3, tracker.distinctKinds(player), "three types, as SPEC 4.2.1 asks");
            assertEquals(1, tracker.distinctMinutes(player), "but all inside one minute");
            assertFalse(tracker.wasActive(player), "so it is a macro, not a player");
        }

        @Test
        @DisplayName("one action type repeated across the whole window is not activity either")
        void repetitionFails() {
            for (int minute = 0; minute < 15; minute++) {
                tracker.record(player, ActivityKind.BROKE_BLOCK, NOON + minute * 60_000L);
            }

            assertEquals(15, tracker.distinctMinutes(player), "plenty of minutes");
            assertEquals(1, tracker.distinctKinds(player), "but one thing, over and over");
            assertFalse(tracker.wasActive(player));
        }

        @Test
        @DisplayName("three types spread over three minutes is a player")
        void genuineActivityPasses() {
            tracker.record(player, ActivityKind.BROKE_BLOCK, NOON);
            tracker.record(player, ActivityKind.PLACED_BLOCK, NOON + 60_000);
            tracker.record(player, ActivityKind.OPENED_INVENTORY, NOON + 120_000);

            assertTrue(tracker.wasActive(player),
                    "a farmer breaking, placing and opening a chest must always qualify");
        }

        @Test
        @DisplayName("movement counts toward the minute as well as the type")
        void movementCountsForBothHalves() {
            tracker.recordMovement(player, 100.0, NOON);
            tracker.record(player, ActivityKind.BROKE_BLOCK, NOON + 60_000);
            tracker.record(player, ActivityKind.SPOKE, NOON + 120_000);

            assertTrue(tracker.wasActive(player));
        }

        @Test
        @DisplayName("the required minutes are configurable")
        void minutesAreConfigurable() {
            support.configs.get(ConfigFile.ECONOMY)
                    .set("anti-abuse.stipend-required-distinct-minutes", 1);

            tracker.record(player, ActivityKind.BROKE_BLOCK, NOON);
            tracker.record(player, ActivityKind.PLACED_BLOCK, NOON + 100);
            tracker.record(player, ActivityKind.OPENED_INVENTORY, NOON + 200);

            assertTrue(tracker.wasActive(player),
                    "an operator who wants SPEC 4.2.1's original rule can have it");
        }
    }

    // ==================================================================================
    // F12, the income gate
    // ==================================================================================

    @Nested
    @DisplayName("F12: alts farming daily login and quests")
    class IncomeGate {

        @Test
        @DisplayName("a new account earns nothing for its first hour of active playtime")
        void newAccountEarnsNothing() {
            // SPEC 21.4 F12 raises SPEC 17.6 case 70's thirty minutes to sixty.
            IncomeMultipliers multipliers = new IncomeMultipliers(support.configs);

            assertEquals(60 * 60_000L, multipliers.minimumPlaytimeMillis());
            assertFalse(multipliers.mayEarn(59 * 60_000L));
            assertTrue(multipliers.mayEarn(60 * 60_000L));
        }

        @Test
        @DisplayName("the hour is configurable")
        void gateIsConfigurable() {
            support.configs.get(ConfigFile.ECONOMY)
                    .set("anti-abuse.new-account-income-block-minutes", 10);
            IncomeMultipliers multipliers = new IncomeMultipliers(support.configs);

            assertTrue(multipliers.mayEarn(10 * 60_000L));
        }

        @Test
        @DisplayName("the daily reward needs active playtime on the day itself")
        void dailyRewardNeedsActivityToday() {
            // The case the lifetime gate lets through: an established alt logging in for one
            // second each morning has years of lifetime playtime and none today.
            DailyLoginService daily = dailyLogin();
            UUID veteran = support.givenPlayer("Veteranus", new BigDecimal("0.00"),
                    500 * 3_600_000L);

            Result<DailyLoginService.Claim> first = await(daily.claim(veteran, NOON));

            assertTrue(first instanceof Result.Failure, "logged in and did nothing");
            assertEquals("NOT_ACTIVE_TODAY", reasonOf(first));
        }

        @Test
        @DisplayName("and pays once that playtime has been earned")
        void paysAfterActivity() {
            DailyLoginService daily = dailyLogin();
            long lifetime = 500 * 3_600_000L;
            UUID veteran = support.givenPlayer("Veteranus", new BigDecimal("0.00"), lifetime);

            // The first attempt sets today's baseline, which is what makes "today" mean
            // anything at all.
            await(daily.claim(veteran, NOON));

            // Half an hour of active playtime later.
            addActivePlaytime(veteran, 30 * 60_000L);
            Result<DailyLoginService.Claim> second = await(daily.claim(veteran, NOON + 60_000));

            assertTrue(second.isSuccess(), reasonOf(second));
        }

        @Test
        @DisplayName("the required minutes are configurable")
        void activeTodayIsConfigurable() {
            support.configs.get(ConfigFile.ECONOMY)
                    .set("anti-abuse.daily-login-requires-active-minutes", 0);
            DailyLoginService daily = dailyLogin();
            UUID veteran = support.givenPlayer("Veteranus", new BigDecimal("0.00"),
                    500 * 3_600_000L);

            assertTrue(await(daily.claim(veteran, NOON)).isSuccess(),
                    "zero minutes required means the rule is off");
        }

        /** Credits active playtime the way the SPEC 4.2.1 filter would. */
        private void addActivePlaytime(UUID player, long millis) {
            var row = support.playerRow(player);
            await(support.daos.players().update(new dev.civitas.storage.row.PlayerRow(
                    row.uuid(), row.lastKnownName(), row.balance(), row.cityId(), row.rankId(),
                    row.firstJoin(), row.lastSeen(), row.totalPlaytimeMs() + millis,
                    row.activePlaytimeMs() + millis, row.dailyStreak(), row.lastDailyClaim(),
                    row.newcomerUntil(), row.frozen(), row.lastCityLeave(),
                    row.lastCityDisband())));
        }

        private DailyLoginService dailyLogin() {
            DailyLoginService daily = new DailyLoginService(support.db, support.daos.players(),
                    support.economy, new IncomeMultipliers(support.configs), support.configs,
                    ZoneId.systemDefault());
            daily.useDailyActivity(support.daos.dailyActivity());
            return daily;
        }
    }

    // ==================================================================================
    // F16, the withdrawal hold
    // ==================================================================================

    @Nested
    @DisplayName("F16: join, withdraw 25%, leave, repeat")
    class WithdrawalHold {

        private City city;
        private UUID mayor;

        @BeforeEach
        void setUp() {
            mayor = support.givenEligiblePlayer("Cincinnatus");
            city = support.givenCity(mayor, "Roma", 0, 0);
            await(support.treasury.deposit(mayor, city, new BigDecimal("40000.00")));
        }

        @Test
        @DisplayName("a member who joined moments ago cannot withdraw at all")
        void newMemberIsRefused() {
            UUID newcomer = support.givenMember(city, "Recens");
            grantWithdraw(newcomer);

            Result<BigDecimal> result = await(support.treasury.withdraw(
                    newcomer, live(),
                    new BigDecimal("100.00")));

            assertTrue(result instanceof Result.Failure, "a brand new member took money out");
            assertEquals("MEMBER_TOO_NEW", reasonOf(result));
        }

        @Test
        @DisplayName("the mayor is exempt, because the city is theirs")
        void mayorIsExempt() {
            Result<BigDecimal> result = await(support.treasury.withdraw(
                    mayor, live(),
                    new BigDecimal("100.00")));

            assertTrue(result.isSuccess(), reasonOf(result));
        }

        @Test
        @DisplayName("the hold is configurable, and zero switches it off")
        void holdIsConfigurable() {
            support.configs.get(ConfigFile.ECONOMY)
                    .set("anti-abuse.treasury-withdraw-member-age-hours", 0);
            UUID newcomer = support.givenMember(city, "Recens");
            grantWithdraw(newcomer);

            Result<BigDecimal> result = await(support.treasury.withdraw(
                    newcomer, live(),
                    new BigDecimal("100.00")));

            assertTrue(result.isSuccess(), reasonOf(result));
        }

        @Test
        @DisplayName("the 25% cap still applies once the hold has passed")
        void capSurvivesTheHold() {
            // F16 adds to the cap rather than replacing it. Losing the cap while adding the
            // hold would trade one exploit for the one SPEC 17.6 case 71 already closed.
            support.configs.get(ConfigFile.ECONOMY)
                    .set("anti-abuse.treasury-withdraw-member-age-hours", 0);
            UUID member = support.givenMember(city, "Civis");
            grantWithdraw(member);

            Result<BigDecimal> tooMuch = await(support.treasury.withdraw(
                    member, live(),
                    new BigDecimal("36000.00")));

            assertTrue(tooMuch instanceof Result.Failure, "took 90% of the treasury");
        }

        /** The city as the registry now holds it, since ranks and members move under us. */
        private City live() {
            return support.registry.city(city.id()).orElseThrow();
        }

        private void grantWithdraw(UUID member) {
            City current = live();
            var rank = current.rankOf(member).orElseThrow();
            await(support.ranks.setPermission(mayor, current, rank,
                    dev.civitas.core.city.CityPermission.WITHDRAW, true));
        }
    }

    // ==================================================================================
    // F7, bounties
    // ==================================================================================

    @Nested
    @DisplayName("F7: bounty an alt, kill it, reclaim")
    class BountySelfClaim {

        private BountyService bounties;

        @BeforeEach
        void setUp() {
            bounties = new BountyService(support.db, support.daos.bounties(), support.economy,
                    support.configs, Scheduler.direct(), java.util.logging.Logger.getAnonymousLogger());
            bounties.useLogins(support.daos.playerLogins());
        }

        @Test
        @DisplayName("a bounty you placed refunds instead of paying you")
        void selfPlacedRefunds() {
            // This supersedes M19's reading, which was recorded in OPEN_QUESTIONS: "A bounty
            // the killer placed themselves pays out normally. SPEC 4.7 names no exception."
            // SPEC 21.4 F7 names one.
            UUID hunter = support.givenPlayer("Venator", new BigDecimal("50000.00"), 0L);
            UUID alt = support.givenPlayer("Alter", new BigDecimal("0.00"), 0L);

            await(bounties.place(hunter, alt, new BigDecimal("5000.00"), NOON));
            BigDecimal afterPlacing = balance(hunter);

            Result<BigDecimal> claimed = await(bounties.claim(hunter, alt, true, NOON + 1000));

            assertEquals(0, balance(hunter).subtract(afterPlacing)
                            .compareTo(new BigDecimal("5000.00")),
                    "the stake came back, and only the stake");
            assertTrue(claimed instanceof Result.Failure || claimed.orElseThrow().signum() == 0,
                    "nothing was paid as a bounty");
        }

        @Test
        @DisplayName("a bounty on an account that connects from the same place refunds too")
        void ipLinkedRefunds() {
            // The second account F7 exists for. Blocking only the self-claim would teach
            // players to place bounties through an alt, which is what M19 predicted.
            UUID placer = support.givenPlayer("Tertius", new BigDecimal("50000.00"), 0L);
            UUID hunter = support.givenPlayer("Venator", new BigDecimal("0.00"), 0L);
            UUID alt = support.givenPlayer("Alter", new BigDecimal("0.00"), 0L);
            await(support.daos.playerLogins().upsert(hunter, "same-house", NOON));
            await(support.daos.playerLogins().upsert(alt, "same-house", NOON));

            await(bounties.place(placer, alt, new BigDecimal("5000.00"), NOON));
            BigDecimal before = balance(hunter);
            BigDecimal placerBefore = balance(placer);

            await(bounties.claim(hunter, alt, true, NOON + 1000));

            assertEquals(0, balance(hunter).compareTo(before),
                    "the killer was paid for killing their own alt");
            assertEquals(0, balance(placer).subtract(placerBefore)
                            .compareTo(new BigDecimal("5000.00")),
                    "and the placer got their stake back");
        }

        @Test
        @DisplayName("an ordinary bounty still pays")
        void strangerIsPaid() {
            // The rule must not break the feature it protects.
            UUID placer = support.givenPlayer("Tertius", new BigDecimal("50000.00"), 0L);
            UUID hunter = support.givenPlayer("Venator", new BigDecimal("0.00"), 0L);
            UUID target = support.givenPlayer("Hostis", new BigDecimal("0.00"), 0L);
            await(support.daos.playerLogins().upsert(hunter, "one-house", NOON));
            await(support.daos.playerLogins().upsert(target, "another-house", NOON));

            await(bounties.place(placer, target, new BigDecimal("5000.00"), NOON));
            Result<BigDecimal> claimed = await(bounties.claim(hunter, target, true, NOON + 1000));

            assertTrue(claimed.isSuccess(), reasonOf(claimed));
            assertEquals(0, balance(hunter).compareTo(new BigDecimal("5000.00")));
        }

        @Test
        @DisplayName("with no login table the self-claim rule still holds")
        void failsOpenOnlyForTheIpHalf() {
            // Failing open on an unreadable fingerprint is deliberate, and must not take the
            // self-claim block down with it.
            BountyService noLogins = new BountyService(support.db, support.daos.bounties(),
                    support.economy, support.configs, Scheduler.direct(),
                    java.util.logging.Logger.getAnonymousLogger());
            UUID hunter = support.givenPlayer("Venator", new BigDecimal("50000.00"), 0L);
            UUID alt = support.givenPlayer("Alter", new BigDecimal("0.00"), 0L);

            await(noLogins.place(hunter, alt, new BigDecimal("5000.00"), NOON));
            BigDecimal afterPlacing = balance(hunter);
            await(noLogins.claim(hunter, alt, true, NOON + 1000));

            assertEquals(0, balance(hunter).subtract(afterPlacing)
                            .compareTo(new BigDecimal("5000.00")),
                    "refunded, not paid");
        }

        private BigDecimal balance(UUID player) {
            return support.playerRow(player).balance();
        }
    }

    // ==================================================================================
    // F4, the war leaderboard
    // ==================================================================================

    @Nested
    @DisplayName("F4: wash-warring for the leaderboard")
    class WarLeaderboard {

        @Test
        @DisplayName("a walkover is recorded but does not rank")
        void walkoverDoesNotRank() {
            // "Two friendly cities alternate wins. Add: a war only counts toward the
            // leaderboard if the losing side scored at least 25% of the winner's score."
            int attacker = cityId("Roma", 0, 0);
            int defender = cityId("Carthago", 20, 20);
            resolvedWar(attacker, defender, 400, 0);

            assertTrue(await(support.daos.wars().findRecords(10, 25)).isEmpty(),
                    "a 400-0 war ranked, which is exactly the collusion F4 describes");
            assertFalse(await(support.daos.wars().findRecords(10, 0)).isEmpty(),
                    "and it is still recorded, which is what 'recorded but not ranked' means");
        }

        @Test
        @DisplayName("a war that was actually fought ranks")
        void contestedWarRanks() {
            int attacker = cityId("Roma", 0, 0);
            int defender = cityId("Carthago", 20, 20);
            resolvedWar(attacker, defender, 400, 150);

            assertFalse(await(support.daos.wars().findRecords(10, 25)).isEmpty(),
                    "the loser scored 37.5%, well over the threshold");
        }

        @Test
        @DisplayName("the threshold is exactly a quarter, not a rounding of one")
        void thresholdIsExact() {
            int attacker = cityId("Roma", 0, 0);
            int defender = cityId("Carthago", 20, 20);
            resolvedWar(attacker, defender, 400, 100);

            assertFalse(await(support.daos.wars().findRecords(10, 25)).isEmpty(),
                    "100 of 400 is exactly 25%, which SPEC words as 'at least'");
        }

        @Test
        @DisplayName("the threshold is configurable")
        void thresholdIsConfigurable() {
            int attacker = cityId("Roma", 0, 0);
            int defender = cityId("Carthago", 20, 20);
            resolvedWar(attacker, defender, 400, 40);

            assertTrue(await(support.daos.wars().findRecords(10, 25)).isEmpty());
            assertFalse(await(support.daos.wars().findRecords(10, 10)).isEmpty(),
                    "a server that wants every war ranked can lower the bar");
        }

        private int cityId(String name, int x, int z) {
            UUID founder = support.givenEligiblePlayer(name + "Founder");
            return support.givenCity(founder, name, x, z).id();
        }

        private void resolvedWar(int attacker, int defender, int attackerScore,
                                 int defenderScore) {
            int winner = attackerScore >= defenderScore ? attacker : defender;
            await(support.daos.wars().insert(new WarRow(0, attacker, defender, NOON,
                    NOON + 1000, NOON + 2000, "RESOLVED", attackerScore, defenderScore,
                    winner, new BigDecimal("50000.00"), NOON + 3000, null, 0)));
        }
    }

    // ==================================================================================
    // Every mechanism has a config key that is actually read
    // ==================================================================================

    @Nested
    @DisplayName("configurable, in the M22 shape")
    class Configurable {

        @Test
        @DisplayName("every SPEC 21.11 anti-abuse key ships with its documented default")
        void keysShipWithSpecDefaults() {
            var economy = support.configs.get(ConfigFile.ECONOMY);

            assertEquals(60, economy.getInt("anti-abuse.new-account-income-block-minutes"));
            assertEquals(30, economy.getInt("anti-abuse.daily-login-requires-active-minutes"));
            assertEquals(72, economy.getInt("anti-abuse.treasury-withdraw-member-age-hours"));
            assertEquals(3, economy.getInt("anti-abuse.stipend-required-distinct-minutes"));
            assertEquals(24, economy.getInt("anti-abuse.placed-block-cache-ttl-hours"));
            assertEquals(25,
                    economy.getInt("anti-abuse.war-leaderboard-min-loser-score-percent"));
        }

        @Test
        @DisplayName("SPEC 21.11's disband-treasury-split is not shipped as a switch")
        void disbandSplitIsNotASwitch() {
            // SPEC 21.11 lists disband-treasury-split: BY_CONTRIBUTION | EVEN. EVEN is the
            // exploit F6 exists to close, so shipping a key that restores it would be
            // shipping the vulnerability behind a setting — the same call the config sweep
            // made about the player-shop tax and the war-only bounty switch.
            assertFalse(support.configs.get(ConfigFile.ECONOMY)
                            .contains("anti-abuse.disband-treasury-split"),
                    "an operator must not be able to re-open F6 by editing a yml");
        }
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

}
