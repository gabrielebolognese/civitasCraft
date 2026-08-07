package dev.civitas.core.defense;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.civitas.core.claim.ClaimRegistry;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/**
 * SPEC 12.3's last row: a unit that wanders too far is put back.
 *
 * <h2>Why this only starts mattering now</h2>
 * M12 wrote {@link DefenseBehaviour#shouldReturn} and tested it, and nothing called it, which
 * was honest at the time: units had no reason to move. In peacetime a unit attacks only
 * hostile mobs inside its own claims, and the Sentry cannot move at all. It is war that gives
 * a guard something to chase, and a chase is what carries it over the border.
 *
 * <p>Without the leash, a defender's Siege Golem follows a retreating attacker into the
 * wilderness and is killed there, a long way from the city that paid 60,000 C for it. SPEC
 * 12.3 says the unit comes home instead.
 *
 * <h2>Measured from the border, not from where it was placed</h2>
 * The distance is to the nearest chunk its city owns, so a guard may cross its own city freely
 * and is only pulled back once it is genuinely outside. Placement position would leash a unit
 * to one chunk and stop it defending the gate ten blocks away.
 */
public final class DefenseLeash {

    private final DefenseRegistry registry;
    private final DefenseSpawner spawner;
    private final DefenseBehaviour behaviour;
    private final ClaimRegistry claims;

    public DefenseLeash(DefenseRegistry registry, DefenseSpawner spawner,
                        DefenseBehaviour behaviour, ClaimRegistry claims) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.behaviour = Objects.requireNonNull(behaviour, "behaviour");
        this.claims = Objects.requireNonNull(claims, "claims");
    }

    /**
     * Puts every strayed unit back, and returns how many were moved.
     *
     * <p>On the server thread: it reads entity positions and teleports. Cheap when nothing has
     * strayed, which is the normal case, because the common path is one distance calculation
     * per live unit and most units never leave the chunk they were placed in.
     */
    public int tick(List<Entity> candidates) {
        int moved = 0;
        for (Entity entity : candidates) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            Optional<Integer> unitId = spawner.unitIdOf(living);
            if (unitId.isEmpty()) {
                continue;
            }
            Optional<DefenseUnit> unit = registry.byId(unitId.get());
            if (unit.isEmpty()) {
                continue;
            }
            if (returnHome(living, unit.get())) {
                moved++;
            }
        }
        return moved;
    }

    /**
     * Sends one unit home if it has strayed.
     *
     * @return whether it was moved
     */
    public boolean returnHome(LivingEntity entity, DefenseUnit unit) {
        Location at = entity.getLocation();
        if (at.getWorld() == null) {
            return false;
        }
        double outside = blocksOutsideClaims(unit.cityId(), at);
        if (!behaviour.shouldReturn(outside)) {
            return false;
        }
        Optional<Location> home = unit.location();
        if (home.isEmpty() || home.get().getWorld() == null) {
            return false;
        }
        if (entity instanceof org.bukkit.entity.Mob mob) {
            mob.setTarget(null);
        }
        entity.teleport(home.get());
        return true;
    }

    /**
     * How far past its city's land this position is, in blocks, or zero if it is inside.
     *
     * <p>Chebyshev distance to the nearest owned chunk, converted to blocks, which is the same
     * measure SPEC 6.2 uses for claim distance. Measuring to the chunk rather than to the
     * exact border is off by at most a chunk and costs one pass over the city's claims instead
     * of a geometric edge test; against a leash of 8 blocks the difference decides only
     * whether a guard turns around at the fence or a few blocks past it.
     */
    public double blocksOutsideClaims(int cityId, Location at) {
        String world = at.getWorld().getName();
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;

        if (claims.at(world, chunkX, chunkZ)
                .filter(claim -> claim.cityId() == cityId).isPresent()) {
            return 0;
        }

        int nearest = Integer.MAX_VALUE;
        for (var claim : claims.claimsOf(cityId)) {
            if (!claim.world().equals(world)) {
                continue;
            }
            int distance = Math.max(Math.abs(claim.chunkX() - chunkX),
                    Math.abs(claim.chunkZ() - chunkZ));
            nearest = Math.min(nearest, distance);
            if (nearest <= 1) {
                break;
            }
        }
        if (nearest == Integer.MAX_VALUE) {
            // Its city holds nothing in this world at all, so it is as far out as it gets.
            return Double.MAX_VALUE;
        }
        // One chunk out is the adjacent chunk, whose near edge is where the claim ends.
        return (nearest - 1) * 16.0 + edgeOffset(at);
    }

    /** How far into its own chunk the position sits, so a unit hugging the fence reads low. */
    private static double edgeOffset(Location at) {
        int withinX = Math.floorMod(at.getBlockX(), 16);
        int withinZ = Math.floorMod(at.getBlockZ(), 16);
        return Math.min(Math.min(withinX, 15 - withinX), Math.min(withinZ, 15 - withinZ));
    }
}
