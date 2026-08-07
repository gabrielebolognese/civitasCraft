package dev.civitas.core.defense;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;

/**
 * The SPEC 12.3 behaviour table, as decisions rather than as event handling.
 *
 * <h2>The rule that matters most</h2>
 * SPEC 12.3's first row: <b>in peacetime, a visitor in a claim is ignored completely.</b> A
 * defended city is a place people can walk through. Getting this wrong turns every city into
 * a no-go zone and contradicts SPEC 1.4 directly, which says combat is a scheduled event
 * rather than the default state of the world.
 *
 * <p>Pure, so the table can be tested one row at a time without a server, and so the listener
 * that consumes it has nothing in it but plumbing.
 */
public final class DefenseBehaviour {

    private final DefenseCatalogue catalogue;
    private final CityRegistry cities;

    public DefenseBehaviour(DefenseCatalogue catalogue, CityRegistry cities) {
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.cities = Objects.requireNonNull(cities, "cities");
    }

    /** What a unit may do about something it can see. */
    public enum Reaction {
        /** Leave it alone. */
        IGNORE,
        /** Attack it. */
        ATTACK
    }

    // ==================================================================================
    // Players
    // ==================================================================================

    /**
     * What a unit does about a player, SPEC 12.3 rows 1, 3 and 4.
     *
     * @param owner    the city the unit belongs to
     * @param player   who it can see
     * @param distance how far away, in blocks
     */
    public Reaction towardsPlayer(City owner, UUID player, double distance) {
        // Rows 3 and 4: only ever in war, and never against its own side.
        if (!isAtWarWith(owner, player)) {
            // Row 1: a visitor in peacetime is ignored completely. Not warned, not pushed,
            // not damaged.
            return catalogue.attacksPlayersInPeacetime() ? Reaction.ATTACK : Reaction.IGNORE;
        }
        if (owner.isMember(player) || isAllied(owner, player)) {
            return Reaction.IGNORE;
        }
        return distance <= catalogue.warTargetRange() ? Reaction.ATTACK : Reaction.IGNORE;
    }

    /**
     * What a unit does about a hostile mob, SPEC 12.3 row 2.
     *
     * <p>Attacks it, in peace or in war. This is what a unit does with the other 99% of its
     * existence and is most of what a city is actually buying.
     */
    public Reaction towardsHostile(City owner) {
        return catalogue.attacksHostilesInPeacetime() ? Reaction.ATTACK : Reaction.IGNORE;
    }

    // ==================================================================================
    // The leash, SPEC 12.3
    // ==================================================================================

    /**
     * Whether a unit has wandered too far and should be put back.
     *
     * <p>SPEC 12.3 measures from the claim border rather than from where the unit was placed,
     * so a guard may chase a creeper across its own city without being yanked home, and may
     * not follow a player out into the wilderness.
     *
     * @param blocksOutsideClaim how far past its city's land it currently is
     */
    public boolean shouldReturn(double blocksOutsideClaim) {
        return blocksOutsideClaim > catalogue.leashDistance();
    }

    /** Whether a unit's name should be readable from here, SPEC 12.5. */
    public boolean nameVisibleAt(double distance) {
        return distance <= catalogue.nameVisibleRange();
    }

    // ==================================================================================
    // War, wired by M19
    // ==================================================================================

    /**
     * Whether this player is on the other side of a war from this city.
     *
     * <p>The one method that turns the whole table on. A player with no city is never at war
     * with anybody, which is SPEC 17.4 case 41 again: somebody who wandered in during a war
     * is a bystander, and a guard that killed them would be attacking a passer-by.
     */
    private boolean isAtWarWith(City owner, UUID player) {
        if (wars == null) {
            return false;
        }
        Optional<City> theirs = cities.cityOf(player);
        if (theirs.isEmpty()) {
            return false;
        }
        int theirCity = theirs.get().id();
        return wars.engagedWarOf(owner.id())
                .filter(war -> war.state() == dev.civitas.core.war.WarState.ACTIVE)
                .filter(war -> war.areEnemies(owner.id(), theirCity))
                .isPresent();
    }

    /**
     * SPEC 12.3: "War, ally or own member: Ignore."
     *
     * <p>An ally here means a city on the same side of this war, which is not the same as a
     * city this one has an alliance with: SPEC 11.10 lets an ally sit a war out, and one that
     * did not join has no business being shot at either. Same side, or not a target.
     */
    private boolean isAllied(City owner, UUID player) {
        Optional<City> theirs = cities.cityOf(player);
        if (theirs.isEmpty()) {
            return false;
        }
        if (theirs.get().id() == owner.id()) {
            return true;
        }
        if (wars == null) {
            return false;
        }
        return wars.engagedWarOf(owner.id())
                .filter(war -> war.involves(theirs.get().id()))
                .filter(war -> !war.areEnemies(owner.id(), theirs.get().id()))
                .isPresent();
    }

    private dev.civitas.core.war.WarRegistry wars;

    /** SPEC 12.3's wartime rows, wired by M19. */
    public void useWars(dev.civitas.core.war.WarRegistry registry) {
        this.wars = registry;
    }
}
