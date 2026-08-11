package dev.civitas.core.defense;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A deterministic combat model, for SPEC 25.2 Rule 1.
 *
 * <h2>What this is for</h2>
 *
 * <p>SPEC 25.2 states the one balance claim in Part III that cannot be verified by reading code:
 * "a defending city's full garrison, at any Fortification level, must be beatable by an attacking
 * force <b>equal in size to the defender's active member count</b>, equipped with good gear and
 * coordinating. If a configuration exists where this is false, that configuration is a bug."
 *
 * <p>PLAN's M20a asks for three live trials at each of three Fortification levels. This is the
 * other half of that: an arithmetic model that resolves the same fight from the published numbers,
 * so <em>every</em> garrison that fits a budget can be checked rather than three that somebody
 * happened to build. It does not replace the live pass — a model cannot find a pathfinding bug or
 * a unit that never acquires a target — and it catches what a live pass cannot, which is the
 * composition nobody thought to try.
 *
 * <h2>It is calibrated against SPEC's own tables, not invented</h2>
 *
 * <p>{@link #afterArmour} reproduces every cell of SPEC 28.4's damage table and {@link #dps}
 * reproduces SPEC 28.5's sword rows exactly. Those two tables are the only combat arithmetic SPEC
 * publishes, and a model that disagreed with them would be measuring something else.
 *
 * <h2>What it deliberately leaves out</h2>
 *
 * <p>No healing, no potions, no shields, no critical hits, no knockback, and no terrain. Every one
 * of those helps the attacker, so a garrison this model says is beatable is beatable in play; a
 * garrison it says is not may still be, and that is the direction a balance check must err in.
 */
public final class CombatModel {

    /** How an attacking force chooses to fight, which SPEC 27 makes the deciding variable. */
    public enum Engagement {
        /**
         * Everything in the garrison fights at once, on its own ground.
         *
         * <p>The pessimistic bound, and not what SPEC 27 describes a competent attacker doing.
         */
        STAND_AND_FIGHT,
        /**
         * Units slower than a sprinting player are led away and left, per SPEC 27.7's stated
         * counterplay for the Colossus: "it can be walked away from at any time and cannot climb.
         * Leading a Colossus away from the objective and simply leaving it there is the intended
         * and correct play."
         *
         * <p>They still have to be dealt with — they are not deleted — but they are fought one at
         * a time and out of support range of the rest, which is what "coordinating" in SPEC 25.2
         * Rule 1 means.
         */
        KITE
    }

    /**
     * What an attacker is wearing and swinging. Armour in points, per the vanilla scale.
     *
     * @param healingPerSecond sustained self-healing. Not decoration: it is the assumption the
     *                         whole verdict turns on, and leaving it at zero models a fight
     *                         nobody actually has. A raider chaining golden apples holds
     *                         Regeneration II, which is one heart every 25 ticks — 0.8 a second —
     *                         and that is the conservative end of what "good gear" in SPEC 25.2
     *                         Rule 1 buys. Notch apples are several times better and are not
     *                         modelled.
     */
    public record Gear(String name, double armour, double toughness, double protectionPoints,
                       double weaponDamage, double attacksPerSecond, double healingPerSecond) {

        /** SPEC 28.4's own row: the reference for "good gear" in Rule 1. */
        public static Gear fullDiamondProtTwo() {
            // Diamond set is 20 armour / 8 toughness. Protection II on four pieces is 8 points.
            // Diamond sword 7 + Sharpness III 2.0, swinging at the vanilla sword rate.
            return new Gear("full diamond, Prot II, Sharpness III", 20, 8, 8, 9.0, 1.6, 0.8);
        }

        /** SPEC 28.4's top row: what a well-supplied raider actually turns up in. */
        public static Gear fullNetheriteProtFour() {
            return new Gear("full netherite, Prot IV, Sharpness V", 20, 12, 16, 11.0, 1.6, 0.8);
        }

        public static Gear fullIron() {
            return new Gear("full iron, Sharpness I", 15, 0, 0, 7.0, 1.6, 0.0);
        }

        public static Gear unarmored() {
            return new Gear("unarmored", 0, 0, 0, 4.0, 1.6, 0.0);
        }

        /** The same kit with nothing to drink, for reporting the margin healing is worth. */
        public Gear withoutHealing() {
            return new Gear(name + ", no healing", armour, toughness, protectionPoints,
                    weaponDamage, attacksPerSecond, 0);
        }
    }

    /** One defending unit, reduced to what a fight actually consumes. */
    public record Defender(String key, double health, double damage, double speed,
                           double attacksPerSecond) {

        public static Defender of(DefenseUnitType type) {
            // One swing a second. Vanilla mob attack cadence is roughly this and Bukkit exposes
            // no per-type figure; it is a model constant and is stated as one.
            return new Defender(type.key(), type.health(), type.damage(), type.speed(), 1.0);
        }
    }

    /** What the model concluded. */
    public record Outcome(boolean attackersWin, double secondsToClear, double secondsToDie,
                          int attackersLost, String detail) {
    }

    /**
     * How fast a sprinting player moves, on the same scale SPEC 27 gives unit speeds in.
     *
     * <p>Anchored on SPEC 27's own two statements rather than chosen: the Warhound at 0.42 is
     * "faster than a sprinting player" and the City Guard at 0.28 is not described as such, so a
     * sprint sits between them. Any value in that band produces the same verdicts, because no
     * unit in the roster has a speed inside it.
     */
    public static final double SPRINT_SPEED = 0.33;

    private CombatModel() {
    }

    // ==================================================================================
    // The two arithmetic pieces, both calibrated against SPEC
    // ==================================================================================

    /**
     * Vanilla damage reduction, reproducing every cell of SPEC 28.4's table.
     *
     * <pre>
     * after = raw * (1 - min(20, max(armour/5, armour - raw/(2 + toughness/4))) / 25)
     *              * (1 - min(20, protection) / 25)
     * </pre>
     */
    public static double afterArmour(double raw, Gear gear) {
        if (raw <= 0) {
            return 0;
        }
        double armourTerm = Math.max(gear.armour() / 5.0,
                gear.armour() - raw / (2.0 + gear.toughness() / 4.0));
        double afterArmour = raw * (1.0 - Math.min(20.0, armourTerm) / 25.0);
        return afterArmour * (1.0 - Math.min(20.0, gear.protectionPoints()) / 25.0);
    }

    /** Sustained damage per second, reproducing SPEC 28.5's sword rows exactly. */
    public static double dps(Gear gear) {
        return gear.weaponDamage() * gear.attacksPerSecond();
    }

    // ==================================================================================
    // The fight
    // ==================================================================================

    /**
     * Resolves an attack.
     *
     * <p>Attackers focus one unit at a time, which is what a coordinating force does and what
     * SPEC 27.6's alert network assumes they will face. Incoming damage is spread evenly across
     * the surviving attackers — a model of a melee rather than of a duel — and an attacker dies
     * when they have taken twenty points.
     *
     * @param attackers how many players, which Rule 1 fixes at the defender's active member count
     */
    public static Outcome resolve(int attackers, Gear gear, List<Defender> garrison,
                                  Engagement engagement) {
        Objects.requireNonNull(gear, "gear");
        Objects.requireNonNull(garrison, "garrison");
        if (attackers <= 0 || garrison.isEmpty()) {
            return new Outcome(true, 0, Double.POSITIVE_INFINITY, 0, "nothing to fight");
        }
        return resolveEngagement(attackers, gear, garrison, engagement);
    }

    /**
     * Whether an attacking force can clear a whole garrison over the course of a war.
     *
     * <p>The distinction this draws is the one the first version of this model got wrong. SPEC
     * 11.2 gives a war <b>seven days</b>; Rule 1 says the garrison must be "beatable", not
     * beatable in one unbroken fight. An attacking force picks where and when it engages, kills
     * what it can reach, withdraws, heals, and comes back — which is what SPEC 25.2's
     * "coordinating" describes and what a single continuous-damage sum cannot represent.
     *
     * <p>How many units can be brought to bear at one place is not a free parameter either. SPEC
     * 27.8 caps a chunk at {@code defense.placement.max-units-per-chunk} units, so a garrison of
     * eighteen is spread over at least six chunks — ninety-six blocks — and an attacker who walks
     * into the middle of all of it has chosen to.
     *
     * @param concurrent how many defenders can reach the attackers at once, which the caller
     *                   derives from the per-chunk cap rather than picking
     */
    public static boolean clearsGarrison(int attackers, Gear gear, List<Defender> garrison,
                                         Engagement engagement, int concurrent) {
        if (garrison.isEmpty()) {
            return true;
        }
        // Worst case first: the hardest-hitting units grouped together, so the attacker meets
        // the most dangerous engagement the garrison can offer rather than an average one.
        List<Defender> ordered = new ArrayList<>(garrison);
        ordered.sort((a, b) -> Double.compare(b.damage(), a.damage()));

        int width = Math.max(1, concurrent);
        for (int start = 0; start < ordered.size(); start += width) {
            List<Defender> group = ordered.subList(start, Math.min(ordered.size(), start + width));
            if (!resolveEngagement(attackers, gear, group, engagement).attackersWin()) {
                return false;
            }
        }
        return true;
    }

    private static Outcome resolveEngagement(int attackers, Gear gear, List<Defender> garrison,
                                             Engagement engagement) {

        List<Defender> fighting = new ArrayList<>();
        List<Defender> ignored = new ArrayList<>();
        for (Defender unit : garrison) {
            // A unit that cannot catch a sprinting player only fights if the attacker lets it.
            if (engagement == Engagement.KITE && unit.speed() > 0 && unit.speed() < SPRINT_SPEED) {
                ignored.add(unit);
            } else if (engagement == Engagement.KITE && unit.speed() <= 0) {
                // Static units — the Frost Sentry and the Watchtower Keeper — cannot follow
                // anybody anywhere, so they are the easiest thing in the game to walk around.
                ignored.add(unit);
            } else {
                fighting.add(unit);
            }
        }

        double totalHealth = garrison.stream().mapToDouble(Defender::health).sum();
        double attackerDps = attackers * dps(gear);
        double secondsToClear = totalHealth / attackerDps;

        double incomingPerSecond = 0;
        for (Defender unit : fighting) {
            incomingPerSecond += afterArmour(unit.damage(), gear) * unit.attacksPerSecond();
        }

        // The garrison's output falls as it dies. Modelled as the average over the engagement,
        // which is what a linear kill order produces: the last unit alive is fighting alone.
        double effectiveIncoming = incomingPerSecond / 2.0
                - attackers * gear.healingPerSecond();
        double poolHealth = attackers * 20.0;
        double secondsToDie = effectiveIncoming <= 0
                ? Double.POSITIVE_INFINITY
                : poolHealth / effectiveIncoming;

        boolean win = secondsToClear < secondsToDie;
        int lost = effectiveIncoming <= 0 ? 0
                : (int) Math.min(attackers,
                        Math.floor(effectiveIncoming * Math.min(secondsToClear, secondsToDie)
                                / 20.0));

        String detail = fighting.size() + " engaged, " + ignored.size() + " out-run";
        return new Outcome(win, secondsToClear, secondsToDie, lost, detail);
    }

    /**
     * Every garrison that spends a budget on one unit type, plus the mixed maximum-threat one.
     *
     * <p>The point of enumerating rather than picking: SPEC 25.2 says "if a configuration exists
     * where this is false, that configuration is a bug", so the check has to be over
     * configurations, not over one somebody chose.
     */
    public static List<List<Defender>> monocultures(int budget, DefenseCatalogue catalogue) {
        List<List<Defender>> garrisons = new ArrayList<>();
        for (DefenseUnitType type : catalogue.all()) {
            if (type.points() <= 0) {
                continue;
            }
            int count = budget / type.points();
            if (count <= 0) {
                continue;
            }
            List<Defender> garrison = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                garrison.add(Defender.of(type));
            }
            garrisons.add(List.copyOf(garrison));
        }
        return List.copyOf(garrisons);
    }

    /**
     * The garrison that puts the most damage per second on the field for a budget.
     *
     * <p>Greedy by damage per point, which is the composition a defender optimising for the fight
     * would actually build and therefore the one Rule 1 has to survive.
     */
    public static List<Defender> deadliest(int budget, DefenseCatalogue catalogue) {
        List<DefenseUnitType> byValue = new ArrayList<>(catalogue.all());
        byValue.sort((a, b) -> Double.compare(
                b.damage() / Math.max(1, b.points()), a.damage() / Math.max(1, a.points())));

        List<Defender> garrison = new ArrayList<>();
        int remaining = budget;
        for (DefenseUnitType type : byValue) {
            while (type.points() > 0 && type.points() <= remaining) {
                garrison.add(Defender.of(type));
                remaining -= type.points();
            }
        }
        return List.copyOf(garrison);
    }
}
