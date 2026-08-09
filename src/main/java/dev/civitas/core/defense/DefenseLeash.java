package dev.civitas.core.defense;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/**
 * SPEC 27.8's leash: a unit that wanders too far is put back.
 *
 * <h2>Measured from the chunk it was placed in, not from the city's border</h2>
 *
 * <p>This is a deliberate reversal, and the reasoning that was here before it argued the other
 * way, so it is worth stating plainly. Part I 12.3 leashed a unit to its city's claim set, on the
 * grounds that a guard should be free to cross its own city to reach a fight. SPEC 27.8 replaces
 * that: "A unit is bound to the chunk it is placed in. It may move up to
 * {@code defense.leash-blocks} (default 8) past that chunk's border, and is teleported back if it
 * exceeds it." SPEC 25 supersedes Part I Section 12 in full, so the tighter rule wins.
 *
 * <p>What it buys is that placement means something. Under the claim-set rule a two-hundred-chunk
 * city's garrison was one roaming mass and where each unit stood was decoration; under this one a
 * city has to decide which gate each guard is watching, which is what makes SPEC 27.8's
 * three-per-chunk cap and the Defense Capacity budget into a layout decision rather than a
 * shopping list.
 *
 * <h2>Distance only, never stuck</h2>
 *
 * <p>SPEC 30.2 case 93: a unit trapped in a hole by an attacker is an intended tactic. The leash
 * triggers on how far away a unit is and on nothing else, so digging a pit under a Colossus works
 * exactly as the attacker hoped.
 */
public final class DefenseLeash {

    private final DefenseRegistry registry;
    private final DefenseSpawner spawner;
    private final DefenseBehaviour behaviour;
    private final DefenseCatalogue catalogue;

    /** Consecutive failed teleports per unit, for SPEC 30.2 case 92. */
    private final Map<Integer, Integer> failures = new ConcurrentHashMap<>();

    public DefenseLeash(DefenseRegistry registry, DefenseSpawner spawner,
                        DefenseBehaviour behaviour, DefenseCatalogue catalogue) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.behaviour = Objects.requireNonNull(behaviour, "behaviour");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
    }

    /**
     * Puts every strayed unit back, and returns how many were moved.
     *
     * <p>On the server thread: it reads entity positions and teleports. Cheap when nothing has
     * strayed, which is the normal case, because the common path is one distance calculation per
     * live unit and most units never leave the chunk they were placed in.
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
     * <p>SPEC 30.2 case 92: "Teleported back to post. If teleport fails three times,
     * dematerialized and re-materialized at post." A teleport can be refused — an unloaded
     * destination chunk, a plugin cancelling it — and a unit that quietly failed to come home
     * would keep drifting while this reported success every tick.
     *
     * @return whether it was moved
     */
    public boolean returnHome(LivingEntity entity, DefenseUnit unit) {
        Location at = entity.getLocation();
        if (at.getWorld() == null) {
            return false;
        }
        if (!behaviour.shouldReturn(blocksOutsidePost(unit, at))) {
            failures.remove(unit.id());
            return false;
        }
        Optional<Location> home = unit.location();
        if (home.isEmpty() || home.get().getWorld() == null) {
            return false;
        }
        if (entity instanceof org.bukkit.entity.Mob mob) {
            mob.setTarget(null);
        }
        if (entity.teleport(home.get())) {
            failures.remove(unit.id());
            return true;
        }

        int failed = failures.merge(unit.id(), 1, Integer::sum);
        if (failed >= catalogue.leashTeleportFailures()) {
            failures.remove(unit.id());
            recovery.rebuild(unit);
        }
        return false;
    }

    /**
     * How far past its own chunk's border this position is, in blocks, or zero if it is inside.
     *
     * <p>Chebyshev distance to the placement chunk, converted to blocks, which is the same measure
     * SPEC 6.2 uses for claim distance. Measuring to the chunk rather than to the exact border is
     * off by at most a chunk and costs no lookups at all; against a leash of 8 blocks the
     * difference decides only whether a guard turns around at the fence or a few blocks past it.
     */
    public double blocksOutsidePost(DefenseUnit unit, Location at) {
        if (at.getWorld() == null || !at.getWorld().getName().equals(unit.world())) {
            // Nothing carries a unit into another world by wandering, so this is a teleport or a
            // portal, and there is no distance to report that means anything.
            return Double.MAX_VALUE;
        }
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;
        int chunks = Math.max(Math.abs(chunkX - unit.chunkX()),
                Math.abs(chunkZ - unit.chunkZ()));
        if (chunks == 0) {
            return 0;
        }
        // One chunk out is the adjacent chunk, whose near edge is where the post's chunk ends.
        return (chunks - 1) * 16.0 + edgeOffset(at);
    }

    /** How far into its own chunk the position sits, so a unit hugging the fence reads low. */
    private static double edgeOffset(Location at) {
        int withinX = Math.floorMod(at.getBlockX(), 16);
        int withinZ = Math.floorMod(at.getBlockZ(), 16);
        return Math.min(Math.min(withinX, 15 - withinX), Math.min(withinZ, 15 - withinZ));
    }

    // ==================================================================================
    // SPEC 30.2 case 92's last resort
    // ==================================================================================

    /** Taking a unit down and standing it back up at its post, when a teleport will not do. */
    @FunctionalInterface
    public interface Recovery {

        void rebuild(DefenseUnit unit);
    }

    private Recovery recovery = unit -> { };

    /** Wired to {@link UnitMaterializer}, which is the only thing that can do it. */
    public void useRecovery(Recovery how) {
        this.recovery = Objects.requireNonNull(how, "how");
    }

    /** How many consecutive failures each unit currently has, for the tests. */
    public int failureCount(int unitId) {
        return failures.getOrDefault(unitId, 0);
    }
}
