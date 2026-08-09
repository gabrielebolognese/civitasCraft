package dev.civitas.core.defense;

import static dev.civitas.core.city.CityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityTestSupport;
import dev.civitas.core.protection.ProtectionAction;
import dev.civitas.core.protection.ProtectionDecision;
import dev.civitas.core.protection.ProtectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 26.2's sequence, joined up.
 *
 * <p>{@link WhoCounts#aCitizenRattlingALockedChestIsNotATrespasser} is the reason this class
 * exists. The obvious wiring — treat any protection refusal as a violation — is wrong in a way
 * that would be extremely visible in play, because a city's own member who lacks a permission
 * is refused too. SPEC 26.2 counts violations "by a non-member", and the difference between
 * those two readings is whether a city's guards attack the people who live there.
 *
 * <p>{@link Debounce#aBurstCountsOnce} is the second of those. The refusals that feed this are
 * not one per deliberate act, and counted raw a player who holds a mouse button for a fifth of
 * a second is a three-strike raider.
 */
class TrespassServiceTest {

    private static final String WORLD = "world";

    @TempDir
    Path directory;

    private CityTestSupport support;
    private TrespassService trespass;
    private ProtectionService protection;
    private UnitStates states;
    private DefenseRegistry units;

    private UUID mayor;
    private UUID stranger;
    private City city;

    private final List<TrespassService.Event> seen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        support = CityTestSupport.open(directory);
        states = new UnitStates();
        units = new DefenseRegistry(support.daos.defenseUnits());
        protection = new ProtectionService(support.claimRegistry, support.registry,
                support.configs);

        trespass = new TrespassService(support.configs, support.registry,
                support.claimRegistry, states, units);
        trespass.useEffects(seen::add);

        mayor = support.givenEligiblePlayer("Romulus");
        stranger = support.givenEligiblePlayer("Hostis");
        city = support.givenCity(mayor, "Roma", 0, 0);
        await(support.daos.cities().updateTreasury(city.id(), new BigDecimal("1000000")));
        city.setTreasury(new BigDecimal("1000000"));
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    /**
     * One violation.
     *
     * <p>Strikes below are two seconds apart because the service debounces at
     * {@code trespass.violation-cooldown-ms} (1500). That is not scaffolding working round the
     * product: a burst closer together than that <em>is</em> one player action, which
     * {@link Debounce} asserts on its own.
     */
    private boolean strike(UUID player, long at) {
        return trespass.violated(city.id(), player, null, at);
    }

    /** Three strikes, spaced past the debounce, which is how a warning is actually earned. */
    private void strikeThrice() {
        strike(stranger, 1_000L);
        strike(stranger, 3_000L);
        strike(stranger, 5_000L);
    }

    /** A unit standing in the city, materialised as far as the states are concerned. */
    private int givenMaterializedUnit(int id) {
        DefenseUnit unit = new DefenseUnit(id, city.id(), "city_guard", WORLD, 8, 64, 8,
                new BigDecimal("900"), true, null, null);
        units.put(unit);
        states.materialized(unit.id());
        return unit.id();
    }

    private List<TrespassService.Event.Kind> kinds() {
        return seen.stream().map(TrespassService.Event::kind).toList();
    }

    // ==================================================================================
    // Who generates a violation at all
    // ==================================================================================

    @Nested
    @DisplayName("who counts as a trespasser, SPEC 26.2")
    class WhoCounts {

        @Test
        @DisplayName("a citizen rattling a locked chest is not a trespasser")
        void aCitizenRattlingALockedChestIsNotATrespasser() {
            // The whole reason violations are fed from the NOT_A_MEMBER refusal rather than
            // from any refusal. A Recruit without CONTAINER is denied exactly like a stranger,
            // and counting it would have a city warn and then attack its own citizens.
            UUID citizen = support.givenMember(city, "Titus");

            ProtectionDecision denied = protection.check(citizen, false, WORLD, 0, 0,
                    ProtectionAction.CONTAINER_TAKE);

            assertTrue(denied.denied(), "the fixture needs a member who is actually refused");
            assertFalse("NOT_A_MEMBER".equals(denied.reason()),
                    "a member must never be refused as a non-member; got " + denied.reason());
        }

        @Test
        @DisplayName("a stranger doing the same is refused as a non-member")
        void aStrangerIsRefusedAsANonMember() {
            ProtectionDecision denied = protection.check(stranger, false, WORLD, 0, 0,
                    ProtectionAction.CONTAINER_TAKE);

            assertTrue(denied.denied());
            assertEquals("NOT_A_MEMBER", denied.reason(),
                    "this reason is what the guard feeds the trespass response");
        }
    }

    // ==================================================================================
    // The sequence
    // ==================================================================================

    @Nested
    @DisplayName("the sequence, SPEC 26.2")
    class Sequence {

        @Test
        @DisplayName("two violations do nothing but get written down")
        void twoIsNotEnough() {
            assertFalse(strike(stranger, 1_000L));
            assertFalse(strike(stranger, 3_000L));

            assertEquals(List.of(TrespassService.Event.Kind.VIOLATION,
                            TrespassService.Event.Kind.VIOLATION), kinds(),
                    "SPEC 26.2 logs violations so an admin can see the pattern, and two "
                            + "strikes and a walk away is a pattern that never warns");
        }

        @Test
        @DisplayName("the third warns, and warning is all it does")
        void thirdWarns() {
            strike(stranger, 1_000L);
            strike(stranger, 3_000L);
            assertTrue(strike(stranger, 5_000L));

            assertEquals(TrespassService.Event.Kind.WARNING, seen.get(seen.size() - 1).kind());
            assertTrue(trespass.response().alertedIn(city.id(), 5_000L).isEmpty(),
                    "nothing is alerted during the warning");
        }

        @Test
        @DisplayName("staying through the warning earns the alert")
        void stayingEarnsTheAlert() {
            strikeThrice();

            assertTrue(trespass.warningEnded(city.id(), stranger, null, true, 8_000L));

            assertEquals(TrespassService.Event.Kind.ALERTED, seen.get(seen.size() - 1).kind());
            assertEquals(List.of(stranger), trespass.response().alertedIn(city.id(), 8_000L));
        }

        @Test
        @DisplayName("SPEC 26.2: leaving during the warning means nothing happens")
        void leavingDuringTheWarningIsSafe() {
            strikeThrice();

            assertFalse(trespass.warningEnded(city.id(), stranger, null, false, 8_000L));

            assertEquals(TrespassService.Event.Kind.CALMED, seen.get(seen.size() - 1).kind());
            assertTrue(trespass.response().alertedIn(city.id(), 8_000L).isEmpty());
        }

        @Test
        @DisplayName("leaving the claims calms it and forgets the strikes")
        void leavingCalms() {
            strikeThrice();
            trespass.warningEnded(city.id(), stranger, null, true, 8_000L);

            trespass.leftClaims(city.id(), stranger, null);

            assertTrue(trespass.response().alertedIn(city.id(), 9_000L).isEmpty());
            assertEquals(0, trespass.tracker().count(city.id(), stranger, 9_000L));
        }

        @Test
        @DisplayName("switching the feature off means no violation ever counts")
        void disabled() {
            support.configs.get(ConfigFile.DEFENSE).set("trespass.enabled", false);

            assertFalse(strike(stranger, 1_000L));
            assertFalse(strike(stranger, 3_000L));
            assertFalse(strike(stranger, 5_000L));
            assertTrue(seen.isEmpty());
        }
    }

    // ==================================================================================
    // One player action is one violation
    // ==================================================================================

    @Nested
    @DisplayName("the debounce, so one action is one violation")
    class Debounce {

        @Test
        @DisplayName("a burst inside the cooldown counts once, so a held click is not a raid")
        void aBurstCountsOnce() {
            // Two seconds of holding left-click on a protected block: forty break events, all
            // refused. Counted raw this is thirteen warnings, and the first thing a visitor
            // ever hears from a city is a false accusation.
            for (int tick = 0; tick < 40; tick++) {
                strike(stranger, 1_000L + tick * 50L);
            }

            assertEquals(2, trespass.tracker().count(city.id(), stranger, 3_000L),
                    "two seconds of holding a button is two violations at most, not forty");
            assertFalse(kinds().contains(TrespassService.Event.Kind.WARNING),
                    "and it must not have earned a warning on its own");
        }

        @Test
        @DisplayName("the cooldown is a config key, per the hard rule on hardcoded numbers")
        void theCooldownIsConfigurable() {
            support.configs.get(ConfigFile.DEFENSE).set("trespass.violation-cooldown-ms", 0);

            strike(stranger, 1_000L);
            strike(stranger, 1_000L);

            assertTrue(strike(stranger, 1_000L),
                    "with the debounce off, three refusals in one millisecond warn");
        }

        @Test
        @DisplayName("one city's cooldown says nothing about another's")
        void perCity() {
            City other = support.givenCity(support.givenEligiblePlayer("Remus"), "Alba", 40, 40);

            trespass.violated(city.id(), stranger, null, 1_000L);
            // The same millisecond, a different city. The debounce is about one player doing
            // one thing, and these two cities did not see the same thing.
            trespass.violated(other.id(), stranger, null, 1_000L);

            assertEquals(1, trespass.tracker().count(city.id(), stranger, 1_000L));
            assertEquals(1, trespass.tracker().count(other.id(), stranger, 1_000L));
        }
    }

    // ==================================================================================
    // SPEC 26.3 and SPEC 30.2 case 94
    // ==================================================================================

    @Nested
    @DisplayName("war, and coming back")
    class WarAndRejoining {

        @Test
        @DisplayName("SPEC 26.3: trespass response is suspended during an active war")
        void suspendedDuringWar() {
            trespass.useWars(cityId -> true);

            strikeThrice();

            assertTrue(seen.isEmpty(),
                    "everything in the zone is hostile anyway, and warning an attacker "
                            + "before the guards engage them is the opposite of a siege");
        }

        @Test
        @DisplayName("SPEC 30.2 case 94: a live alert goes back onto units that stood up again")
        void reapplyPutsUnitsBackOnAlert() {
            int unitId = givenMaterializedUnit(1);
            strikeThrice();
            trespass.warningEnded(city.id(), stranger, null, true, 8_000L);
            assertEquals(List.of(stranger), trespass.response().alertedIn(city.id(), 8_000L));

            // What a logout and a return look like from the units' side. Case 95 lets them
            // dematerialise while the trespasser is away, and UnitStates.materialized brings
            // every one of them back PASSIVE — so the response says ALERTED, every unit says
            // PASSIVE, and without reapply not one guard would move.
            states.dematerialized(unitId);
            states.materialized(unitId);
            assertEquals(UnitState.PASSIVE, states.stateOf(unitId, 9_000L));

            assertEquals(1, trespass.reapply(city.id(), 9_000L));
            assertEquals(UnitState.ALERTED, states.stateOf(unitId, 9_000L));
            assertEquals(List.of(city.id()), trespass.citiesAlerting(stranger, 9_000L));
        }

        @Test
        @DisplayName("a re-applied alert ends when the response does, not 45 seconds later")
        void reapplyDoesNotExtendTheAlert() {
            int unitId = givenMaterializedUnit(1);
            strikeThrice();
            trespass.warningEnded(city.id(), stranger, null, true, 8_000L);
            long ends = trespass.response().endsAt(city.id(), stranger).orElseThrow();

            states.dematerialized(unitId);
            states.materialized(unitId);
            trespass.reapply(city.id(), ends - 1_000L);

            assertEquals(UnitState.ALERTED, states.stateOf(unitId, ends - 1L));
            assertEquals(UnitState.PASSIVE, states.stateOf(unitId, ends + 1L),
                    "a unit standing up must not buy the trespasser another 45 seconds");
        }

        @Test
        @DisplayName("nothing is re-applied for a player nobody is alerted against")
        void reapplyIsQuietWhenNothingIsRunning() {
            givenMaterializedUnit(1);

            assertEquals(0, trespass.reapply(city.id(), 9_000L));
            assertTrue(trespass.citiesAlerting(stranger, 9_000L).isEmpty());
        }
    }

    // ==================================================================================
    // What the listener is handed to roar with
    // ==================================================================================

    @Nested
    @DisplayName("the units that roar, SPEC 26.2 step 1")
    class Roar {

        @Test
        @DisplayName("a unit with no entity is not handed over, because a row cannot make a noise")
        void dormantUnitsAreNotHandedOver() {
            // SPEC 26.2 step 1 says "all materialized units ... roar or play their alert
            // sound". A dormant unit is a database row with nowhere for a sound to come from.
            int standing = givenMaterializedUnit(1);
            int dormant = givenMaterializedUnit(2);
            units.link(UUID.randomUUID(), standing);

            assertTrue(units.isMaterialized(standing));
            assertFalse(units.isMaterialized(dormant));
        }

        @Test
        @DisplayName("no location means no units, rather than the whole garrison")
        void noLocationNoUnits() {
            givenMaterializedUnit(1);

            assertTrue(trespass.materializedUnitsNear(city, null).isEmpty());
        }
    }
}
