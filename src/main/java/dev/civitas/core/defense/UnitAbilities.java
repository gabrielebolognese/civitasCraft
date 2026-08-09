package dev.civitas.core.defense;

import dev.civitas.core.defense.DefenseUnitType.Ability;

/**
 * The arithmetic behind SPEC 27.2 to 27.7's abilities. Pure, and every branch has a test.
 *
 * <p>Separated from the listener that fires them for one reason: two of these numbers are the
 * whole of a unit's stated counterplay, and SPEC 25.2 Rule 3 makes counterplay a shipping gate
 * — "a unit without a written counterplay does not ship". The Colossus's arrow resistance is a
 * <b>threshold</b> and not a scale, and reading it as a scale removes the only ranged answer to
 * the tank while looking entirely reasonable in code. It is asserted here, in isolation, rather
 * than inferred from a live fight.
 *
 * <p>Nothing here builds a {@code PotionEffectType}: those resolve through a registry and would
 * drag a server into a pure class. Levels come out as amplifiers and seconds as ticks, and the
 * caller with a server constructs the effect.
 */
public final class UnitAbilities {

    private UnitAbilities() {
    }

    // ==================================================================================
    // SPEC 27.7, the Colossus
    // ==================================================================================

    /**
     * What an arrow actually lands for, SPEC 27.7.
     *
     * <p>"Arrows dealing under 8 damage are reduced by 80%." Under, not up to: a 7.9 arrow
     * lands 1.58 and an 8.0 arrow lands 8.0, and the discontinuity is deliberate. SPEC 27.7's
     * counterplay says so in as many words — the resistance is "explicitly capped at 8 damage
     * so a fully charged Power V bow still hurts it". Reduce everything by 80% instead and the
     * Colossus has no ranged counter at all, which is a Rule 1 failure the numbers hide.
     */
    public static double arrowDamageAfterResist(DefenseUnitType type, double raw) {
        if (!type.hasAbility(Ability.ARROW_RESIST_THRESHOLD)) {
            return raw;
        }
        double threshold = type.ability(Ability.ARROW_RESIST_THRESHOLD, 0);
        if (raw >= threshold) {
            return raw;
        }
        double percent = type.ability(Ability.ARROW_RESIST_PERCENT, 0);
        return raw * (1 - clampPercent(percent) / 100.0);
    }

    /** Whether a bystander is close enough to the impact to be caught by the slam. */
    public static boolean withinSlam(DefenseUnitType type, double distance) {
        return type.hasAbility(Ability.SLAM_RADIUS)
                && distance <= type.ability(Ability.SLAM_RADIUS, 0);
    }

    public static double slamDamage(DefenseUnitType type) {
        return type.ability(Ability.SLAM_DAMAGE, 0);
    }

    public static double slamKnockback(DefenseUnitType type) {
        return type.ability(Ability.SLAM_KNOCKBACK, 0);
    }

    // ==================================================================================
    // SPEC 27.5, the Archer
    // ==================================================================================

    /**
     * How fast an archer may fire with an enemy this close, SPEC 27.5.
     *
     * <p>"Fire rate halves while any enemy is within 5 blocks." Expressed as a multiplier on
     * the delay rather than on the rate, so the caller multiplies a cooldown by it: a unit that
     * is weak in close quarters is the counterplay, so the number that grows is the wait.
     */
    public static double fireDelayMultiplier(DefenseUnitType type, double nearestEnemy) {
        if (!type.hasAbility(Ability.MELEE_FIRERATE_PENALTY)) {
            return 1.0;
        }
        double radius = type.ability(Ability.MELEE_FIRERATE_RADIUS, 0);
        if (nearestEnemy > radius) {
            return 1.0;
        }
        double penalty = type.ability(Ability.MELEE_FIRERATE_PENALTY, 1.0);
        // A penalty of 0.5 means half the fire rate, which is twice the delay.
        return penalty <= 0 ? 1.0 : 1.0 / penalty;
    }

    // ==================================================================================
    // SPEC 27.6, the City Guard
    // ==================================================================================

    /**
     * How far the alert network reaches, in blocks.
     *
     * <p>SPEC 27.6 states it in chunks — "every City Guard within 3 chunks" — and every distance
     * a listener has is in blocks.
     */
    public static double alertNetworkRadiusBlocks(DefenseUnitType type) {
        return type.ability(Ability.ALERT_NETWORK_CHUNKS, 0) * 16.0;
    }

    public static long alertNetworkMillis(DefenseUnitType type) {
        return (long) (type.ability(Ability.ALERT_NETWORK_SECONDS, 0) * 1000.0);
    }

    public static boolean hasAlertNetwork(DefenseUnitType type) {
        return type.hasAbility(Ability.ALERT_NETWORK_CHUNKS)
                && type.ability(Ability.ALERT_NETWORK_SECONDS, 0) > 0;
    }

    // ==================================================================================
    // Effects, SPEC 27.2 and 27.4
    // ==================================================================================

    /**
     * SPEC 30.3 writes potion strengths as <b>levels</b> and Bukkit takes an amplifier.
     *
     * <p>Slowness II is amplifier 1. The conversion happens once, here, rather than at each of
     * the four call sites — two vocabularies for one number, silently off by one, is how the
     * superseded catalogue ended up with {@code amplifier: 1} meaning the same thing SPEC calls
     * level 2.
     */
    public static int amplifierOf(DefenseUnitType type, Ability level) {
        return Math.max(0, (int) Math.round(type.ability(level, 1)) - 1);
    }

    public static int ticksOf(DefenseUnitType type, Ability seconds) {
        return Math.max(0, (int) Math.round(type.ability(seconds, 0) * 20));
    }

    /** Whether this unit's projectile debuffs rather than hurting, which is the Frost Sentry. */
    public static boolean isFrostProjectile(DefenseUnitType type) {
        return type.hasAbility(Ability.SLOWNESS_LEVEL)
                || type.hasAbility(Ability.MINING_FATIGUE_LEVEL);
    }

    /** Whether this unit's bite debuffs, which is the Warhound. */
    public static boolean bites(DefenseUnitType type) {
        return type.hasAbility(Ability.BITE_SLOWNESS_LEVEL);
    }

    private static double clampPercent(double percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
