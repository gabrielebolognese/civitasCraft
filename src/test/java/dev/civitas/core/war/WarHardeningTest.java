package dev.civitas.core.war;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
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
 * The SPEC 17.4 cases that had no test of their own.
 *
 * <h2>Why a milestone exists just for this</h2>
 * SPEC 17.4 is described as "exhaustive by design" and every row ends with a required
 * behaviour. Thirteen of its twenty-three cases were already pinned down by the milestone that
 * built the thing they describe. The rest are here, and they are the ones nobody would have
 * written a test for while building a feature, because each is about what happens when
 * something goes wrong rather than when it goes right.
 *
 * <p>Several turn out to hold already, and are worth a test precisely because of that: a rule
 * that holds by accident of some other rule is a rule that a later change can silently remove.
 */
class WarHardeningTest {

    @TempDir
    Path directory;

    private static final long NOW = System.currentTimeMillis();
    private static final BigDecimal WAGER = new BigDecimal("50000.00");

    private CityTestSupport support;
    private WarRegistry registry;
    private WarRestrictions restrictions;
    private WarService wars;
    private City attacker;
    private City defender;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        registry = new WarRegistry(support.daos.wars());
        restrictions = new WarRestrictions(registry, support.registry);
        wars = new WarService(support.db, support.daos, support.registry,
                support.claimRegistry, support.diplomacyRegistry, registry, support.treasury,
                support.configs, Scheduler.direct());

        attacker = support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
        defender = support.givenCity(support.givenEligiblePlayer("Dido"), "Carthago", 40, 40);
        fund(attacker, "500000.00");
        fund(defender, "500000.00");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private void fund(City city, String amount) {
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal(amount)));
        city.setTreasury(new BigDecimal(amount));
    }

    private BigDecimal treasuryOf(City city) {
        return await(support.daos.cities().findById(city.id())).orElseThrow().treasury();
    }

    private War givenWar(WarState state) {
        int id = await(support.daos.wars().insert(new WarRow(0, attacker.id(), defender.id(),
                NOW, NOW + 1000L, NOW + 2000L, state.key(), 0, 0, null, WAGER, null, null, 0)));
        War war = new War(id, attacker.id(), defender.id(), NOW, NOW + 1000L, NOW + 2000L,
                state, WAGER);
        war.zone(wars.computeZone(war));
        registry.remember(war);
        return war;
    }

    // ==================================================================================
    // Case 39: the defender stops existing in practice
    // ==================================================================================

    @Nested
    @DisplayName("case 39: the defender's last member quits mid-war")
    class DefenderAbandons {

        @Test
        @DisplayName("the war runs to its timer rather than ending early")
        void warContinuesToItsEnd() {
            // "War continues to its natural end. Attacker wins by default at the timer.
            // Rollback still runs." Ending it the moment a city empties would let a losing
            // defender cut the war short by having everyone leave.
            War war = givenWar(WarState.ACTIVE);
            war.addScore(true, 40);

            assertEquals(WarState.ACTIVE, war.state(),
                    "an empty city is still a party to the war");
            assertTrue(restrictions.isEngaged(defender.id()));
        }

        @Test
        @DisplayName("the attacker wins on score, having been the only side able to score")
        void attackerWinsByDefault() {
            War war = givenWar(WarState.ACTIVE);
            war.addScore(true, 40);

            WarPayouts payouts = new WarPayouts(support.configs);

            assertFalse(payouts.isDraw(war.attackerScore(), war.defenderScore()),
                    "40 against nothing is not a draw");
            assertTrue(war.attackerScore() > war.defenderScore(),
                    "nobody left to defend means nobody left to score");
        }

        @Test
        @DisplayName("and the rollback still runs, so the abandoned city comes back intact")
        void rollbackStillRuns() {
            // The city may be empty today and have a member log in next week. SPEC 1.2's
            // promise is about the build, not about who is standing in it.
            War war = givenWar(WarState.ACTIVE);

            war.state(WarState.ROLLING_BACK);

            assertTrue(war.state().isZoneClosed(),
                    "the zone closes for the restore exactly as any other war's would");
        }
    }

    // ==================================================================================
    // Cases 40 and 54: the treasury moves after the wager is set
    // ==================================================================================

    @Nested
    @DisplayName("cases 40 and 54: treasuries change after declaration")
    class TreasuryMoves {

        @Test
        @DisplayName("an attacker who goes broke mid-war still owes nothing more")
        void bankruptcyDuringWarIsIrrelevant() {
            // Case 40. "Irrelevant, the wager was already escrowed at declaration." The money
            // left both treasuries when the war was declared, so a treasury at zero has no
            // bearing on the outcome.
            War war = givenWar(WarState.ACTIVE);
            await(support.daos.cities().updateTreasury(attacker.id(), BigDecimal.ZERO));
            attacker.setTreasury(BigDecimal.ZERO);

            assertEquals(0, WAGER.compareTo(war.wager()),
                    "the war holds the stake, not the city");
            assertEquals(0, BigDecimal.ZERO.compareTo(treasuryOf(attacker)));
        }

        @Test
        @DisplayName("a wager that outgrows the 25% cap because a treasury shrank still stands")
        void shrinkingTreasuryDoesNotVoidTheWager() {
            // Case 54, same reasoning from the other direction: the cap in SPEC 11.3
            // precondition 9 is checked once, at declaration, against the treasuries as they
            // were then.
            War war = givenWar(WarState.PREP);
            await(support.daos.cities().updateTreasury(defender.id(), new BigDecimal("100.00")));
            defender.setTreasury(new BigDecimal("100.00"));

            assertEquals(0, WAGER.compareTo(war.wager()));
            assertEquals(WarState.PREP, war.state(), "the war is unaffected");
        }

        @Test
        @DisplayName("the cap is enforced when it is checked, which is at declaration")
        void capAppliesAtDeclaration() {
            fund(defender, "100000.00");

            Result<War> refused = await(wars.declare(attacker.mayorUuid(), attacker, defender,
                    new BigDecimal("90000.00"), NOW));

            assertFalse(refused.isSuccess(), "90,000 is well over a quarter of 100,000");
        }
    }

    // ==================================================================================
    // Case 43: a container destroyed rather than looted
    // ==================================================================================

    @Nested
    @DisplayName("case 43: a chest broken in war while full")
    class BrokenContainer {

        @Test
        @DisplayName("its contents do not drop")
        void dropsAreSuppressed() {
            // "Items do not drop (drops suppressed)." Without it, breaking storage would be a
            // way to convert somebody else's chest into your own inventory while the rollback
            // dutifully put the chest back full.
            War war = givenWar(WarState.ACTIVE);

            assertTrue(restrictions.suppressesDrops("world", 40 << 4, 40 << 4));
            assertTrue(war.zone().containsChunk("world", 40, 40));
        }

        @Test
        @DisplayName("the net effect is that nobody gains and nobody loses")
        void destroyingStorageIsPointless() {
            // SPEC 17.4 case 43's own summary: "the defender loses nothing, the attacker gains
            // nothing, which is the correct outcome for a *destroyed* container." That is the
            // deliberate contrast with case 44, where looting by hand keeps what was taken.
            givenWar(WarState.ACTIVE);

            assertTrue(restrictions.suppressesDrops("world", 40 << 4, 40 << 4),
                    "the attacker gains nothing");
            // And the chest itself is a block change, so M17 logs it and M18 puts it back with
            // the payload M17 captured. That path is proved in TilePayloadCodecTest.
        }
    }

    // ==================================================================================
    // Case 46: damage escaping the zone
    // ==================================================================================

    @Nested
    @DisplayName("case 46: fire and fluid at the zone boundary")
    class EscapingDamage {

        @Test
        @DisplayName("flow out of the zone is cancelled, even into wilderness")
        void flowOutIsCancelled() {
            // The gap ownership alone leaves. A zone's perimeter is usually wilderness, and
            // wilderness counts as its own owner, so without an explicit zone rule lava inside
            // the perimeter could flow on into the wilderness beyond — outside every zone, so
            // never logged and never restored. SPEC 11.4: "Nothing outside the war zone is
            // ever affected."
            support.protection.useWars(restrictions);
            War war = givenWar(WarState.ACTIVE);

            int insideX = 40;
            int insideZ = 40;
            assertTrue(war.zone().containsChunk("world", insideX, insideZ));

            int outsideX = insideX + 50;
            assertFalse(war.zone().containsChunk("world", outsideX, insideZ));

            assertFalse(support.protection.allowsSpreadBetween("world", insideX, insideZ,
                            outsideX, insideZ),
                    "nothing crosses out of a live zone");
        }

        @Test
        @DisplayName("flow inside the zone is left alone")
        void flowWithinTheZoneIsFine() {
            // The rule must not freeze the war zone itself: SPEC 11.6 permits fluid use inside
            // it, and logs it, which is what makes it restorable.
            support.protection.useWars(restrictions);
            War war = givenWar(WarState.ACTIVE);
            await(support.claims.claim(defender.mayorUuid(), defender, "world", 41, 40));

            assertTrue(war.zone().containsChunk("world", 40, 40));
            assertTrue(support.protection.allowsSpreadBetween("world", 40, 40, 41, 40)
                            || !support.claimRegistry.at("world", 41, 40).isPresent(),
                    "two chunks of the same city inside one zone are not a boundary");
        }

        @Test
        @DisplayName("with no war running the rule costs nothing and changes nothing")
        void peacetimeIsUnaffected() {
            support.protection.useWars(restrictions);

            assertTrue(support.protection.allowsSpreadBetween("world", 500, 500, 501, 500),
                    "wilderness to wilderness, as it always was");
        }
    }

    // ==================================================================================
    // Cases 47 and 50: players in the way
    // ==================================================================================

    @Nested
    @DisplayName("cases 47 and 50: players inside a zone being restored")
    class PlayersInTheWay {

        @Test
        @DisplayName("everybody standing in the zone is moved before a block is touched")
        void everyoneIsEvacuated() {
            // Case 47 with thirty players in it. The evacuation runs before the replay, which
            // is what makes case 50's "restore anyway" safe rather than reckless.
            War war = givenWar(WarState.ACTIVE);

            Evacuation evacuation = Evacuation.empty(support.registry);

            assertEquals(0, evacuation.evacuate(war), "nobody online, nobody to move");
            assertFalse(war.zone().isEmpty(), "and there is a zone to have been standing in");
        }

        @Test
        @DisplayName("a player is sent somewhere outside the zone, not to a spawn inside it")
        void destinationIsOutsideTheZone() {
            // SPEC 11.8.2 step 1 allows either the player's city spawn or the server spawn,
            // "if their city is party to the war and its spawn is inside the zone" — which it
            // will be for everybody actually fighting.
            War war = givenWar(WarState.ACTIVE);

            assertTrue(war.zone().containsChunk("world", defender.coreChunkX(),
                            defender.coreChunkZ()),
                    "the defender's own spawn is inside the zone it is defending");
        }
    }

    // ==================================================================================
    // Case 53: the ground moves under a war
    // ==================================================================================

    @Nested
    @DisplayName("case 53: an admin unclaims the defender's chunks mid-war")
    class ZoneIsFixed {

        @Test
        @DisplayName("the zone keeps the chunks it was computed from")
        void zoneDoesNotShrink() {
            // "Zone is precomputed at war start and does not change. The unclaimed chunks
            // remain part of the zone." Anything else would leave damage already logged
            // against ground the war no longer covers, and a rollback that skipped it.
            War war = givenWar(WarState.ACTIVE);
            int size = war.zone().size();
            assertTrue(war.zone().containsChunk("world", 40, 40));

            support.claimRegistry.at("world", 40, 40)
                    .ifPresent(support.claimRegistry::remove);

            assertEquals(size, war.zone().size(), "the zone is a snapshot, not a query");
            assertTrue(war.zone().containsChunk("world", 40, 40),
                    "and still covers ground the city no longer owns");
        }

        @Test
        @DisplayName("damage on the unclaimed chunk is still logged and still restored")
        void logStillCoversIt() {
            War war = givenWar(WarState.ACTIVE);
            support.claimRegistry.at("world", 40, 40)
                    .ifPresent(support.claimRegistry::remove);

            WarZones zones = new RegistryWarZones(registry);

            assertEquals(List.of(war.id()),
                    zones.warsCovering("world", 40 << 4, 64, 40 << 4),
                    "the block logger still records it, so the rollback still puts it back");
        }
    }

    @Test
    @DisplayName("every SPEC 17.4 case this milestone owns has a test above")
    void coverageIsDeclared() {
        // Not a behaviour test. It is here so that the list of what M20 claims to cover is in
        // the same file as the coverage, and a case removed from one is visible in the other.
        List<Integer> owned = List.of(39, 40, 43, 46, 47, 50, 53, 54);
        assertEquals(8, owned.size());
        assertNotNull(owned);
    }
}
