package dev.civitas.core.siege;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.civitas.core.defense.DefenseSpawner;
import dev.civitas.core.defense.TargetingRule;
import dev.civitas.core.defense.TargetingRule.Candidate;
import dev.civitas.core.defense.TargetingRule.Decision;
import dev.civitas.core.defense.UnitState;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Resolves a siege unit's target question into {@link TargetingRule}, SPEC 29.4 and SPEC 30.1.
 *
 * <h2>One rule, two resolvers</h2>
 *
 * <p>SPEC 30.1 requires "exactly one such handler and no unit-specific targeting logic anywhere
 * else". This class contains no decision: it turns a Bukkit entity into the facts the one rule
 * asks for, exactly as {@code UnitTargeting} does for defense units, and the rule decides. A
 * second decision table is what SPEC forbids; a second lookup is unavoidable, because a siege
 * unit is not in {@code defense_units} and has no city that owns the ground it stands on.
 *
 * <h2>A siege unit is only ever HOSTILE</h2>
 *
 * <p>It has no peacetime. It is bought inside a war, it never leaves the war zone, and it is
 * despawned when the war ends — so there is no state for it to be in but the fighting one. That
 * is also what makes the Breacher's carve-out safe: {@link TargetingRule} allows it to engage a
 * garrison only in HOSTILE, and nothing else in the plugin can put a unit there outside a war.
 */
public final class SiegeTargeting {

    private final TargetingRule rule = new TargetingRule();
    private final SiegeSpawner spawner;
    private final DefenseSpawner defenseUnits;
    private final SiegeCatalogue catalogue;

    public SiegeTargeting(SiegeSpawner spawner, DefenseSpawner defenseUnits,
                          SiegeCatalogue catalogue) {
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.defenseUnits = Objects.requireNonNull(defenseUnits, "defenseUnits");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
    }

    /**
     * May this siege unit target this entity?
     *
     * @return empty when the attacker is not siege, so the caller leaves the event alone
     */
    public Optional<Decision> decide(Entity attacker, LivingEntity target) {
        if (!spawner.isSiegeUnit(attacker) || target == null) {
            return Optional.empty();
        }
        Optional<SiegeUnitType> type = spawner.typeKeyOf(attacker).flatMap(catalogue::byKey);
        if (type.isEmpty()) {
            // Tagged as siege but the roster no longer has the entry: an operator removed it
            // mid-war. Cancel rather than allow, for the same reason UnitTargeting cancels a
            // unit with no row — a mob nothing owns should not be hunting anybody.
            return Optional.of(new Decision(false, "NO_SUCH_SIEGE_TYPE"));
        }
        Optional<Integer> warId = spawner.warIdOf(attacker);
        Optional<Integer> ownerCity = spawner.cityIdOf(attacker);
        if (warId.isEmpty() || ownerCity.isEmpty()) {
            return Optional.of(new Decision(false, "UNTAGGED_SIEGE_UNIT"));
        }

        SiegeUnitType siege = type.get();
        // A support unit deals no damage; letting it acquire a target only produces a Banner
        // Bearer chasing somebody it cannot hurt.
        UnitState state = siege.isSupport() ? UnitState.PASSIVE : stateOf(warId.get());

        return Optional.of(rule.decide(
                new TargetingRule.Unit(state, null, siege.range(), true,
                        siege.engagesDefenseUnits()),
                candidateOf(warId.get(), ownerCity.get(), attacker, target)));
    }

    private UnitState stateOf(int warId) {
        return wars != null && wars.isActive(warId) ? UnitState.HOSTILE : UnitState.PASSIVE;
    }

    private Candidate candidateOf(int warId, int ownerCity, Entity attacker, LivingEntity target) {
        boolean targetIsDefenseUnit = defenseUnits.isDefenseUnit(target);
        double distance = distance(attacker, target);

        if (targetIsDefenseUnit) {
            // For a garrison mob the only question the rule asks is whether it belongs to the
            // other side. Reusing enemyInWarZone rather than adding a field keeps the meaning
            // the same in both resolvers: "on the far side of this war, inside its zone".
            boolean enemyGarrison = wars != null
                    && wars.isEnemyUnit(warId, ownerCity, target);
            return new Candidate(false, false, true, target.getUniqueId(),
                    false, false, false, false, false, enemyGarrison, distance);
        }

        if (!(target instanceof Player player)) {
            // SPEC 29.4: siege targets "Enemy players and enemy defense units". Not wildlife,
            // and not hostile mobs either — an attacker's Ravager is not a pest controller.
            return new Candidate(false, false, false, target.getUniqueId(),
                    false, false, false, false, false, false, distance);
        }

        UUID uuid = player.getUniqueId();
        boolean sameSide = wars != null && wars.isOnSameSide(warId, ownerCity, uuid);
        return new Candidate(true, false, false, uuid,
                sameSide,
                false,
                player.hasPermission("civitas.bypass.war"),
                player.getGameMode() == GameMode.CREATIVE
                        || player.getGameMode() == GameMode.SPECTATOR,
                false,
                wars != null && wars.isEnemyPlayer(warId, ownerCity, uuid),
                distance);
    }

    private static double distance(Entity attacker, Entity target) {
        if (attacker.getWorld() != target.getWorld()) {
            return Double.MAX_VALUE;
        }
        return attacker.getLocation().distance(target.getLocation());
    }

    // ==================================================================================
    // The war seam
    // ==================================================================================

    private Wars wars;

    /**
     * What a siege unit needs to know about its war.
     *
     * <p>Every method answers conservatively when unwired: no war is active, nobody is an enemy,
     * so an unwired resolver produces siege units that attack nothing. The other direction would
     * be mobs attacking bystanders in peacetime.
     */
    public interface Wars {

        boolean isActive(int warId);

        boolean isOnSameSide(int warId, int ownerCityId, UUID player);

        boolean isEnemyPlayer(int warId, int ownerCityId, UUID player);

        boolean isEnemyUnit(int warId, int ownerCityId, Entity defenseUnit);
    }

    public void useWars(Wars registry) {
        this.wars = Objects.requireNonNull(registry, "registry");
    }
}
