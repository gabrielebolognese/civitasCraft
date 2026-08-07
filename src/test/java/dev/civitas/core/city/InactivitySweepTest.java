package dev.civitas.core.city;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.config.ConfigFile;
import dev.civitas.storage.row.PlayerNoticeRow;
import dev.civitas.storage.row.PlayerRow;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 17.1 cases 1, 2 and 3.
 *
 * <p>These three were specified in M0's SPEC, deferred by M2 to M4, deferred again by M4 to
 * "a later milestone", and then never built. What made that survivable for so long is also
 * what made it invisible: {@code cities.yml} has always shipped the {@code inactivity:} block
 * with all four of SPEC's numbers in it, so the feature looked present to anyone reading the
 * configuration. Nothing read a single key.
 *
 * <p>So each rule here is asserted twice, in the shape M22's audit established: that it
 * <b>happens</b>, and that changing its config key <b>changes the behaviour</b>. The second is
 * the assertion that would have failed on the day this was still a block of unread numbers.
 */
class InactivitySweepTest {

    @TempDir
    Path directory;

    private static final long DAY = 86_400_000L;

    /** Fixed, and read at construction rather than at class load: see the note in setUp. */
    private long now;

    private CityTestSupport support;
    private DormancyCache dormancy;
    private InactivityTask task;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        // Captured per test rather than in a static. A NOW fixed at class load makes a city
        // founded in @BeforeEach younger than zero, which cost time at both M19 and M22.
        now = System.currentTimeMillis();
        dormancy = new DormancyCache();
        task = new InactivityTask(support.daos, support.registry, support.cities, dormancy,
                support.configs, CityTestSupport.quietLogger());
        support.protection.useDormancy(dormancy);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private ConfigurationSection inactivity() {
        return support.configs.get(ConfigFile.CITIES).getConfigurationSection("inactivity");
    }

    /** Backdates a player's last login, which is the only input all three rules read. */
    private void lastSeen(UUID uuid, long daysAgo) {
        PlayerRow row = await(support.daos.players().findByUuid(uuid)).orElseThrow();
        await(support.daos.players().update(new PlayerRow(row.uuid(), row.lastKnownName(),
                row.balance(), row.cityId(), row.rankId(), row.firstJoin(),
                now - daysAgo * DAY, row.totalPlaytimeMs(), row.activePlaytimeMs(),
                row.dailyStreak(), row.lastDailyClaim(), row.newcomerUntil(), row.frozen(),
                row.lastCityLeave(), row.lastCityDisband())));
    }

    /**
     * Whether a stranger may build in a chunk, through the real protection path.
     *
     * <p>Asserted through {@code ProtectionService.check} rather than the cache directly: a
     * dormancy flag nothing consults protects nobody, and that seam sat returning false from
     * M4 until this change.
     */
    private boolean strangerMayBuild(UUID stranger, int chunkX, int chunkZ) {
        return !support.protection.check(stranger, false, "world", chunkX, chunkZ,
                dev.civitas.core.protection.ProtectionAction.BUILD).denied();
    }

    /** A city with a mayor and one other member, both last seen just now. */
    private City givenCity(String name, int chunkX) {
        UUID mayor = support.givenEligiblePlayer(name + "Mayor");
        City city = support.givenCity(mayor, name, chunkX, 0);
        return city;
    }

    // ==================================================================================
    // Case 1: the absent mayor
    // ==================================================================================

    @Nested
    @DisplayName("case 1, an absent mayor is replaced")
    class AbsentMayor {

        @Test
        @DisplayName("after 30 days the city gets a new mayor")
        void replaced() {
            City city = givenCity("Roma", 0);
            UUID oldMayor = city.mayorUuid();
            UUID heir = support.givenMember(city, "Heir");
            lastSeen(oldMayor, 40);

            assertEquals(1, task.sweep(now).mayorsReplaced());

            assertEquals(heir, city.mayorUuid());
            assertNotEquals(oldMayor, city.mayorUuid());
        }

        @Test
        @DisplayName("and does not before then")
        void notYet() {
            City city = givenCity("Roma", 0);
            UUID oldMayor = city.mayorUuid();
            support.givenMember(city, "Heir");
            lastSeen(oldMayor, 29);

            assertEquals(0, task.sweep(now).mayorsReplaced());
            assertEquals(oldMayor, city.mayorUuid());
        }

        @Test
        @DisplayName("the threshold is a config key, not a literal 30")
        void configurable() {
            // The assertion that would have failed while inactivity: was a block of numbers
            // nothing read. Enforcement alone can be satisfied by a hardcoded constant.
            City city = givenCity("Roma", 0);
            support.givenMember(city, "Heir");
            lastSeen(city.mayorUuid(), 10);

            assertEquals(0, task.sweep(now).mayorsReplaced(), "ten days is under the default");

            inactivity().set("mayor-transfer-days", 5);

            assertEquals(1, task.sweep(now).mayorsReplaced(), "and over a lowered threshold");
        }

        @Test
        @DisplayName("the old mayor is demoted rather than removed, SPEC 17.1 case 1")
        void demotedNotKicked() {
            City city = givenCity("Roma", 0);
            UUID oldMayor = city.mayorUuid();
            support.givenMember(city, "Heir");
            lastSeen(oldMayor, 40);

            task.sweep(now);

            assertTrue(city.isMember(oldMayor), "they keep their seat");
            CityRank theirs = city.rank(city.member(oldMayor).orElseThrow().rankId())
                    .orElseThrow();
            CityRank mayorRank = city.mayorRank().orElseThrow();
            assertNotEquals(mayorRank.id(), theirs.id(), "but not the top rank");
            assertTrue(theirs.weight() < mayorRank.weight());
        }

        @Test
        @DisplayName("a notice is stored, because the person it is for is not here")
        void noticeStored() {
            // SPEC 17.1 case 1: "notified on next login". They are absent by definition —
            // that is why the transfer happened — so there is nowhere to send a message.
            City city = givenCity("Roma", 0);
            UUID oldMayor = city.mayorUuid();
            support.givenMember(city, "Heir");
            lastSeen(oldMayor, 40);

            task.sweep(now);

            List<PlayerNoticeRow> waiting =
                    await(support.daos.playerNotices().findFor(oldMayor));
            assertEquals(1, waiting.size());
            assertEquals("city.inactive-demoted", waiting.get(0).messageKey());
            assertTrue(waiting.get(0).placeholders().contains("Roma"),
                    "and it names the city, since a player may be in more than one over time");
        }

        @Test
        @DisplayName("the notice carries a lang key, never rendered text")
        void noticeIsALangKey() {
            // SPEC 2.1 keeps player-facing strings in lang/. A notice stored as English would
            // be unreadable to an Italian player and unfixable after the fact.
            City city = givenCity("Roma", 0);
            UUID oldMayor = city.mayorUuid();
            support.givenMember(city, "Heir");
            lastSeen(oldMayor, 40);
            task.sweep(now);

            PlayerNoticeRow notice =
                    await(support.daos.playerNotices().findFor(oldMayor)).get(0);

            assertFalse(notice.messageKey().contains(" "), "a key, not a sentence");
            assertTrue(support.configs.get(ConfigFile.CITIES) != null);
        }

        @Test
        @DisplayName("a mayor with nobody to hand over to keeps the city")
        void noSuccessor() {
            // A sole member has nobody to promote, and SPEC 17.1 case 4 already refuses to let
            // them leave. The city waits, and case 3 reaches it eventually if nobody returns.
            City city = givenCity("Roma", 0);
            lastSeen(city.mayorUuid(), 40);

            assertEquals(0, task.sweep(now).mayorsReplaced());
            assertFalse(city.isDeleted());
        }

        @Test
        @DisplayName("a successor who is also absent is not promoted")
        void successorMustBeAround() {
            // Promoting one would leave the city in exactly the state this rule exists to fix,
            // and the sweep would do the whole thing again next interval.
            City city = givenCity("Roma", 0);
            UUID absentHeir = support.givenMember(city, "AlsoGone");
            lastSeen(city.mayorUuid(), 40);
            lastSeen(absentHeir, 35);

            assertEquals(0, task.sweep(now).mayorsReplaced());
        }

        @Test
        @DisplayName("weight decides, and recency breaks the tie")
        void successionOrder() {
            // SPEC 17.1 case 1: "the highest-weight member with the most recent login". Read
            // as weight first — the other reading hands a Recruit who logged in yesterday a
            // city over a Co-Mayor who logged in last week.
            City city = givenCity("Roma", 0);
            UUID recentButJunior = support.givenMember(city, "Recruit");
            UUID seniorButOlder = support.givenMember(city, "Senior");

            CityRank senior = city.ranks().stream()
                    .filter(rank -> rank.id() != city.mayorRank().orElseThrow().id())
                    .max((a, b) -> Integer.compare(a.weight(), b.weight()))
                    .orElseThrow();
            await(support.ranks.assign(city.mayorUuid(), city, seniorButOlder, senior));

            lastSeen(city.mayorUuid(), 40);
            lastSeen(recentButJunior, 0);
            lastSeen(seniorButOlder, 5);

            Optional<UUID> chosen = task.chooseSuccessor(city,
                    java.util.Map.of(recentButJunior, now, seniorButOlder, now - 5 * DAY), now);

            assertEquals(Optional.of(seniorButOlder), chosen);
        }
    }

    // ==================================================================================
    // Case 2: dormancy
    // ==================================================================================

    @Nested
    @DisplayName("case 2, dormancy")
    class Dormancy {

        @Test
        @DisplayName("after 60 days with no login the city is dormant")
        void becomesDormant() {
            City city = givenCity("Roma", 0);
            lastSeen(city.mayorUuid(), 70);

            assertEquals(1, task.sweep(now).dormant());
            assertTrue(dormancy.isDormant(city.id()));
        }

        @Test
        @DisplayName("and its claims stop being protected, which is the whole point")
        void claimsBecomeUnprotected() {
            // SPEC 17.1 case 2: "claims become unprotected but are not removed". Asserted
            // through ProtectionService, because a cache nothing reads protects nobody — and
            // that seam sat returning false from M4 until now.
            City city = givenCity("Roma", 0);
            UUID stranger = support.givenEligiblePlayer("Stranger");
            lastSeen(city.mayorUuid(), 70);

            assertFalse(strangerMayBuild(stranger, 0, 0),
                    "a stranger cannot build there while the city is awake");

            task.sweep(now);

            assertTrue(strangerMayBuild(stranger, 0, 0),
                    "and can once it is dormant");
        }

        @Test
        @DisplayName("the claims are still there, not released")
        void landSurvives() {
            City city = givenCity("Roma", 0);
            lastSeen(city.mayorUuid(), 70);
            task.sweep(now);

            assertEquals(1, support.claimRegistry.countOf(city.id()));
            assertFalse(city.isDeleted());
        }

        @Test
        @DisplayName("a login restores protection instantly, not at the next sweep")
        void wakesOnLogin() {
            // SPEC's word is "instantly". A player logging in to defend their city must not
            // wait out an hour of interval while somebody digs through the walls.
            City city = givenCity("Roma", 0);
            UUID stranger = support.givenEligiblePlayer("Stranger");
            lastSeen(city.mayorUuid(), 70);
            task.sweep(now);
            assertTrue(strangerMayBuild(stranger, 0, 0));

            dormancy.wake(city.id());

            assertFalse(strangerMayBuild(stranger, 0, 0));
        }

        @Test
        @DisplayName("the threshold is a config key")
        void configurable() {
            City city = givenCity("Roma", 0);
            lastSeen(city.mayorUuid(), 20);

            assertEquals(0, task.sweep(now).dormant());

            inactivity().set("dormant-days", 10);

            assertEquals(1, task.sweep(now).dormant());
        }

        @Test
        @DisplayName("nothing is dormant until a sweep has run, so the failure is safe")
        void failsOpen() {
            // An empty cache means everything stays protected. The failure mode of this
            // feature must be a city keeping protection it should have lost, never the
            // reverse: a sweep that never runs must not expose anybody's build.
            City city = givenCity("Roma", 0);
            UUID stranger = support.givenEligiblePlayer("Stranger");
            lastSeen(city.mayorUuid(), 900);

            assertFalse(strangerMayBuild(stranger, 0, 0));
            assertFalse(dormancy.isDormant(city.id()));
        }

        @Test
        @DisplayName("a city that becomes active again stops being dormant on the next sweep")
        void recovers() {
            City city = givenCity("Roma", 0);
            lastSeen(city.mayorUuid(), 70);
            task.sweep(now);
            assertTrue(dormancy.isDormant(city.id()));

            lastSeen(city.mayorUuid(), 0);

            task.sweep(now);
            assertFalse(dormancy.isDormant(city.id()),
                    "replaceAll must drop cities that recovered, not only add new ones");
        }
    }

    // ==================================================================================
    // Case 3: expiry
    // ==================================================================================

    @Nested
    @DisplayName("case 3, expiry")
    class Expiry {

        @Test
        @DisplayName("after 120 days the city is soft-deleted")
        void expires() {
            City city = givenCity("Roma", 0);
            lastSeen(city.mayorUuid(), 130);

            assertEquals(1, task.sweep(now).expired());
            assertTrue(city.isDeleted());
        }

        @Test
        @DisplayName("its land is released, SPEC 17.1 case 3")
        void landReleased() {
            // The one place this differs from /ca city delete, which deliberately keeps the
            // claims so a restore gives back a real city. SPEC 17.1 case 3 says released.
            City city = givenCity("Roma", 0);
            lastSeen(city.mayorUuid(), 130);

            task.sweep(now);

            assertEquals(0, support.claimRegistry.countOf(city.id()));
            assertTrue(support.claimRegistry.at("world", 0, 0).isEmpty(),
                    "the chunk is wilderness again and somebody else may claim it");
        }

        @Test
        @DisplayName("its treasury is burned, not paid out")
        void treasuryBurned() {
            // Not disband, which splits the treasury among the members. Paying out a city
            // dead for four months would reward abandoning it.
            City city = givenCity("Roma", 0);
            await(support.daos.cities().updateTreasury(city.id(), new BigDecimal("50000.00")));
            city.setTreasury(new BigDecimal("50000.00"));
            UUID mayor = city.mayorUuid();
            BigDecimal before = await(support.daos.players().findByUuid(mayor))
                    .orElseThrow().balance();
            lastSeen(mayor, 130);

            task.sweep(now);

            assertEquals(0, BigDecimal.ZERO.compareTo(city.treasury()));
            assertEquals(0, before.compareTo(await(support.daos.players().findByUuid(mayor))
                    .orElseThrow().balance()), "nobody was paid");
        }

        @Test
        @DisplayName("the burn is written to the ledger, so it can be audited")
        void burnIsRecorded() {
            // SPEC 1.5 makes every coin movement auditable, and money leaving circulation is
            // exactly what an admin chasing a discrepancy needs to be able to find.
            City city = givenCity("Roma", 0);
            await(support.daos.cities().updateTreasury(city.id(), new BigDecimal("50000.00")));
            city.setTreasury(new BigDecimal("50000.00"));
            lastSeen(city.mayorUuid(), 130);

            task.sweep(now);

            assertTrue(await(support.daos.ledger().findByCity(city.id(), 0L, 100)).stream()
                            .anyMatch(row -> row.metadata() != null
                                    && row.metadata().contains("inactive_city_expired")),
                    "no ledger row explains where the treasury went");
        }

        @Test
        @DisplayName("deletion has its own switch, and honours it")
        void deletionCanBeTurnedOff() {
            // It is the most destructive thing this plugin does without a human asking, and
            // it fires on a timer against cities whose members may simply have been away.
            City city = givenCity("Roma", 0);
            lastSeen(city.mayorUuid(), 130);
            inactivity().set("soft-delete-enabled", false);

            assertEquals(0, task.sweep(now).expired());

            assertFalse(city.isDeleted());
            assertEquals(1, support.claimRegistry.countOf(city.id()),
                    "and the land stays");
            assertTrue(dormancy.isDormant(city.id()),
                    "while dormancy still applies, which is the point of a separate switch");
        }

        @Test
        @DisplayName("the threshold is a config key")
        void configurable() {
            City city = givenCity("Roma", 0);
            lastSeen(city.mayorUuid(), 70);

            assertEquals(0, task.sweep(now).expired(), "seventy days is dormant, not expired");

            inactivity().set("soft-delete-days", 60);

            assertEquals(1, task.sweep(now).expired());
        }

        @Test
        @DisplayName("an expiring city is not handed a new mayor first")
        void orderingIsExpiryFirst() {
            // A city past 120 days is also past 30. Promoting somebody a moment before
            // deleting the city would write two rows and an audit entry about a thing that
            // stopped existing in the same sweep.
            City city = givenCity("Roma", 0);
            support.givenMember(city, "Heir");
            lastSeen(city.mayorUuid(), 130);
            lastSeen(city.members().stream()
                    .map(CityMember::uuid)
                    .filter(uuid -> !uuid.equals(city.mayorUuid()))
                    .findFirst().orElseThrow(), 130);

            InactivityTask.Outcome outcome = task.sweep(now);

            assertEquals(1, outcome.expired());
            assertEquals(0, outcome.mayorsReplaced());
        }
    }

    // ==================================================================================
    // The sweep as a whole
    // ==================================================================================

    @Nested
    @DisplayName("the sweep")
    class Sweep {

        @Test
        @DisplayName("an active city is left completely alone")
        void activeCityUntouched() {
            City city = givenCity("Roma", 0);
            support.givenMember(city, "Someone");

            InactivityTask.Outcome outcome = task.sweep(now);

            assertEquals(new InactivityTask.Outcome(0, 0, 0), outcome);
            assertFalse(city.isDeleted());
            assertFalse(dormancy.isDormant(city.id()));
        }

        @Test
        @DisplayName("one member logging in keeps the whole city active")
        void oneActiveMemberIsEnough() {
            // SPEC 17.1 case 2 is about the *entire* city being inactive.
            City city = givenCity("Roma", 0);
            UUID active = support.givenMember(city, "Loyal");
            lastSeen(city.mayorUuid(), 200);
            lastSeen(active, 1);

            InactivityTask.Outcome outcome = task.sweep(now);

            assertFalse(city.isDeleted(), "the city survives on one active member");
            assertFalse(dormancy.isDormant(city.id()));
            assertEquals(1, outcome.mayorsReplaced(), "though the absent mayor is replaced");
        }

        @Test
        @DisplayName("the whole sweep can be switched off")
        void masterSwitch() {
            City city = givenCity("Roma", 0);
            lastSeen(city.mayorUuid(), 900);
            inactivity().set("enabled", false);

            assertEquals(new InactivityTask.Outcome(0, 0, 0), task.sweep(now));
            assertFalse(city.isDeleted());
            assertFalse(dormancy.isDormant(city.id()));
        }

        @Test
        @DisplayName("a freshly founded city with no logins yet is not swept away")
        void newCityIsSafe() {
            // Founding date stands in for activity when there is none. Without it a city
            // founded by a player whose last_seen predates the thresholds would be deleted
            // before anyone could join.
            City city = givenCity("Roma", 0);

            assertEquals(new InactivityTask.Outcome(0, 0, 0), task.sweep(now));
            assertFalse(city.isDeleted());
        }

        @Test
        @DisplayName("an already deleted city is skipped")
        void deletedCitiesSkipped() {
            City city = givenCity("Roma", 0);
            lastSeen(city.mayorUuid(), 130);
            assertEquals(1, task.sweep(now).expired());

            assertEquals(0, task.sweep(now).expired(), "and not expired a second time");
        }

        @Test
        @DisplayName("every SPEC 17.1 threshold is present in cities.yml")
        void keysExist() {
            // The block existed all along; what was missing was anything reading it. This
            // asserts the keys the task actually consults, so a rename breaks the build
            // rather than silently reverting every threshold to its default.
            ConfigurationSection section = inactivity();

            assertTrue(section.contains("enabled"));
            assertTrue(section.contains("check-interval-minutes"));
            assertTrue(section.contains("mayor-transfer-days"));
            assertTrue(section.contains("dormant-days"));
            assertTrue(section.contains("soft-delete-days"));
            assertTrue(section.contains("soft-delete-enabled"));
            assertEquals(30, task.mayorTransferDays(), "SPEC 17.1 case 1");
            assertEquals(60, task.dormantDays(), "SPEC 17.1 case 2");
            assertEquals(120, task.softDeleteDays(), "SPEC 17.1 case 3");
        }
    }
}
