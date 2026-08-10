package dev.civitas.core.siege;

import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.defense.DefenseCapacity;

/**
 * How much siege an attacker may field, SPEC 29.2.
 *
 * <pre>
 * Siege Capacity = round(defender_defense_capacity * 0.70)
 * </pre>
 *
 * <h2>This is the anti-fortress mechanism, not a balance knob</h2>
 *
 * <p>SPEC 29.2 states what the ratio is for: "This is self-balancing and is the mechanism that
 * structurally prevents an unattackable fortress: <b>the more a city fortifies, the more siege
 * its attacker is permitted to field.</b> A city cannot outbuild the counter, it can only make
 * the war more expensive for both sides."
 *
 * <p>So the budget is derived from the <em>defender's</em> capacity rather than the attacker's
 * wealth or size. A city at Fortification 5 has 225 points of garrison and hands its attacker
 * 157 points of siege; a city that never upgraded has 100 and hands over 70. Fortifying is still
 * worth doing — the attacker's 70% never catches the defender's 100% — but it can never put a
 * city out of reach.
 *
 * <h2>Frozen at declaration</h2>
 *
 * <p>SPEC 29.2: "Siege Capacity is computed once, at war declaration, and frozen." A defender who
 * could raise it mid-war by buying a Fortification level would be handing their attacker a larger
 * army, and one who could lower it by selling would be shrinking the attack after it was planned.
 * Neither is a decision a war should contain, so the figure is stored on the war and never
 * recomputed.
 *
 * <h2>Allies share it</h2>
 *
 * <p>"Allies joining an attack share the attacker's budget rather than adding their own." Three
 * cities attacking together field the same siege as one, which is what stops a coalition simply
 * multiplying the counter until no fortification means anything.
 */
public final class SiegeCapacity {

    private final ConfigManager configs;
    private final DefenseCapacity defense;

    public SiegeCapacity(ConfigManager configs, DefenseCapacity defense) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.defense = Objects.requireNonNull(defense, "defense");
    }

    /**
     * What an attacker gets against a defender at this Fortification level.
     *
     * <h3>SPEC 29.2's formula and its own table disagree, and the table wins</h3>
     *
     * <p>The formula says {@code round(defender_capacity * 0.70)}. At Fortification 5 that is
     * {@code round(225 * 0.70)} — and 225 x 0.70 is exactly 157.5 in double, which rounds to
     * <b>158</b> under the usual half-up convention. SPEC 29.2's published table says
     * <b>157</b>.
     *
     * <p>Settled the same way SPEC 39.3's ambiguous {@code n} was: against the published
     * figures rather than by argument, because the table is what a player is promised and the
     * formula is a description of it. Truncation matches all three rows SPEC prints (70, 105,
     * 157); half-up matches two of the three. It is also the conservative direction — an
     * attacker never gets more siege than the exact share.
     */
    public int against(int defenderFortificationLevel) {
        return forDefenderCapacity(defense.capacityAt(defenderFortificationLevel));
    }

    /** The same from a capacity figure already in hand, for a war that stored one. */
    public int forDefenderCapacity(int defenderCapacity) {
        return java.math.BigDecimal.valueOf(Math.max(0, defenderCapacity))
                .multiply(java.math.BigDecimal.valueOf(budgetRatio()))
                .setScale(0, java.math.RoundingMode.DOWN)
                .intValue();
    }

    /**
     * Whether one more unit fits.
     *
     * <p>Delegates to {@link DefenseCapacity#fits} rather than repeating the arithmetic: a
     * budget that answered "does this fit" differently from the defensive one would be two
     * rules, and the difference would only ever show up at the boundary.
     */
    public boolean fits(int spent, int cost, int capacity) {
        return DefenseCapacity.fits(spent, cost, capacity);
    }

    public double budgetRatio() {
        return configs.get(ConfigFile.DEFENSE).getDouble("siege.budget-ratio", 0.70);
    }

    /** SPEC 29.5: how far from the enemy city a camp may be planted. */
    public int maxCampDistanceChunks() {
        return configs.get(ConfigFile.DEFENSE).getInt("siege.max-camp-distance-chunks", 12);
    }

    public int campHealth() {
        return configs.get(ConfigFile.DEFENSE).getInt("siege.camp-health", 200);
    }

    /** SPEC 29.5: what the defender scores for destroying a camp. */
    public int campDestroyPoints() {
        return configs.get(ConfigFile.DEFENSE).getInt("siege.camp-destroy-points", 40);
    }

    /** "It can be rebuilt once per war at half cost." */
    public int campRebuildCostPercent() {
        return configs.get(ConfigFile.DEFENSE).getInt("siege.camp-rebuild-cost-percent", 50);
    }
}
