package dev.civitas.core.war;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.civitas.core.city.CityRegistry;

/**
 * What a war forbids and what it permits, SPEC 11.6 and 11.11, as decisions with no I/O.
 *
 * <h2>Two lists, and the asymmetry between them</h2>
 * SPEC 11.11 lists what is blocked for both cities during PREP and ACTIVE: claiming,
 * disbanding, members joining or leaving, transfers, outposts, another war, breaking an
 * alliance, buying upgrades. Every one of those exists so a city cannot change what it is
 * while a war is being fought over it. SPEC 17.4 case 38 is the sharpest example: a defender
 * who could disband mid-war would take the war zone with them.
 *
 * <p>SPEC 11.6 lists what becomes permitted, and that list is much shorter and much more
 * carefully bounded: grief and PvP, inside the zone, between opposing sides, in exactly one
 * state. Everything else stays protected, including three things that stay protected
 * <em>during</em> a war: the City Hall, defense unit spawners, and admin-protected chunks.
 */
public final class WarRestrictions {

    private final WarRegistry registry;
    private final CityRegistry cities;

    public WarRestrictions(WarRegistry registry, CityRegistry cities) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.cities = Objects.requireNonNull(cities, "cities");
    }

    // ==================================================================================
    // SPEC 11.11: what a city cannot do while engaged
    // ==================================================================================

    /** Whether this city is bound by SPEC 11.11 right now. */
    public boolean isEngaged(int cityId) {
        return registry.engagedWarOf(cityId).isPresent();
    }

    /** SPEC 6.3 precondition 9 and SPEC 11.5: the zone must not move under the war. */
    public boolean blocksClaiming(int cityId) {
        return isEngaged(cityId);
    }

    /** SPEC 17.4 case 38: a defender who disbanded would take the war zone with them. */
    public boolean blocksDisband(int cityId) {
        return isEngaged(cityId);
    }

    /**
     * SPEC 11.5: "Members cannot leave either city (prevents rat-fleeing before the fight)"
     * and "New members cannot join either city (prevents mercenary stacking)".
     */
    public boolean blocksMembershipChange(int cityId) {
        return isEngaged(cityId);
    }

    public boolean blocksTransfer(int cityId) {
        return isEngaged(cityId);
    }

    /** SPEC 11.11 and SPEC 7.2: no outpost creation, deletion or teleport. */
    public boolean blocksOutposts(int cityId) {
        return isEngaged(cityId);
    }

    /** SPEC 11.3 precondition 6, restated: a city fights one war at a time. */
    public boolean blocksNewWar(int cityId) {
        return isEngaged(cityId);
    }

    /** SPEC 11.11: an alliance cannot be broken mid-war. */
    public boolean blocksAllianceBreak(int cityId) {
        return isEngaged(cityId);
    }

    public boolean blocksUpgrades(int cityId) {
        return isEngaged(cityId);
    }

    // ==================================================================================
    // SPEC 11.6: what becomes permitted, and where
    // ==================================================================================

    /**
     * Whether one player may damage this city's land right now.
     *
     * <p>Four conditions, all required: a war is ACTIVE, the block is inside its zone, the
     * actor belongs to a city, and the two cities are on opposite sides. A bystander standing
     * in a war zone is not a combatant, which SPEC 17.4 case 41 requires directly.
     */
    public boolean isGriefPermitted(int ownerCityId, UUID actor, String world, int x, int z) {
        if (actor == null) {
            return false;
        }
        // SPEC 11.6 lists admin-protected chunks among the three things that stay protected
        // even in war, alongside the City Hall and defense unit spawners. Without this a
        // protected build inside a zone would be flattened and then restored, which is not
        // the same as never being touched: the rollback is a promise about the end state and
        // this is a promise about the whole war.
        if (adminProtection != null && adminProtection.isProtectedAtBlock(world, x, z)) {
            return false;
        }
        Optional<Integer> actorCity = cities.cityOf(actor).map(dev.civitas.core.city.City::id);
        if (actorCity.isEmpty()) {
            return false;
        }
        for (War war : registry.activeWarsCovering(world, x, z)) {
            if (war.areEnemies(ownerCityId, actorCity.get())) {
                return true;
            }
        }
        return false;
    }

    /**
     * SPEC 5.5: PvP is "enabled only inside the claims of cities that are party to an active
     * war".
     */
    public boolean isPvpPermitted(UUID attacker, UUID victim, String world, int x, int z) {
        if (attacker == null || victim == null) {
            return false;
        }
        Optional<Integer> attackerCity = cities.cityOf(attacker)
                .map(dev.civitas.core.city.City::id);
        Optional<Integer> victimCity = cities.cityOf(victim).map(dev.civitas.core.city.City::id);
        if (attackerCity.isEmpty() || victimCity.isEmpty()) {
            return false;
        }
        for (War war : registry.activeWarsCovering(world, x, z)) {
            if (war.areEnemies(attackerCity.get(), victimCity.get())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this position is inside any war's zone, whatever its state.
     *
     * <p>Used to close the zone during a rollback: SPEC 11.8.2 step 1 says entry is blocked
     * with a message once the restore begins.
     */
    public boolean isZoneClosed(String world, int x, int z) {
        for (War war : registry.all()) {
            if (war.state().isZoneClosed() && war.zone().containsBlock(world, x, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * SPEC 11.8.3: "Breaking a block in a war zone drops nothing."
     *
     * <p>SPEC 11.8.3 calls the no-drops rule critical and says why: without it a war is a free
     * strip-mining event, because attackers keep the materials of 50,000 broken blocks and the
     * rollback puts the blocks back anyway. That is resources from nothing, and SPEC 17.6 case
     * 73 names it as the main duplication vector the rule closes.
     */
    public boolean suppressesDrops(String world, int x, int z) {
        return !registry.activeWarsCovering(world, x, z).isEmpty();
    }

    private dev.civitas.core.admin.AdminProtection adminProtection;

    /** SPEC 11.6's admin-protected chunks, wired by M21. */
    public void useAdminProtection(dev.civitas.core.admin.AdminProtection protection) {
        this.adminProtection = protection;
    }

    /** Whether any war is ACTIVE at all, for the listeners' first line. */
    public boolean isAnyWarActive() {
        return registry.isAnyWarActive();
    }

    /**
     * Whether this position is inside the zone of any war being fought right now.
     *
     * <p>SPEC 17.4 case 46 and SPEC 11.4's flat statement that "nothing outside the war zone is
     * ever affected". Fire and fluid crossing the boundary is the one way damage escapes a war
     * without a player carrying it: nobody places the lava that flows, so nothing outside is
     * logged, and what is not logged is not restored.
     */
    public boolean isInActiveZone(String world, int blockX, int blockZ) {
        return !registry.activeWarsCovering(world, blockX, blockZ).isEmpty();
    }
}
