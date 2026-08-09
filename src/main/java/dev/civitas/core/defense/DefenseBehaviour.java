package dev.civitas.core.defense;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;

/**
 * What a unit knows about a war, and how far it may wander. SPEC 12.3's leash and sides.
 *
 * <h2>What this no longer does</h2>
 *
 * <p>It used to decide targeting, and SPEC 30.1 forbids that: "There must be exactly one such
 * handler and no unit-specific targeting logic anywhere else." That decision moved to
 * {@link TargetingRule}, which is ordered so a member of the owning city is safe whatever
 * state a unit is in — a guarantee a second table sitting beside it would quietly undo.
 *
 * <p>What stayed is the part that was never targeting: SPEC 12.3's leash, and the two questions
 * about sides that {@link UnitTargeting} now asks through its seams. Those carry a nuance worth
 * keeping — SPEC 17.4 case 41, that a player with no city is a bystander rather than an enemy,
 * so somebody who wandered in during a war is not shot at.
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
    public boolean isEnemyOf(City owner, UUID player) {
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
    public boolean isSameSide(City owner, UUID player) {
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
