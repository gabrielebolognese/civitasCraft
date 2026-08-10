package dev.civitas.core.defense;

import java.util.Objects;
import java.util.UUID;

/**
 * SPEC 30.1's decision table. The only place in the plugin that decides what a unit may attack.
 *
 * <p>SPEC 30.1 is unusually prescriptive about this: "Every targeting decision in the plugin
 * flows through one handler... There must be exactly one such handler and no unit-specific
 * targeting logic anywhere else." The reason is visible in the table itself — the rules are
 * ordered, and a unit-specific shortcut that skipped to the state check would attack a member
 * of its own city. So there is one rule, it is pure, and every branch has a test.
 *
 * <h2>The order is the specification</h2>
 *
 * <p>Ownership, alliance, bypass, game mode and the join grace all cancel <b>before</b> state is
 * consulted. That is what makes "a city member is never attacked" true in all four states rather
 * than in the three somebody remembered to check. Reordering this table is a behaviour change
 * even when every individual rule still reads correctly.
 */
public final class TargetingRule {

    /** What the rule decided, and why — the reason is for diagnostics, never for the player. */
    public record Decision(boolean allowed, String reason) {

        public Decision {
            Objects.requireNonNull(reason, "reason");
        }

        static Decision cancel(String reason) {
            return new Decision(false, reason);
        }

        static Decision allow(String reason) {
            return new Decision(true, reason);
        }
    }

    /** Everything the rule needs to know about the thing a unit can see. */
    public record Candidate(
            boolean isPlayer,
            boolean isHostileMob,
            boolean isDefenseUnit,
            UUID uuid,
            boolean memberOfOwningCity,
            boolean memberOfAlliedCity,
            boolean hasWarBypass,
            boolean creativeOrSpectator,
            boolean withinJoinGrace,
            boolean enemyInWarZone,
            double distance) {
    }

    /**
     * Everything the rule needs to know about the unit doing the looking.
     *
     * @param commissioned SPEC 27.8's 60-second window after a wartime placement has passed. An
     *                     uncommissioned unit is "before functioning" and targets nothing at all,
     *                     which is why it cancels above the hostile-mob branch rather than beside
     *                     the state check
     */
    public record Unit(UnitState state, UUID alertedTarget, double range, boolean commissioned,
                       boolean engagesDefenseUnits) {

        public Unit {
            Objects.requireNonNull(state, "state");
        }

        /** A unit past its commissioning window, which is every unit placed in peacetime. */
        public Unit(UnitState state, UUID alertedTarget, double range) {
            this(state, alertedTarget, range, true, false);
        }

        public Unit(UnitState state, UUID alertedTarget, double range, boolean commissioned) {
            this(state, alertedTarget, range, commissioned, false);
        }
    }

    /**
     * May this unit target this candidate?
     *
     * <p>Written in SPEC 30.1's order, top to bottom, so the code and the table can be read
     * against each other line by line.
     */
    public Decision decide(Unit unit, Candidate candidate) {
        // SPEC 26.4: "Units never fight units. Only players kill units." Checked first because
        // it holds in every state including HOSTILE, and because unit-versus-unit combat
        // produces unwatchable clumps of AI and lets wars resolve with no players present.
        if (candidate.isDefenseUnit()) {
            return breacherException(unit, candidate);
        }

        // SPEC 27.8: a unit placed during an ACTIVE war "enter[s] a 60-second inactive period
        // before functioning". Before functioning means before anything, so this sits above the
        // hostile-mob branch rather than beside the state check: a Colossus dropped into a melee
        // does not get to swing at a creeper either.
        if (!unit.commissioned()) {
            return Decision.cancel("COMMISSIONING");
        }

        // "if candidate is not a Player -> allow only if hostile mob and unit is PASSIVE"
        if (!candidate.isPlayer()) {
            return candidate.isHostileMob() && unit.state() == UnitState.PASSIVE
                    ? Decision.allow("HOSTILE_MOB")
                    : Decision.cancel("NOT_A_TARGETABLE_MOB");
        }

        // The five cancels that come before state. A member of the owning city is safe whatever
        // the unit is doing, which is the property the ordering exists to guarantee.
        if (candidate.memberOfOwningCity()) {
            return Decision.cancel("OWN_CITY");
        }
        if (candidate.memberOfAlliedCity()) {
            return Decision.cancel("ALLIED_CITY");
        }
        if (candidate.hasWarBypass()) {
            return Decision.cancel("BYPASS");
        }
        if (candidate.creativeOrSpectator()) {
            return Decision.cancel("CREATIVE_OR_SPECTATOR");
        }
        if (candidate.withinJoinGrace()) {
            // SPEC 26.4: five seconds after joining or respawning. A player who logs in inside
            // a city at war should not be killed before their screen has finished loading.
            return Decision.cancel("JOIN_GRACE");
        }

        // Now state.
        if (!unit.state().canTargetPlayers()) {
            // DORMANT and PASSIVE, which is SPEC 25.2's Rule 2: peacetime is safe, and a
            // visitor standing in front of a guard is ignored completely.
            return Decision.cancel("STATE_" + unit.state());
        }
        if (unit.state() == UnitState.ALERTED) {
            // "allow only if candidate == alerted target". SPEC 26.2 is explicit that alerting
            // is per player and never per group: a trespasser's companions standing peacefully
            // nearby are not attacked.
            if (unit.alertedTarget() == null
                    || !unit.alertedTarget().equals(candidate.uuid())) {
                return Decision.cancel("NOT_THE_ALERTED_TARGET");
            }
        } else if (!candidate.enemyInWarZone()) {
            // HOSTILE, which is war only and only against the other side.
            return Decision.cancel("NOT_AN_ENEMY");
        }

        if (candidate.distance() > unit.range()) {
            return Decision.cancel("OUT_OF_RANGE");
        }
        return Decision.allow("ALLOWED");
    }

    /**
     * SPEC 29.4's single exception to "units never fight units": the Breacher.
     *
     * <p>It lives here rather than in the siege package on purpose. SPEC 30.1 forbids
     * "unit-specific targeting logic anywhere else", and an exception written next to the code
     * that spawns Breachers would be exactly that — a second place that decides what may be
     * attacked, free to drift from this one. Written here, the prohibition and its one hole are
     * a single readable statement.
     *
     * <p>Every guard the general path applies still applies: an uncommissioned unit swings at
     * nothing, only a HOSTILE unit engages, only an enemy's garrison is a target (an attacker's
     * own guards are inside the war zone too, per SPEC 11.4, and a Breacher that ate them would
     * be a war-winning own goal), and range is still range.
     */
    private Decision breacherException(Unit unit, Candidate candidate) {
        if (!unit.engagesDefenseUnits()) {
            return Decision.cancel("UNITS_NEVER_FIGHT_UNITS");
        }
        if (!unit.commissioned()) {
            return Decision.cancel("COMMISSIONING");
        }
        if (unit.state() != UnitState.HOSTILE) {
            // A siege unit outside an ACTIVE war has nothing to break. The narrow reading is
            // also the safe one: it is the only state in which a war is being fought.
            return Decision.cancel("STATE_" + unit.state());
        }
        if (!candidate.enemyInWarZone()) {
            return Decision.cancel("NOT_AN_ENEMY_UNIT");
        }
        if (candidate.distance() > unit.range()) {
            return Decision.cancel("OUT_OF_RANGE");
        }
        return Decision.allow("BREACHER");
    }
}
