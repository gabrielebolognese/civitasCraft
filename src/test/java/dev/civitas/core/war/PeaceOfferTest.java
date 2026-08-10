package dev.civitas.core.war;

import static dev.civitas.core.city.CityTestSupport.await;
import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;

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
 * SPEC 8.8's sue for peace.
 *
 * <p>It is the mid-war counterpart of SPEC 11.3's decline, and it exists for the same reason
 * SPEC 15.2 gives for that one: a side losing badly needs an alternative to logging off for a
 * week. The forfeit is what stops it being a free way to stop a fight you are losing.
 */
class PeaceOfferTest {

    @TempDir
    Path directory;

    private static final long NOW = System.currentTimeMillis();
    private static final BigDecimal WAGER = new BigDecimal("50000.00");

    private CityTestSupport support;
    private WarRegistry registry;
    private PeaceOffer peace;
    private City attacker;
    private City defender;

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        registry = new WarRegistry(support.daos.wars());
        peace = new PeaceOffer(support.db, support.daos, support.registry, support.treasury,
                support.configs, Scheduler.direct());

        attacker = support.givenCity(support.givenEligiblePlayer("Romulus"), "Roma", 0, 0);
        defender = support.givenCity(support.givenEligiblePlayer("Dido"), "Carthago", 40, 40);
        fund(attacker, "0.00");
        fund(defender, "0.00");
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
        registry.remember(war);
        return war;
    }

    // ==================================================================================
    // Offering
    // ==================================================================================

    @Nested
    @DisplayName("offering peace")
    class Offering {

        @Test
        @DisplayName("an offer is recorded and charges nothing yet")
        void offerIsFree() {
            War war = givenWar(WarState.ACTIVE);

            assertTrue(peace.offer(attacker.mayorUuid(), attacker, war).isSuccess());

            assertEquals(attacker.id(), peace.pendingOffer(war.id()).orElseThrow());
            assertEquals(0, BigDecimal.ZERO.compareTo(treasuryOf(attacker)),
                    "nothing is charged until the other side accepts");
        }

        @Test
        @DisplayName("offering twice is refused")
        void oneOfferPerWar() {
            War war = givenWar(WarState.ACTIVE);
            peace.offer(attacker.mayorUuid(), attacker, war);

            assertEquals("ALREADY_OFFERED",
                    reasonOf(peace.offer(attacker.mayorUuid(), attacker, war)));
        }

        @Test
        @DisplayName("a city not in the war cannot offer peace in it")
        void mustBeInTheWar() {
            War war = givenWar(WarState.ACTIVE);
            City outsider = support.givenCity(support.givenEligiblePlayer("Aeneas"),
                    "Lavinium", 80, 80);

            assertEquals("NOT_IN_WAR",
                    reasonOf(peace.offer(outsider.mayorUuid(), outsider, war)));
        }

        @Test
        @DisplayName("peace cannot be offered in a war that is already over")
        void onlyWhileRunning() {
            War war = givenWar(WarState.ROLLING_BACK);

            assertEquals("WRONG_PHASE",
                    reasonOf(peace.offer(attacker.mayorUuid(), attacker, war)));
        }

        @Test
        @DisplayName("a member without DECLARE_WAR cannot sue for peace")
        void needsPermission() {
            War war = givenWar(WarState.ACTIVE);
            java.util.UUID citizen = support.givenMember(attacker, "Remus");

            assertEquals("NO_PERMISSION", reasonOf(peace.offer(citizen, attacker, war)));
        }
    }

    // ==================================================================================
    // Accepting
    // ==================================================================================

    @Nested
    @DisplayName("accepting peace")
    class Accepting {

        @Test
        @DisplayName("both wagers come back, less the offerer's forfeit")
        void forfeitFallsOnTheOfferer() {
            // SPEC 8.8 words it from the suing side: "forfeits 25% of your wager". That is
            // what makes an offer a concession rather than a free exit.
            War war = givenWar(WarState.ACTIVE);
            peace.offer(attacker.mayorUuid(), attacker, war);

            Result<War> ended = await(peace.accept(defender.mayorUuid(), defender, war, NOW));

            assertTrue(ended.isSuccess(), reasonOf(ended));
            assertEquals(0, new BigDecimal("37500.00").compareTo(treasuryOf(attacker)));
            assertEquals(0, new BigDecimal("62500.00").compareTo(treasuryOf(defender)));
        }

        @Test
        @DisplayName("accepting your own offer is refused")
        void cannotAcceptYourOwn() {
            // SPEC 8.8 requires both mayors. Without this a war could be ended unilaterally.
            War war = givenWar(WarState.ACTIVE);
            peace.offer(attacker.mayorUuid(), attacker, war);

            assertEquals("OWN_OFFER",
                    reasonOf(await(peace.accept(attacker.mayorUuid(), attacker, war, NOW))));
        }

        @Test
        @DisplayName("accepting with no offer on the table is refused")
        void needsAnOffer() {
            War war = givenWar(WarState.ACTIVE);

            assertEquals("NO_OFFER",
                    reasonOf(await(peace.accept(defender.mayorUuid(), defender, war, NOW))));
        }

        @Test
        @DisplayName("a peace mid-war still rolls the world back")
        void activePeaceRollsBack() {
            // SPEC 8.8 does not say, and it must: the damage is already logged and SPEC 1.2
            // promises it is never permanent. Recorded in OPEN_QUESTIONS.md.
            War war = givenWar(WarState.ACTIVE);
            peace.offer(attacker.mayorUuid(), attacker, war);

            await(peace.accept(defender.mayorUuid(), defender, war, NOW));

            assertEquals(WarState.ROLLING_BACK, war.state());
        }

        @Test
        @DisplayName("a peace during preparation simply cancels")
        void prepPeaceCancels() {
            // Nothing was destroyed, so there is nothing to restore.
            War war = givenWar(WarState.PREP);
            peace.offer(attacker.mayorUuid(), attacker, war);

            await(peace.accept(defender.mayorUuid(), defender, war, NOW));

            assertEquals(WarState.CANCELLED, war.state());
        }

        @Test
        @DisplayName("a peace records no winner")
        void noWinner() {
            // A peace is not a victory, so nothing goes on either city's war record.
            War war = givenWar(WarState.ACTIVE);
            war.winnerCityId(attacker.id());
            peace.offer(attacker.mayorUuid(), attacker, war);

            await(peace.accept(defender.mayorUuid(), defender, war, NOW));

            assertNull(war.winnerCityId());
        }

        @Test
        @DisplayName("the offer is cleared once it is taken")
        void offerIsConsumed() {
            War war = givenWar(WarState.ACTIVE);
            peace.offer(attacker.mayorUuid(), attacker, war);

            await(peace.accept(defender.mayorUuid(), defender, war, NOW));

            assertTrue(peace.pendingOffer(war.id()).isEmpty());
        }

        @Test
        @DisplayName("either side may be the one to sue")
        void defenderCanSueToo() {
            War war = givenWar(WarState.ACTIVE);
            peace.offer(defender.mayorUuid(), defender, war);

            await(peace.accept(attacker.mayorUuid(), attacker, war, NOW));

            assertEquals(0, new BigDecimal("37500.00").compareTo(treasuryOf(defender)),
                    "the defender forfeits when the defender asked");
            assertEquals(0, new BigDecimal("62500.00").compareTo(treasuryOf(attacker)));
        }
    }

    @Test
    @DisplayName("the forfeit is a quarter of the wager, from war.yml")
    void forfeitIsConfigured() {
        War war = givenWar(WarState.ACTIVE);

        assertEquals(0, new BigDecimal("12500.00").compareTo(peace.forfeitOf(war)));
    }

    @Test
    @DisplayName("forgetting a war drops its offer")
    void forgetClears() {
        War war = givenWar(WarState.ACTIVE);
        peace.offer(attacker.mayorUuid(), attacker, war);

        peace.forget(war.id());

        assertFalse(peace.pendingOffer(war.id()).isPresent());
    }
}
