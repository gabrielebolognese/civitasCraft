package dev.civitas.core.income;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.storage.row.CityChallengeRow;
import dev.civitas.storage.row.PlayerQuestRow;
import dev.civitas.storage.row.PlayerRow;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SPEC 4.2 and SPEC 13 income sources over a real database, and the SPEC 17.6 cases 69
 * and 70 they exist to defend.
 */
class IncomeSystemsTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    /**
     * Enough active playtime to clear the SPEC 17.6 case 70 income floor.
     *
     * <p>Was half an hour until SPEC 21.4 F12 raised the floor to sixty minutes. Named for
     * what it is for rather than for its value, so the next change to the floor moves one
     * number instead of nine call sites that all meant "past the floor".
     */
    private static final long PAST_THE_FLOOR = TimeUnit.MINUTES.toMillis(60);

    @TempDir
    Path directory;

    private CityTestSupport support;
    private ActivityTracker activity;
    private IncomeMultipliers multipliers;
    private QuestPool questPool;
    private QuestService quests;
    private ChallengeService challenges;
    private DailyLoginService dailyLogin;
    private StipendTask stipend;

    private UUID player;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        activity = new ActivityTracker(support.configs);
        multipliers = new IncomeMultipliers(support.configs);

        questPool = new QuestPool(support.configs, CityTestSupport.quietLogger());
        questPool.load("income.quests.pool");
        QuestPool challengePool = new QuestPool(support.configs, CityTestSupport.quietLogger());
        challengePool.load("income.challenges.pool");

        quests = new QuestService(support.db, support.daos.playerQuests(), support.daos.players(),
                support.economy, questPool, multipliers, support.configs,
                (uuid, key, extra) -> { }, UTC);
        challenges = new ChallengeService(support.db, support.daos.cityChallenges(),
                support.registry, support.treasury, challengePool, support.configs,
                (uuid, key, extra) -> { }, UTC);
        dailyLogin = new DailyLoginService(support.db, support.daos.players(), support.economy,
                multipliers, support.configs, UTC);

        player = support.givenPlayer("Farmer", new BigDecimal("1000.00"), 0L);
        stipend = new StipendTask(support.db, support.daos.players(), support.daos.ledger(),
                support.economy, activity, multipliers, support.configs,
                () -> List.of(player), (uuid, key, extra) -> { },
                CityTestSupport.quietLogger());
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private PlayerRow row() {
        return support.playerRow(player);
    }

    private BigDecimal wallet() {
        return row().balance();
    }

    private void givePlaytime(long millis) {
        PlayerRow current = row();
        await(support.daos.players().update(new PlayerRow(current.uuid(),
                current.lastKnownName(), current.balance(), current.cityId(), current.rankId(),
                current.firstJoin(), current.lastSeen(), current.totalPlaytimeMs(), millis,
                current.dailyStreak(), current.lastDailyClaim(), current.newcomerUntil(),
                current.frozen(), current.lastCityLeave(), current.lastCityDisband())));
    }

    /**
     * Puts the fixture player inside the SPEC 15.1 newcomer window.
     *
     * <p>The shared fixture inserts {@code newcomer_until = 0}, so a test that wants the
     * multiplier has to say so; the plugin sets it on first join.
     */
    private void beNewcomer() {
        PlayerRow current = row();
        await(support.daos.players().update(new PlayerRow(current.uuid(),
                current.lastKnownName(), current.balance(), current.cityId(), current.rankId(),
                current.firstJoin(), current.lastSeen(), current.totalPlaytimeMs(),
                current.activePlaytimeMs(), current.dailyStreak(), current.lastDailyClaim(),
                System.currentTimeMillis() + TimeUnit.DAYS.toMillis(14), current.frozen(),
                current.lastCityLeave(), current.lastCityDisband())));
    }

    private void endNewcomerWindow() {
        PlayerRow current = row();
        await(support.daos.players().update(new PlayerRow(current.uuid(),
                current.lastKnownName(), current.balance(), current.cityId(), current.rankId(),
                current.firstJoin(), current.lastSeen(), current.totalPlaytimeMs(),
                current.activePlaytimeMs(), current.dailyStreak(), current.lastDailyClaim(),
                0L, current.frozen(), current.lastCityLeave(), current.lastCityDisband())));
    }

    /**
     * What a genuinely active player looks like to the tracker.
     *
     * <p>Spread over three minutes, because SPEC 21.4 F11 requires that as well as three
     * distinct kinds: "a single repeating macro produces one action type and fails", and a
     * burst in one second is the other shape a macro takes. A real farmer does both, so this
     * helper does both.
     */
    private void beActive() {
        long start = System.currentTimeMillis();
        activity.record(player, ActivityKind.BROKE_BLOCK, start);
        activity.record(player, ActivityKind.PLACED_BLOCK, start + 60_000);
        activity.record(player, ActivityKind.SPOKE, start + 120_000);
    }

    // ==================================================================================
    // The stipend, SPEC 4.2
    // ==================================================================================

    @Nested
    @DisplayName("The playtime stipend")
    class Stipend {

        @Test
        @DisplayName("an active interval pays, and credits filtered active playtime")
        void activeIntervalPays() {
            givePlaytime(PAST_THE_FLOOR);
            endNewcomerWindow();
            BigDecimal before = wallet();
            beActive();

            assertTrue(stipend.settle(player, System.currentTimeMillis()));

            assertEquals(0, before.add(new BigDecimal("40")).compareTo(wallet()));
            assertEquals(PAST_THE_FLOOR + TimeUnit.MINUTES.toMillis(15), row().activePlaytimeMs(),
                    "one qualifying interval of active playtime");
        }

        @Test
        @DisplayName("an idle interval pays nothing and credits no playtime either")
        void idleIntervalPaysNothing() {
            givePlaytime(PAST_THE_FLOOR);
            BigDecimal before = wallet();
            activity.record(player, ActivityKind.BROKE_BLOCK);

            assertFalse(stipend.settle(player, System.currentTimeMillis()));

            assertEquals(0, before.compareTo(wallet()));
            assertEquals(PAST_THE_FLOOR, row().activePlaytimeMs(),
                    "an AFK machine must not accumulate the playtime the city gate reads");
        }

        @Test
        @DisplayName("SPEC 4.2: the daily cap holds, and is read from the ledger")
        void dailyCap() {
            givePlaytime(PAST_THE_FLOOR);
            endNewcomerWindow();

            // Sixteen intervals of 40 C is 640 C, which is the cap exactly.
            for (int interval = 0; interval < 20; interval++) {
                beActive();
                stipend.settle(player, System.currentTimeMillis());
            }

            BigDecimal earned = await(support.daos.ledger().sumByActorAndType(player,
                    TransactionType.PLAYTIME_STIPEND.name(), 0L));
            assertEquals(0, new BigDecimal("640.00").compareTo(earned),
                    "sixteen intervals paid, the rest refused");
            assertEquals(0, BigDecimal.ZERO.compareTo(
                    stipend.remainingToday(player, System.currentTimeMillis())));
        }

        @Test
        @DisplayName("SPEC 15.1: a newcomer is paid one and a half times as much")
        void newcomerMultiplier() {
            givePlaytime(PAST_THE_FLOOR);
            beNewcomer();
            BigDecimal before = wallet();
            beActive();

            stipend.settle(player, System.currentTimeMillis());

            assertEquals(0, before.add(new BigDecimal("60")).compareTo(wallet()),
                    "40 C times the 1.5 newcomer multiplier");
        }
    }

    // ==================================================================================
    // SPEC 17.6 cases 69 and 70
    // ==================================================================================

    @Nested
    @DisplayName("Alt accounts")
    class Alts {

        @Test
        @DisplayName("case 70: a brand-new account earns nothing at all")
        void freshAccountEarnsNothing() {
            // Zero playtime: below the thirty-minute floor.
            BigDecimal before = wallet();
            beActive();

            assertFalse(stipend.settle(player, System.currentTimeMillis()));
            assertEquals(0, before.compareTo(wallet()));
        }

        @Test
        @DisplayName("case 70: playtime still accrues, so the floor is reachable by playing")
        void playtimeStillAccrues() {
            beActive();
            stipend.settle(player, System.currentTimeMillis());

            assertEquals(TimeUnit.MINUTES.toMillis(15), row().activePlaytimeMs(),
                    "the floor is a wait, not a wall");
        }

        @Test
        @DisplayName("case 70: the daily login is refused to an account that has not played")
        void dailyLoginRefusedToFreshAlt() {
            Result<DailyLoginService.Claim> result =
                    await(dailyLogin.claim(player, System.currentTimeMillis()));

            assertEquals("TOO_NEW", reasonOf(result));
            assertEquals(0, new BigDecimal("1000.00").compareTo(wallet()),
                    "and no money moved");
            assertEquals(0, row().dailyStreak(),
                    "nor did a streak start, so parking alts builds nothing");
        }

        @Test
        @DisplayName("case 69: active playtime is the filtered figure the member divisor reads")
        void activePlaytimeIsFiltered() {
            // Two hours of sitting still.
            for (int interval = 0; interval < 8; interval++) {
                activity.recordMovement(player, 0.001);
                stipend.settle(player, System.currentTimeMillis());
            }

            assertEquals(0L, row().activePlaytimeMs(),
                    "an alt left logged in never becomes an active member");
        }
    }

    // ==================================================================================
    // Daily login, SPEC 4.2
    // ==================================================================================

    @Nested
    @DisplayName("Daily login")
    class Daily {

        @Test
        @DisplayName("SPEC 4.2: 250 C, then +125 a day, to a ceiling of 1,000 C")
        void streakRewards() {
            assertEquals(0, new BigDecimal("250").compareTo(dailyLogin.rewardFor(1)));
            assertEquals(0, new BigDecimal("375").compareTo(dailyLogin.rewardFor(2)));
            assertEquals(0, new BigDecimal("1000").compareTo(dailyLogin.rewardFor(7)));
            assertEquals(0, new BigDecimal("1000").compareTo(dailyLogin.rewardFor(50)),
                    "the ceiling holds");
        }

        @Test
        @DisplayName("claiming pays, starts the streak, and cannot be repeated the same day")
        void claimOncePerDay() {
            givePlaytime(PAST_THE_FLOOR);
            endNewcomerWindow();
            long now = System.currentTimeMillis();

            Result<DailyLoginService.Claim> first = await(dailyLogin.claim(player, now));
            assertTrue(first.isSuccess(), reasonOf(first));
            assertEquals(1, first.orElseThrow().streak());
            assertEquals(0, new BigDecimal("1250.00").compareTo(wallet()));

            assertEquals("ALREADY_CLAIMED", reasonOf(await(dailyLogin.claim(player, now + 1000))));
            assertEquals(0, new BigDecimal("1250.00").compareTo(wallet()));
        }

        @Test
        @DisplayName("SPEC 4.2: the streak survives a normal night and dies after 48 hours")
        void streakWindow() {
            PlayerRow base = row();
            long now = System.currentTimeMillis();

            PlayerRow onDayOne = withClaim(base, 3, now - TimeUnit.HOURS.toMillis(20));
            assertEquals(4, dailyLogin.nextStreak(onDayOne, now), "twenty hours away continues it");

            PlayerRow longGone = withClaim(base, 3, now - TimeUnit.HOURS.toMillis(60));
            assertEquals(1, dailyLogin.nextStreak(longGone, now), "sixty hours away breaks it");
        }

        private PlayerRow withClaim(PlayerRow row, int streak, long lastClaim) {
            return new PlayerRow(row.uuid(), row.lastKnownName(), row.balance(), row.cityId(),
                    row.rankId(), row.firstJoin(), row.lastSeen(), row.totalPlaytimeMs(),
                    row.activePlaytimeMs(), streak, lastClaim, row.newcomerUntil(), row.frozen(),
                    row.lastCityLeave(), row.lastCityDisband());
        }
    }

    // ==================================================================================
    // Quests, SPEC 13.1
    // ==================================================================================

    @Nested
    @DisplayName("Daily quests")
    class Quests {

        @Test
        @DisplayName("SPEC 13.1: three a day, and the same three all day")
        void threeADay() {
            long now = System.currentTimeMillis();

            List<PlayerQuestRow> first = await(quests.todaysQuests(player, now));
            assertEquals(3, first.size());

            List<PlayerQuestRow> again = await(quests.todaysQuests(player, now + 60_000));
            // Sorted, because the three share an assignment timestamp and the row order is
            // the database's business; what matters is that it is the same three.
            assertEquals(first.stream().map(PlayerQuestRow::questId).sorted().toList(),
                    again.stream().map(PlayerQuestRow::questId).sorted().toList(),
                    "a relog must not reroll a quest somebody dislikes");
        }

        @Test
        @DisplayName("the three are distinct")
        void distinct() {
            List<PlayerQuestRow> today =
                    await(quests.todaysQuests(player, System.currentTimeMillis()));

            assertEquals(3, today.stream().map(PlayerQuestRow::questId).distinct().count());
        }

        @Test
        @DisplayName("the draw is seeded per player, so players do not all get the same quests")
        void drawIsPerPlayer() {
            // Was "a different day draws different quests" and compared two PLAYERS on one day,
            // asserting their draws differed. That is flaky by construction: SPEC 13.1 draws
            // three quests from a pool of eight, so two players legitimately collide a few
            // percent of the time, and this test failed a build for doing exactly that.
            //
            // The property that is actually true is that the draw is seeded per player rather
            // than per server. Across a population that shows up as more than one distinct
            // draw, which no collision can make false.
            long today = System.currentTimeMillis();
            java.util.Set<List<String>> draws = new java.util.LinkedHashSet<>();

            draws.add(await(quests.todaysQuests(player, today)).stream()
                    .map(PlayerQuestRow::questId).sorted().toList());
            for (int i = 0; i < 12; i++) {
                UUID other = support.givenPlayer("Miner" + i, new BigDecimal("100.00"), 0L);
                draws.add(await(quests.todaysQuests(other, today)).stream()
                        .map(PlayerQuestRow::questId).sorted().toList());
            }

            assertTrue(draws.size() > 1,
                    "thirteen players all drew the same three quests, so the draw is not "
                            + "seeded per player: " + draws);
        }

        @Test
        @DisplayName("the same player on the same day draws the same quests")
        void drawIsStable() {
            // The other half, and the one that makes the seeding meaningful: asking twice must
            // not reroll, or a player could refresh until they liked their quests.
            long today = System.currentTimeMillis();

            assertEquals(
                    await(quests.todaysQuests(player, today)).stream()
                            .map(PlayerQuestRow::questId).sorted().toList(),
                    await(quests.todaysQuests(player, today)).stream()
                            .map(PlayerQuestRow::questId).sorted().toList());
        }

        @Test
        @DisplayName("progress accumulates and completion pays once")
        void progressAndPayout() {
            givePlaytime(PAST_THE_FLOOR);
            endNewcomerWindow();
            long now = System.currentTimeMillis();

            List<PlayerQuestRow> today = await(quests.todaysQuests(player, now));
            PlayerQuestRow quest = today.get(0);
            QuestDefinition definition = questPool.byId(quest.questId()).orElseThrow();

            BigDecimal before = wallet();
            quests.report(player, definition.metric(), quest.target());

            eventually(() -> wallet().compareTo(before) > 0, "the reward to arrive");

            BigDecimal paid = await(support.daos.ledger().sumByActorAndType(player,
                    TransactionType.QUEST_REWARD.name(), 0L));
            assertEquals(0, quest.reward().compareTo(paid));

            // Reporting more must not pay again.
            quests.report(player, definition.metric(), quest.target());
            assertEquals(0, paid.compareTo(await(support.daos.ledger().sumByActorAndType(player,
                    TransactionType.QUEST_REWARD.name(), 0L))));
        }

        @Test
        @DisplayName("SPEC 13.1: target and reward scale together, so the ratio stays flat")
        void scaleIsFlat() {
            QuestDefinition definition = questPool.all().get(0);

            long noviceTarget = questPool.targetFor(definition, 0L);
            BigDecimal noviceReward = questPool.rewardFor(definition, 0L, 42L);

            long veteranHours = TimeUnit.HOURS.toMillis(100);
            long veteranTarget = questPool.targetFor(definition, veteranHours);
            BigDecimal veteranReward = questPool.rewardFor(definition, veteranHours, 42L);

            assertTrue(veteranTarget > noviceTarget, "a veteran's quest is harder");
            assertTrue(veteranReward.compareTo(noviceReward) > 0, "and pays more");

            double noviceRatio = noviceReward.doubleValue() / noviceTarget;
            double veteranRatio = veteranReward.doubleValue() / veteranTarget;
            assertEquals(noviceRatio, veteranRatio, noviceRatio * 0.02,
                    "the effort-to-reward ratio is flat, SPEC 13.1");
        }

        @Test
        @DisplayName("SPEC 13.1: rewards never scale with wealth")
        void rewardIgnoresWealth() {
            QuestDefinition definition = questPool.all().get(0);
            BigDecimal poor = questPool.rewardFor(definition, PAST_THE_FLOOR, 7L);

            await(support.economy.give(player, new BigDecimal("100000000"),
                    TransactionType.ADMIN_GIVE, null, null));

            assertEquals(0, poor.compareTo(questPool.rewardFor(definition, PAST_THE_FLOOR, 7L)),
                    "nothing in the reward path reads a balance");
        }
    }

    // ==================================================================================
    // Challenges, SPEC 13.2
    // ==================================================================================

    @Nested
    @DisplayName("Weekly challenges")
    class Challenges {

        private City city;

        @Test
        @DisplayName("SPEC 13.2: two a week, pooled, paid to the treasury")
        void pooledAndPaidToTreasury() {
            UUID mayor = support.givenEligiblePlayer("Romulus");
            city = support.givenCity(mayor, "Roma", 0, 0);
            long now = System.currentTimeMillis();

            List<CityChallengeRow> week = await(challenges.thisWeek(city.id(), now));
            assertEquals(2, week.size());

            CityChallengeRow challenge = week.get(0);
            QuestDefinition definition =
                    challenges.pool().byId(challenge.challengeId()).orElseThrow();

            BigDecimal treasuryBefore = city.treasury();
            challenges.report(mayor, definition.metric(), challenge.target());

            eventually(() -> await(support.daos.cities().findById(city.id())).orElseThrow()
                            .treasury().compareTo(treasuryBefore) > 0,
                    "the treasury to be paid");

            BigDecimal after = await(support.daos.cities().findById(city.id()))
                    .orElseThrow().treasury();
            assertEquals(0, treasuryBefore.add(challenge.reward()).compareTo(after));
        }

        @Test
        @DisplayName("the same week returns the same two, never a fresh pair")
        void idempotentPerWeek() {
            UUID mayor = support.givenEligiblePlayer("Numa");
            city = support.givenCity(mayor, "Alba", 20, 20);
            long now = System.currentTimeMillis();

            List<String> first = await(challenges.thisWeek(city.id(), now)).stream()
                    .map(CityChallengeRow::challengeId).toList();
            List<String> again = await(challenges.thisWeek(city.id(), now + 60_000)).stream()
                    .map(CityChallengeRow::challengeId).toList();

            assertEquals(first, again);
        }

        @Test
        @DisplayName("SPEC 13.2: the week starts on Monday")
        void weekStartsMonday() {
            long wednesday = java.time.ZonedDateTime.of(2026, 8, 5, 14, 30, 0, 0, UTC)
                    .toInstant().toEpochMilli();

            java.time.ZonedDateTime start = java.time.Instant
                    .ofEpochMilli(challenges.startOfWeek(wednesday)).atZone(UTC);

            assertEquals(java.time.DayOfWeek.MONDAY, start.getDayOfWeek());
            assertEquals(0, start.getHour());
            assertEquals(3, start.getDayOfMonth());
        }
    }

    // ==================================================================================
    // Multipliers
    // ==================================================================================

    @Test
    @DisplayName("the newcomer window and the alt floor are both config keys")
    void multipliersAreConfigurable() {
        beNewcomer();
        PlayerRow fresh = row();

        assertTrue(multipliers.isNewcomer(fresh, System.currentTimeMillis()));
        assertEquals(0, new BigDecimal("1.5")
                .compareTo(multipliers.multiplierFor(fresh, System.currentTimeMillis())));
        // SPEC 21.4 F12 raised SPEC 17.6 case 70's thirty minutes to sixty.
        assertEquals(TimeUnit.MINUTES.toMillis(60), multipliers.minimumPlaytimeMillis());

        support.configs.get(ConfigFile.ECONOMY).set("income.newcomer.multiplier", 2.0);
        assertEquals(0, new BigDecimal("2.0")
                .compareTo(multipliers.multiplierFor(fresh, System.currentTimeMillis())));
    }

    private static void eventually(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        org.junit.jupiter.api.Assertions.fail("timed out waiting for: " + what);
    }
}
