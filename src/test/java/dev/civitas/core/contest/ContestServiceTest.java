package dev.civitas.core.contest;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.storage.row.ContestEntryRow;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Building contests, SPEC 13.4.
 *
 * <p>SPEC 18 assigns no tests to this milestone, so these follow its style. The ones that
 * matter most are the anti-abuse rules: a contest is the ladder SPEC 13.3 offers players who
 * cannot win on money, and a riggable ladder is worse than no ladder.
 */
class ContestServiceTest {

    @TempDir
    Path directory;

    private CityTestSupport support;
    private ContestService contests;
    private UUID mayor;
    private City roma;

    private static final long NOW = 1_000_000_000L;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        contests = support.contests;
        mayor = support.givenEligiblePlayer("Romulus");
        roma = support.givenCity(mayor, "Roma", 0, 0);
        fund(roma, "500000.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private void fund(City city, String amount) {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal(amount)));
        city.setTreasury(new BigDecimal(amount));
    }

    private Contest givenContest() {
        Result<Contest> started = await(contests.start("Medieval Market", 14, NOW));
        assertTrue(started.isSuccess(), reasonOf(started));
        return started.orElseThrow();
    }

    /** Marks a region inside the city's core chunk, which is chunk (0,0). */
    private Result<PlotRegion> markRegion(UUID actor, City city, int size) {
        await(contests.mark(actor, city, "world", 0, 64, 0, NOW));
        return await(contests.mark(actor, city, "world", size - 1, 64 + size - 1, size - 1, NOW));
    }

    // ==================================================================================
    // Marking, SPEC 13.4 step 2
    // ==================================================================================

    @Nested
    @DisplayName("marking a region")
    class Marking {

        @Test
        @DisplayName("the first corner is remembered and the second completes the region")
        void twoCorners() {
            givenContest();

            Result<PlotRegion> first = await(contests.mark(mayor, roma, "world", 0, 64, 0, NOW));
            assertTrue(first.isSuccess());
            assertNull(first.orElse(null), "one corner is not yet a region");
            assertTrue(contests.hasPendingCorner(mayor));

            Result<PlotRegion> second = await(contests.mark(mayor, roma, "world", 9, 70, 9, NOW));
            assertNotNull(second.orElse(null));
            assertFalse(contests.hasPendingCorner(mayor));
            assertEquals(10, second.orElseThrow().width());
        }

        @Test
        @DisplayName("a region larger than the limit is refused, SPEC 13.4")
        void tooLarge() {
            givenContest();
            int max = contests.maxRegionSize();

            // Deliberately one block over the limit on a single axis.
            await(contests.mark(mayor, roma, "world", 0, 64, 0, NOW));
            Result<PlotRegion> result =
                    await(contests.mark(mayor, roma, "world", max, 64, 0, NOW));

            assertEquals("REGION_TOO_LARGE", reasonOf(result));
        }

        @Test
        @DisplayName("a region exactly at the limit is allowed")
        void exactlyAtTheLimit() {
            givenContest();
            claimEnoughChunks();
            int max = contests.maxRegionSize();

            await(contests.mark(mayor, roma, "world", 0, 64, 0, NOW));
            Result<PlotRegion> result =
                    await(contests.mark(mayor, roma, "world", max - 1, 64, 0, NOW));

            assertTrue(result.isSuccess(), reasonOf(result));
        }

        @Test
        @DisplayName("a region that leaves the city's own claims is refused")
        void outsideClaims() {
            givenContest();

            // Chunk (5,5) belongs to nobody, so the far corner is off the city's land.
            await(contests.mark(mayor, roma, "world", 0, 64, 0, NOW));
            Result<PlotRegion> result =
                    await(contests.mark(mayor, roma, "world", 20, 64, 20, NOW));

            assertEquals("REGION_OUTSIDE_CLAIMS", reasonOf(result));
        }

        @Test
        @DisplayName("a corner in another world replaces the first rather than pairing with it")
        void worldsDoNotPair() {
            givenContest();

            await(contests.mark(mayor, roma, "world", 0, 64, 0, NOW));
            Result<PlotRegion> other =
                    await(contests.mark(mayor, roma, "nether", 5, 64, 5, NOW));

            assertTrue(other.isSuccess());
            assertNull(other.orElse(null), "a cross-world pair would be nonsense coordinates");
            assertTrue(contests.hasPendingCorner(mayor));
        }

        @Test
        @DisplayName("a member without CONTEST_SUBMIT cannot mark")
        void needsPermission() {
            givenContest();
            UUID citizen = support.givenMember(roma, "Cincinnatus");

            Result<PlotRegion> result =
                    await(contests.mark(citizen, roma, "world", 0, 64, 0, NOW));

            assertEquals("NO_PERMISSION", reasonOf(result));
        }

        /** Claims chunks 1..4 on both axes so a 64-block region fits inside the city. */
        private void claimEnoughChunks() {
            for (int x = 0; x <= 4; x++) {
                for (int z = 0; z <= 4; z++) {
                    if (x == 0 && z == 0) {
                        continue;
                    }
                    await(support.claims.claim(mayor, roma, "world", x, z));
                }
            }
        }
    }

    // ==================================================================================
    // Submitting, SPEC 13.4 step 3
    // ==================================================================================

    @Nested
    @DisplayName("submitting")
    class Submitting {

        @Test
        @DisplayName("a marked region can be submitted, once")
        void submitOnce() {
            givenContest();
            assertTrue(markRegion(mayor, roma, 8).isSuccess());

            Result<ContestEntryRow> first = await(contests.submit(mayor, roma, NOW));
            assertTrue(first.isSuccess(), reasonOf(first));

            Result<ContestEntryRow> again = await(contests.submit(mayor, roma, NOW));
            assertEquals("ALREADY_SUBMITTED", reasonOf(again));
        }

        @Test
        @DisplayName("submitting without a marked region is refused")
        void needsARegion() {
            givenContest();

            assertEquals("NO_REGION", reasonOf(await(contests.submit(mayor, roma, NOW))));
        }

        @Test
        @DisplayName("a submitted entry cannot have its region moved")
        void regionIsFinalAfterSubmission() {
            givenContest();
            markRegion(mayor, roma, 8);
            await(contests.submit(mayor, roma, NOW));

            Result<PlotRegion> moved = markRegion(mayor, roma, 4);

            assertEquals("ALREADY_SUBMITTED", reasonOf(moved));
        }

        @Test
        @DisplayName("submissions are refused once the building phase is over")
        void refusedAfterTheDeadline() {
            Contest contest = givenContest();
            markRegion(mayor, roma, 8);
            contests.remember(contest.withState(ContestState.VOTING));

            assertEquals("WRONG_PHASE", reasonOf(await(contests.submit(mayor, roma, NOW))));
        }

        @Test
        @DisplayName("with no contest running there is nothing to submit to")
        void noContest() {
            assertEquals("NO_CONTEST", reasonOf(await(contests.submit(mayor, roma, NOW))));
        }
    }

    // ==================================================================================
    // Voting, SPEC 13.4 step 4
    // ==================================================================================

    @Nested
    @DisplayName("voting")
    class Voting {

        private int entryId;
        private UUID outsider;

        @BeforeEach
        void enterAndOpenVoting() {
            Contest contest = givenContest();
            markRegion(mayor, roma, 8);
            entryId = await(contests.submit(mayor, roma, NOW)).orElseThrow().id();
            contests.remember(contest.withState(ContestState.VOTING));
            outsider = support.givenPlayer("Iudex", BigDecimal.ZERO, TimeUnit.HOURS.toMillis(40));
        }

        /** Moves the clock into the voting window of the contest started at NOW. */
        private long duringVoting() {
            return NOW + 12 * DAY;
        }

        @Test
        @DisplayName("a vote is recorded with its three axes")
        void recordsAllAxes() {
            Result<Vote> result = await(contests.vote(outsider, entryId,
                    Map.of(VoteAxis.CREATIVITY, 9,
                            VoteAxis.TECHNICAL_SKILL, 6,
                            VoteAxis.THEME_FIT, 3),
                    duringVoting()));

            assertTrue(result.isSuccess(), reasonOf(result));
            assertEquals(6.0, result.orElseThrow().combined(), 1e-9);
        }

        @Test
        @DisplayName("a score outside one to ten is refused, per axis")
        void refusesOutOfRange() {
            Result<Vote> result = await(contests.vote(outsider, entryId,
                    Map.of(VoteAxis.CREATIVITY, 9,
                            VoteAxis.TECHNICAL_SKILL, 11,
                            VoteAxis.THEME_FIT, 3),
                    duringVoting()));

            assertEquals("SCORE_OUT_OF_RANGE", reasonOf(result));
        }

        @Test
        @DisplayName("a missing axis is refused rather than counted as zero")
        void refusesMissingAxis() {
            Result<Vote> result = await(contests.vote(outsider, entryId,
                    Map.of(VoteAxis.CREATIVITY, 9), duringVoting()));

            assertEquals("SCORE_OUT_OF_RANGE", reasonOf(result));
        }

        @Test
        @DisplayName("a member of the entered city cannot vote for it, SPEC 13.4")
        void selfVoteRefused() {
            Result<Vote> result = await(contests.vote(mayor, entryId,
                    ContestService.uniform(10), duringVoting()));

            assertEquals("SELF_VOTE", reasonOf(result));
        }

        @Test
        @DisplayName("an account under the playtime bar votes at a quarter weight")
        void newAccountWeighted() {
            UUID fresh = support.givenPlayer("Novus", BigDecimal.ZERO, TimeUnit.MINUTES.toMillis(30));

            Result<Vote> result = await(contests.vote(fresh, entryId,
                    ContestService.uniform(8), duringVoting()));

            assertTrue(result.isSuccess(), reasonOf(result));
            assertEquals(0.25, result.orElseThrow().weight(), 1e-9);
        }

        @Test
        @DisplayName("a vote from a connection shared with a member is stored at zero weight")
        void sharedConnectionDiscarded() {
            // SPEC 13.4 discards these. Storing at zero rather than refusing keeps the voter
            // from learning anything about the other account's connection.
            String shared = "hash-of-a-shared-address";
            await(support.daos.playerLogins().upsert(mayor, shared, NOW));
            await(support.daos.playerLogins().upsert(outsider, shared, NOW));

            Result<Vote> result = await(contests.vote(outsider, entryId,
                    ContestService.uniform(10), duringVoting()));

            assertTrue(result.isSuccess(), "the vote is accepted, not refused");
            assertEquals(0.0, result.orElseThrow().weight(), 1e-9);
            assertFalse(result.orElseThrow().counts());
        }

        @Test
        @DisplayName("a different connection is not discarded")
        void unrelatedConnectionCounts() {
            await(support.daos.playerLogins().upsert(mayor, "one-address", NOW));
            await(support.daos.playerLogins().upsert(outsider, "another-address", NOW));

            Result<Vote> result = await(contests.vote(outsider, entryId,
                    ContestService.uniform(10), duringVoting()));

            assertEquals(1.0, result.orElseThrow().weight(), 1e-9);
        }

        @Test
        @DisplayName("voting again replaces the earlier vote rather than adding one")
        void secondVoteReplaces() {
            await(contests.vote(outsider, entryId, ContestService.uniform(3), duringVoting()));
            await(contests.vote(outsider, entryId, ContestService.uniform(9), duringVoting()));

            assertEquals(1L, await(support.daos.contestVotes().count()));
            assertEquals(9.0,
                    await(support.daos.contestVotes().findByEntry(entryId)).get(0).score(), 1e-9);
        }

        @Test
        @DisplayName("voting before the phase opens is refused")
        void refusedOutsideTheWindow() {
            contests.remember(contests.current().orElseThrow().withState(ContestState.BUILDING));

            Result<Vote> result = await(contests.vote(outsider, entryId,
                    ContestService.uniform(5), NOW));

            assertEquals("WRONG_PHASE", reasonOf(result));
        }

        @Test
        @DisplayName("a disqualified entry cannot be voted for")
        void disqualifiedEntryIsClosed() {
            await(contests.disqualify(entryId, "Built before the contest"));

            Result<Vote> result = await(contests.vote(outsider, entryId,
                    ContestService.uniform(5), duringVoting()));

            assertEquals("ENTRY_NOT_IN_RUNNING", reasonOf(result));
        }
    }

    // ==================================================================================
    // Scoring and prizes, SPEC 13.4 step 5
    // ==================================================================================

    @Nested
    @DisplayName("scoring")
    class Scoring {

        @Test
        @DisplayName("the weighted mean is what an entry scores")
        void weightedMean() {
            // One full-weight 8 and one quarter-weight 4: (8 + 1) / 1.25 = 7.2
            double score = ContestService.tally(List.of(
                    vote(8.0, 1.0),
                    vote(4.0, 0.25)));

            assertEquals(7.2, score, 1e-9);
        }

        @Test
        @DisplayName("a discarded vote pulls the score neither up nor down")
        void discardedVotesDoNotCount() {
            double withDiscard = ContestService.tally(List.of(
                    vote(9.0, 1.0),
                    vote(1.0, 0.0)));

            assertEquals(9.0, withDiscard, 1e-9);
        }

        @Test
        @DisplayName("an entry nobody voted for scores zero rather than dividing by nothing")
        void noVotes() {
            assertEquals(0.0, ContestService.tally(List.of()), 1e-9);
        }

        @Test
        @DisplayName("prizes go to the treasuries, best first, SPEC 4.2")
        void prizesArePaid() {
            Contest contest = givenContest();
            UUID rivalMayor = support.givenEligiblePlayer("Dido");
            City carthago = support.givenCity(rivalMayor, "Carthago", 40, 40);

            int romaEntry = enter(mayor, roma, 0, 0);
            int carthagoEntry = enter(rivalMayor, carthago, 40, 40);

            contests.remember(contest.withState(ContestState.VOTING));
            UUID judge = support.givenPlayer("Iudex", BigDecimal.ZERO, TimeUnit.HOURS.toMillis(40));
            long voting = NOW + 12 * DAY;
            await(contests.vote(judge, carthagoEntry, ContestService.uniform(9), voting));
            await(contests.vote(judge, romaEntry, ContestService.uniform(4), voting));

            BigDecimal romaBefore = treasuryOf(roma.id());
            BigDecimal carthagoBefore = treasuryOf(carthago.id());

            Result<List<ContestService.Placement>> scored =
                    await(contests.score(contest.withState(ContestState.SCORING)));
            assertTrue(scored.isSuccess(), reasonOf(scored));

            List<ContestService.Placement> places = scored.orElseThrow();
            assertEquals(2, places.size());
            assertEquals(carthago.id(), places.get(0).entry().cityId());
            assertEquals(1, places.get(0).place());
            assertEquals(2, places.get(1).place());

            assertEquals(carthagoBefore.add(contests.prizeFor(1)), treasuryOf(carthago.id()));
            assertEquals(romaBefore.add(contests.prizeFor(2)), treasuryOf(roma.id()));
        }

        @Test
        @DisplayName("an entry with no votes is placed but paid nothing")
        void unvotedEntryWinsNothing() {
            Contest contest = givenContest();
            enter(mayor, roma, 0, 0);
            BigDecimal before = treasuryOf(roma.id());

            Result<List<ContestService.Placement>> scored =
                    await(contests.score(contest.withState(ContestState.SCORING)));

            assertEquals(BigDecimal.ZERO, scored.orElseThrow().get(0).prize(),
                    "entering unopposed must not be a way to farm the treasury");
            assertEquals(before, treasuryOf(roma.id()));
        }

        @Test
        @DisplayName("a disqualified entry is left out of the results entirely")
        void disqualifiedEntryIsNotScored() {
            Contest contest = givenContest();
            int entryId = enter(mayor, roma, 0, 0);
            await(contests.disqualify(entryId, "Built before the window"));

            Result<List<ContestService.Placement>> scored =
                    await(contests.score(contest.withState(ContestState.SCORING)));

            assertTrue(scored.orElseThrow().isEmpty());
        }

        @Test
        @DisplayName("scoring marks the contest finished, so prizes cannot be paid twice")
        void scoringIsFinal() {
            Contest contest = givenContest();
            enter(mayor, roma, 0, 0);

            await(contests.score(contest.withState(ContestState.SCORING)));

            assertEquals(ContestState.FINISHED.key(),
                    await(support.daos.contests().findById(contest.id())).orElseThrow().state());
        }

        private BigDecimal treasuryOf(int cityId) {
            return await(support.daos.cities().findById(cityId)).orElseThrow().treasury();
        }

        private dev.civitas.storage.row.ContestVoteRow vote(double score, double weight) {
            return new dev.civitas.storage.row.ContestVoteRow(0, 1, UUID.randomUUID(), 1,
                    0, 0, 0, score, weight);
        }
    }

    /** Marks and submits an entry inside a city's core chunk. */
    private int enter(UUID actor, City city, int chunkX, int chunkZ) {
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        await(contests.mark(actor, city, "world", baseX, 64, baseZ, NOW));
        Result<PlotRegion> marked =
                await(contests.mark(actor, city, "world", baseX + 8, 70, baseZ + 8, NOW));
        assertTrue(marked.isSuccess(), reasonOf(marked));
        Result<ContestEntryRow> submitted = await(contests.submit(actor, city, NOW));
        assertTrue(submitted.isSuccess(), reasonOf(submitted));
        return submitted.orElseThrow().id();
    }

    // ==================================================================================
    // What cannot be checked yet
    // ==================================================================================

    @Test
    @DisplayName("the build-window check reports that it cannot run, rather than passing silently")
    void buildWindowVerificationIsHonest() {
        // SPEC 13.4 wants entries verified against block placement logs. No such log exists
        // outside a war (SPEC 11.8.1 is war-scoped, M17), and quietly passing every entry
        // would let an operator believe a check was running.
        assertFalse(contests.canVerifyBuildWindow());
        assertTrue(contests.wantsUnavailableVerification(),
                "the shipped config asks for a check this build cannot perform, and says so");
    }

    @Test
    @DisplayName("a second contest cannot start while one is running")
    void oneContestAtATime() {
        givenContest();

        assertEquals("CONTEST_RUNNING", reasonOf(await(contests.start("Harbour Town", 14, NOW))));
    }

    @Test
    @DisplayName("disqualification needs a reason and keeps the entry on record")
    void disqualificationIsAudited() {
        givenContest();
        int entryId = enter(mayor, roma, 0, 0);

        assertEquals("NO_REASON", reasonOf(await(contests.disqualify(entryId, "  "))));

        assertTrue(await(contests.disqualify(entryId, "Built before the window")).isSuccess());
        ContestEntryRow row = await(support.daos.contestEntries().findById(entryId)).orElseThrow();
        assertTrue(row.disqualified());
        assertEquals("Built before the window", row.disqualifiedReason());
    }
}
