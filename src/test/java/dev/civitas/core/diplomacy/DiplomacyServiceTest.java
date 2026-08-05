package dev.civitas.core.diplomacy;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.util.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Alliances and truces, SPEC 14.
 *
 * <p>One test per rule in SPEC 14.2 and 14.3, because every rule there exists to stop
 * diplomacy being used as a weapon: the cap stops blocs, the notice stops betrayal, the
 * cooldown stops break-and-reform, and a truce nobody can cancel is the only kind worth
 * signing.
 */
class DiplomacyServiceTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;

    @TempDir
    Path directory;

    private CityTestSupport support;
    private DiplomacyService diplomacy;
    private DiplomacyRegistry registry;

    private UUID romulus;
    private UUID aeneas;
    private City roma;
    private City ostia;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        diplomacy = support.diplomacy;
        registry = support.diplomacyRegistry;

        romulus = support.givenEligiblePlayer("Romulus");
        aeneas = support.givenEligiblePlayer("Aeneas");
        roma = support.givenCity(romulus, "Roma", 0, 0);
        ostia = support.givenCity(aeneas, "Ostia", 40, 40);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    // ==================================================================================
    // Proposing and accepting
    // ==================================================================================

    @Nested
    @DisplayName("forming an alliance, SPEC 14.2")
    class Forming {

        @Test
        @DisplayName("a proposal binds nobody until the other city accepts")
        void proposalIsNotAnAlliance() {
            assertTrue(await(diplomacy.invite(romulus, roma, ostia)) instanceof Result.Success);

            assertFalse(registry.areAllied(roma.id(), ostia.id()),
                    "a pending proposal must not count as an alliance");
            assertEquals(0, registry.allyCount(roma.id()));
            assertEquals(1, registry.pendingFor(ostia.id()).size());
            assertEquals(0, registry.pendingFor(roma.id()).size(),
                    "the proposing city is not waiting on itself");
        }

        @Test
        @DisplayName("accepting makes it mutual, whichever way round the pair is read")
        void acceptingIsSymmetric() {
            await(diplomacy.invite(romulus, roma, ostia));
            assertTrue(await(diplomacy.accept(aeneas, ostia, roma)) instanceof Result.Success);

            assertTrue(registry.areAllied(roma.id(), ostia.id()));
            assertTrue(registry.areAllied(ostia.id(), roma.id()));
            assertEquals(1, registry.allyCount(roma.id()));
            assertEquals(1, registry.allyCount(ostia.id()));
        }

        @Test
        @DisplayName("the city that proposed cannot accept its own proposal")
        void proposerCannotAccept() {
            await(diplomacy.invite(romulus, roma, ostia));

            Result<Alliance> accepted = await(diplomacy.accept(romulus, roma, ostia));
            assertEquals("NO_PROPOSAL", reasonOf(accepted));
            assertFalse(registry.areAllied(roma.id(), ostia.id()));
        }

        @Test
        @DisplayName("a member without MANAGE_DIPLOMACY cannot propose")
        void permissionIsRequired() {
            UUID citizen = support.givenMember(roma, "Numa");

            Result<Alliance> proposed = await(diplomacy.invite(citizen, roma, ostia));
            assertEquals("NO_CITY_PERMISSION", reasonOf(proposed));
        }

        @Test
        @DisplayName("a city cannot ally with itself")
        void noSelfAlliance() {
            assertEquals("SAME_CITY", reasonOf(await(diplomacy.invite(romulus, roma, roma))));
        }

        @Test
        @DisplayName("a second proposal to the same city is refused")
        void noDuplicateProposal() {
            await(diplomacy.invite(romulus, roma, ostia));
            assertEquals("ALREADY_PENDING",
                    reasonOf(await(diplomacy.invite(romulus, roma, ostia))));
        }

        @Test
        @DisplayName("re-proposing after a break records the new proposer, SPEC 14.2")
        void reProposalMovesTheProposer() {
            ally(roma, ostia);
            await(diplomacy.breakAlliance(romulus, roma, ostia));
            completeNotice(roma, ostia);
            // Past the seven-day cooldown.
            rewindStateChange(roma, ostia, 8 * DAY);

            // Ostia proposes this time, so Roma must be the one able to accept.
            assertTrue(await(diplomacy.invite(aeneas, ostia, roma)) instanceof Result.Success);
            assertEquals("NO_PROPOSAL", reasonOf(await(diplomacy.accept(aeneas, ostia, roma))));
            assertTrue(await(diplomacy.accept(romulus, roma, ostia)) instanceof Result.Success);
        }
    }

    // ==================================================================================
    // The cap, SPEC 14.2
    // ==================================================================================

    @Nested
    @DisplayName("the three-ally cap, SPEC 14.2")
    class Cap {

        @Test
        @DisplayName("a fourth alliance is refused")
        void capIsThree() {
            for (int index = 0; index < 3; index++) {
                ally(roma, foundCity("Ally" + index, 200 + index * 40));
            }
            assertEquals(3, registry.allyCount(roma.id()));

            City fourth = foundCity("Veii", 600);
            assertEquals("ALLY_LIMIT", reasonOf(await(diplomacy.invite(romulus, roma, fourth))));
        }

        @Test
        @DisplayName("the cap is re-checked at acceptance, not only at invitation")
        void capIsRecheckedOnAccept() {
            // Ostia is invited while it still has room, then fills up before it answers.
            await(diplomacy.invite(romulus, roma, ostia));
            for (int index = 0; index < 3; index++) {
                ally(ostia, foundCity("Filler" + index, 200 + index * 40));
            }

            assertEquals("ALLY_LIMIT", reasonOf(await(diplomacy.accept(aeneas, ostia, roma))));
        }

        @Test
        @DisplayName("a city that is full cannot be proposed to either")
        void theirCapCounts() {
            for (int index = 0; index < 3; index++) {
                ally(ostia, foundCity("Full" + index, 200 + index * 40));
            }
            assertEquals("THEIR_ALLY_LIMIT",
                    reasonOf(await(diplomacy.invite(romulus, roma, ostia))));
        }
    }

    // ==================================================================================
    // Breaking, SPEC 14.2
    // ==================================================================================

    @Nested
    @DisplayName("breaking an alliance, SPEC 14.2")
    class Breaking {

        @Test
        @DisplayName("the alliance still holds during the notice period")
        void noticeKeepsTheAllianceAlive() {
            ally(roma, ostia);

            assertTrue(await(diplomacy.breakAlliance(romulus, roma, ostia))
                    instanceof Result.Success);

            assertEquals(AllianceState.BREAKING,
                    registry.alliance(roma.id(), ostia.id()).orElseThrow().state());
            assertTrue(registry.areAllied(roma.id(), ostia.id()),
                    "SPEC 14.2: during the notice period the alliance still holds");
            assertEquals(1, registry.allyCount(roma.id()),
                    "a slot is not freed until the notice runs out");
        }

        @Test
        @DisplayName("notice cannot be given twice, so it cannot be shortened")
        void noticeCannotBeRestarted() {
            ally(roma, ostia);
            await(diplomacy.breakAlliance(romulus, roma, ostia));

            assertEquals("ALREADY_BREAKING",
                    reasonOf(await(diplomacy.breakAlliance(romulus, roma, ostia))));
        }

        @Test
        @DisplayName("the notice expires after the configured hours and not before")
        void noticeExpiresOnTime() {
            ally(roma, ostia);
            await(diplomacy.breakAlliance(romulus, roma, ostia));
            Alliance breaking = registry.alliance(roma.id(), ostia.id()).orElseThrow();

            long given = breaking.stateChangedAt();
            assertFalse(diplomacy.noticeExpired(breaking, given + 23 * HOUR));
            assertTrue(diplomacy.noticeExpired(breaking, given + 24 * HOUR));
        }

        @Test
        @DisplayName("completing the break frees a slot and drops the build grant")
        void completingTheBreakEndsIt() {
            ally(roma, ostia);
            await(diplomacy.setTrusted(romulus, roma, ostia, true));
            await(diplomacy.breakAlliance(romulus, roma, ostia));
            completeNotice(roma, ostia);

            assertFalse(registry.areAllied(roma.id(), ostia.id()));
            assertFalse(registry.areTrusted(roma.id(), ostia.id()));
            assertEquals(0, registry.allyCount(roma.id()));
            assertEquals(AllianceState.BROKEN,
                    registry.alliance(roma.id(), ostia.id()).orElseThrow().state());
        }

        @Test
        @DisplayName("a city that was never allied cannot break")
        void breakingNothing() {
            assertEquals("NOT_ALLIED",
                    reasonOf(await(diplomacy.breakAlliance(romulus, roma, ostia))));
        }
    }

    // ==================================================================================
    // The re-ally cooldown, SPEC 14.2
    // ==================================================================================

    @Nested
    @DisplayName("the seven-day re-ally cooldown, SPEC 14.2")
    class Cooldown {

        @Test
        @DisplayName("the same two cities cannot re-ally straight away")
        void cooldownBlocks() {
            ally(roma, ostia);
            await(diplomacy.breakAlliance(romulus, roma, ostia));
            completeNotice(roma, ostia);

            assertEquals("REALLY_COOLDOWN",
                    reasonOf(await(diplomacy.invite(romulus, roma, ostia))));
        }

        @Test
        @DisplayName("they may re-ally once seven days have passed")
        void cooldownExpires() {
            ally(roma, ostia);
            await(diplomacy.breakAlliance(romulus, roma, ostia));
            completeNotice(roma, ostia);
            rewindStateChange(roma, ostia, 7 * DAY);

            assertTrue(await(diplomacy.invite(romulus, roma, ostia)) instanceof Result.Success);
        }

        @Test
        @DisplayName("the cooldown is per pair, so a different city is unaffected")
        void cooldownIsPerPair() {
            ally(roma, ostia);
            await(diplomacy.breakAlliance(romulus, roma, ostia));
            completeNotice(roma, ostia);

            City veii = foundCity("Veii", 200);
            assertTrue(await(diplomacy.invite(romulus, roma, veii)) instanceof Result.Success);
        }
    }

    // ==================================================================================
    // Trust, SPEC 14.2
    // ==================================================================================

    @Nested
    @DisplayName("reciprocal build access, SPEC 14.2")
    class Trust {

        @Test
        @DisplayName("trust can only be granted to an actual ally")
        void trustNeedsAnAlliance() {
            assertEquals("NOT_ALLIED",
                    reasonOf(await(diplomacy.setTrusted(romulus, roma, ostia, true))));
        }

        @Test
        @DisplayName("trust is a property of the pair, so it reads the same from both sides")
        void trustIsSymmetric() {
            ally(roma, ostia);
            await(diplomacy.setTrusted(romulus, roma, ostia, true));

            assertTrue(registry.areTrusted(roma.id(), ostia.id()));
            assertTrue(registry.areTrusted(ostia.id(), roma.id()));

            await(diplomacy.setTrusted(aeneas, ostia, roma, false));
            assertFalse(registry.areTrusted(roma.id(), ostia.id()));
        }

        @Test
        @DisplayName("trust survives a reload, because it is written not only cached")
        void trustIsPersisted() {
            ally(roma, ostia);
            await(diplomacy.setTrusted(romulus, roma, ostia, true));

            await(registry.loadAll(System.currentTimeMillis()));
            assertTrue(registry.areTrusted(roma.id(), ostia.id()));
        }
    }

    // ==================================================================================
    // Truces, SPEC 14.3
    // ==================================================================================

    @Nested
    @DisplayName("truces, SPEC 14.3")
    class Truces {

        @Test
        @DisplayName("a truce holds for the days asked for")
        void truceRuns() {
            long before = System.currentTimeMillis();
            Result<Long> offered = await(diplomacy.offerTruce(romulus, roma, ostia, 5));
            assertTrue(offered instanceof Result.Success);

            long expiresAt = offered.orElseThrow();
            assertTrue(expiresAt >= before + 5 * DAY);
            assertTrue(registry.hasTruce(roma.id(), ostia.id(), before));
            assertFalse(registry.hasTruce(roma.id(), ostia.id(), expiresAt + 1));
        }

        @Test
        @DisplayName("a truce shorter than a day or longer than thirty is refused")
        void truceLengthIsBounded() {
            assertEquals("BAD_LENGTH", reasonOf(await(diplomacy.offerTruce(romulus, roma,
                    ostia, 0))));
            assertEquals("BAD_LENGTH", reasonOf(await(diplomacy.offerTruce(romulus, roma,
                    ostia, 31))));
        }

        @Test
        @DisplayName("a second truce cannot be laid over a running one")
        void oneTruceAtATime() {
            await(diplomacy.offerTruce(romulus, roma, ostia, 5));
            assertEquals("ALREADY_TRUCED",
                    reasonOf(await(diplomacy.offerTruce(aeneas, ostia, roma, 30))));
        }

        @Test
        @DisplayName("a war end imposes a truce with nobody's consent, SPEC 14.3")
        void postWarTruceIsImposed() {
            long now = System.currentTimeMillis();
            long expiresAt = await(diplomacy.imposePostWarTruce(roma.id(), ostia.id(), now));

            // SPEC 11.9 and war.yml rewards.immunity-days, which is 7.
            assertEquals(now + 7 * DAY, expiresAt);
            assertTrue(registry.hasTruce(roma.id(), ostia.id(), now));
        }

        @Test
        @DisplayName("expired truces are dropped when the cache reloads")
        void expiredTrucesAreNotLoaded() {
            await(support.daos.truces().upsert(roma.id(), ostia.id(),
                    System.currentTimeMillis() - 1_000L));

            await(registry.loadAll(System.currentTimeMillis()));
            assertFalse(registry.hasTruce(roma.id(), ostia.id(), System.currentTimeMillis()));
        }
    }

    // ==================================================================================
    // Relations, SPEC 14.1
    // ==================================================================================

    @Nested
    @DisplayName("relations, SPEC 14.1")
    class Relations {

        @Test
        @DisplayName("two cities that have never met are neutral")
        void defaultIsNeutral() {
            assertEquals(Relation.NEUTRAL,
                    diplomacy.relationBetween(roma.id(), ostia.id(), System.currentTimeMillis()));
        }

        @Test
        @DisplayName("an alliance reads as ALLY from both sides")
        void allyReads() {
            ally(roma, ostia);
            long now = System.currentTimeMillis();
            assertEquals(Relation.ALLY, diplomacy.relationBetween(roma.id(), ostia.id(), now));
            assertEquals(Relation.ALLY, diplomacy.relationBetween(ostia.id(), roma.id(), now));
        }

        @Test
        @DisplayName("a truce outranks an alliance, because it is the one with an end date")
        void trucePrecedesAlliance() {
            ally(roma, ostia);
            await(diplomacy.offerTruce(romulus, roma, ostia, 3));

            assertEquals(Relation.TRUCE, diplomacy.relationBetween(roma.id(), ostia.id(),
                    System.currentTimeMillis()));
        }

        @Test
        @DisplayName("a city is neutral toward itself, not allied")
        void selfIsNeutral() {
            assertEquals(Relation.NEUTRAL, diplomacy.relationBetween(roma.id(), roma.id(),
                    System.currentTimeMillis()));
        }
    }

    // ==================================================================================
    // Disband
    // ==================================================================================

    @Test
    @DisplayName("a disbanded city takes its alliances and truces with it")
    void disbandForgetsEverything() {
        ally(roma, ostia);
        await(diplomacy.offerTruce(romulus, roma, foundCity("Veii", 200), 5));

        Result<City> disbanded = await(support.cities.disband(romulus, roma));
        assertTrue(disbanded instanceof Result.Success, reasonOf(disbanded));

        assertFalse(registry.areAllied(roma.id(), ostia.id()));
        assertEquals(0, registry.allyCount(ostia.id()));
        assertTrue(registry.trucesOf(roma.id(), System.currentTimeMillis()).isEmpty());
        assertTrue(await(support.daos.alliances().findByCity(roma.id())).isEmpty());
    }

    // ==================================================================================
    // Fixtures
    // ==================================================================================

    /**
     * Founds a city far enough away that the SPEC 5.1 distance rule is satisfied.
     *
     * <p>Its mayor is the actor in every fixture call, and SPEC 5.4 gives a mayor every
     * permission, so nothing has to be granted.
     */
    private City foundCity(String name, int chunk) {
        UUID mayor = support.givenEligiblePlayer(name + "Mayor");
        return support.givenCity(mayor, name, chunk, chunk);
    }

    /** Puts two cities into a settled alliance. */
    private void ally(City first, City second) {
        UUID firstMayor = first.mayorUuid();
        UUID secondMayor = second.mayorUuid();
        Result<Alliance> proposed = await(diplomacy.invite(firstMayor, first, second));
        assertTrue(proposed instanceof Result.Success, reasonOf(proposed));
        Result<Alliance> accepted = await(diplomacy.accept(secondMayor, second, first));
        assertTrue(accepted instanceof Result.Success, reasonOf(accepted));
    }

    /** Runs the sweep's work for one pair whose notice has run out. */
    private void completeNotice(City first, City second) {
        Alliance alliance = registry.alliance(first.id(), second.id()).orElseThrow();
        await(diplomacy.completeBreak(alliance, System.currentTimeMillis()));
    }

    /**
     * Backdates when a pair last changed state.
     *
     * <p>The alternative is a test that sleeps for seven days. The cooldown is measured from
     * this stamp and nothing else, so moving the stamp is the same as moving the clock.
     */
    private void rewindStateChange(City first, City second, long by) {
        Alliance alliance = registry.alliance(first.id(), second.id()).orElseThrow();
        long moved = alliance.stateChangedAt() - by;
        await(support.daos.alliances().updateState(first.id(), second.id(),
                alliance.state().name(), moved));
        registry.put(alliance.withState(alliance.state(), moved));
    }

}
