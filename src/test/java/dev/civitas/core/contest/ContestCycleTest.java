package dev.civitas.core.contest;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.income.StipendTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The automatic cycle of SPEC 13.4.
 *
 * <p>Every test here moves a clock rather than waiting, and the ones worth having are the
 * ones where the clock jumps: a phase boundary that passed while the server was down must be
 * caught up rather than missed, which is the same problem SPEC 17.3 case 31 poses for upkeep.
 */
class ContestCycleTest {

    @TempDir
    Path directory;

    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final long START = 2_000_000_000L;

    private CityTestSupport support;
    private ContestService contests;
    private ContestCycle cycle;
    private long clock = START;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        contests = support.contests;
        cycle = new ContestCycle(contests, support.registry,
                new StipendTask.Notifier() {
                    @Override
                    public void tell(UUID player, String key,
                                     net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra) {
                        // Nobody is online in a test; announcements are not what is under test.
                    }
                },
                CityTestSupport.quietLogger(), () -> clock);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private void tickAt(long now) {
        clock = now;
        cycle.run();
    }

    @Test
    @DisplayName("the first tick starts a contest, since there is none")
    void firstTickStartsOne() {
        tickAt(START);

        Contest contest = contests.current().orElseThrow();
        assertEquals(ContestState.BUILDING, contest.state());
        assertTrue(contests.themes().contains(contest.theme()));
    }

    @Test
    @DisplayName("nothing happens while the phase still matches the clock")
    void quietWhileBuilding() {
        tickAt(START);
        Contest first = contests.current().orElseThrow();

        tickAt(START + DAY);
        tickAt(START + 5 * DAY);

        assertEquals(first.id(), contests.current().orElseThrow().id());
        assertEquals(ContestState.BUILDING, contests.current().orElseThrow().state());
    }

    @Test
    @DisplayName("submissions close and voting opens on the SPEC 13.4 boundary")
    void votingOpens() {
        tickAt(START);
        int contestId = contests.current().orElseThrow().id();

        tickAt(START + (contests.buildDays() * DAY) + 1);

        assertEquals(ContestState.VOTING, contests.current().orElseThrow().state());
        assertEquals("VOTING",
                await(support.daos.contests().findById(contestId)).orElseThrow().state());
    }

    @Test
    @DisplayName("a contest is scored and finished at the end of its window")
    void scoresAtTheEnd() {
        tickAt(START);
        Contest first = contests.current().orElseThrow();

        tickAt(START + (contests.cycleDays() * DAY) + 1);

        assertEquals("FINISHED",
                await(support.daos.contests().findById(first.id())).orElseThrow().state());
    }

    @Test
    @DisplayName("a boundary missed while the server was down is caught up, not skipped")
    void catchesUpAcrossMissedPhases() {
        // The whole point of the cycle. An operator who takes the server down on day 2 and
        // brings it back on day 20 must find the contest scored, not still building.
        tickAt(START);
        Contest first = contests.current().orElseThrow();
        UUID mayor = support.givenEligiblePlayer("Romulus");
        City roma = support.givenCity(mayor, "Roma", 0, 0);
        enter(mayor, roma);

        tickAt(START + 20 * DAY);

        assertEquals("FINISHED",
                await(support.daos.contests().findById(first.id())).orElseThrow().state());
        assertEquals(1, await(support.daos.contestEntries().findAll(first.id())).size());
    }

    @Test
    @DisplayName("the next contest begins once the last one is finished")
    void rollsIntoTheNext() {
        tickAt(START);
        Contest first = contests.current().orElseThrow();

        tickAt(START + (contests.cycleDays() * DAY) + 1);

        Contest second = contests.current().orElseThrow();
        assertNotEquals(first.id(), second.id());
        assertEquals(ContestState.BUILDING, second.state());
    }

    @Test
    @DisplayName("consecutive contests do not repeat the same theme")
    void themesRotate() {
        tickAt(START);
        String first = contests.current().orElseThrow().theme();

        tickAt(START + (contests.cycleDays() * DAY) + 1);
        String second = contests.current().orElseThrow().theme();

        assertNotEquals(first, second, "a rotating pool should not hand out the same theme twice");
    }

    @Test
    @DisplayName("the phase a contest should be in is decided by the clock alone")
    void phaseAtIsPureClockArithmetic() {
        tickAt(START);
        Contest contest = contests.current().orElseThrow();

        assertEquals(ContestState.BUILDING, contest.phaseAt(START));
        assertEquals(ContestState.BUILDING, contest.phaseAt(contest.submissionsCloseAt() - 1));
        assertEquals(ContestState.VOTING, contest.phaseAt(contest.submissionsCloseAt()));
        assertEquals(ContestState.VOTING, contest.phaseAt(contest.votingEndsAt() - 1));
        assertEquals(ContestState.SCORING, contest.phaseAt(contest.votingEndsAt()));
        assertEquals(ContestState.FINISHED, contest.phaseAt(contest.endsAt()));
    }

    @Test
    @DisplayName("a disabled contest system does nothing at all")
    void disabledDoesNothing() {
        support.configs.get(dev.civitas.config.ConfigFile.EVENTS).set("contests.enabled", false);

        tickAt(START);

        assertTrue(contests.current().isEmpty());
    }

    private void enter(UUID actor, City city) {
        await(contests.mark(actor, city, "world", 0, 64, 0, clock));
        await(contests.mark(actor, city, "world", 8, 70, 8, clock));
        await(contests.submit(actor, city, clock));
    }

    /** Guards the assumption the catch-up test rests on. */
    @Test
    @DisplayName("a cycle is longer than its build and voting phases together")
    void phasesFitInsideTheCycle() {
        List<Integer> days = List.of(contests.buildDays(), contests.votingDays(),
                contests.cycleDays());

        assertTrue(days.get(0) + days.get(1) <= days.get(2),
                "SPEC 13.4's build and voting days must leave room for scoring on day 14");
    }
}
