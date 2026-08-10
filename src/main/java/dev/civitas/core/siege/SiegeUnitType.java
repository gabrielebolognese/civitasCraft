package dev.civitas.core.siege;

import java.math.BigDecimal;
import java.util.Objects;

import org.bukkit.entity.EntityType;

/**
 * One entry in SPEC 29.3's siege roster.
 *
 * <p>Four units against the defender's six, deliberately. SPEC 29.3: "Deliberately fewer than the
 * defensive roster, because the attacker's real advantages are choosing the timing, having full
 * player mobility, and being able to retreat."
 *
 * @param damageVsUnits   SPEC 29.3's key balance lever. The Breacher deals 2x here and 0.6x to
 *                        players, because it "exists to break a garrison, not to kill players" —
 *                        it makes the attacker's mob budget a tool against the defender's mob
 *                        budget without turning wars into mob-versus-mob spectacles.
 * @param damageVsPlayers the other half of that lever
 * @param buffRadius      the Banner Bearer's only function; it deals no damage at all
 */
public record SiegeUnitType(
        String key,
        String displayName,
        EntityType mob,
        double health,
        double damage,
        int points,
        BigDecimal cost,
        double range,
        double damageVsUnits,
        double damageVsPlayers,
        int buffRadius) {

    public SiegeUnitType {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(cost, "cost");

        if (health <= 0) {
            throw new IllegalArgumentException("health must be positive for " + key);
        }
        if (points <= 0) {
            throw new IllegalArgumentException("points must be positive for " + key);
        }
        if (cost.signum() < 0) {
            throw new IllegalArgumentException("cost cannot be negative for " + key);
        }
    }

    /**
     * Whether this unit may attack a defense unit, SPEC 29.4.
     *
     * <p>"Only the Breacher engages defense units directly. Others prioritise players." This is
     * the single carve-out from SPEC 26.4's "units never fight units", and it is narrow on
     * purpose: one unit type, so a siege never becomes two mob armies resolving a war with no
     * players present.
     */
    public boolean engagesDefenseUnits() {
        return damageVsUnits > 1.0;
    }

    /** A support unit deals no damage; the Banner Bearer is the only one. */
    public boolean isSupport() {
        return damage <= 0 && buffRadius > 0;
    }

    /** What this unit actually does to a target, applying SPEC 29.3's asymmetry. */
    public double damageAgainst(boolean defenseUnit) {
        return damage * (defenseUnit ? damageVsUnits : damageVsPlayers);
    }
}
