package dev.civitas.core.outpost;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.travel.TeleportService;
import dev.civitas.core.travel.TravelKind;
import dev.civitas.util.Result;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Travelling to an outpost, SPEC 39.5 and 7.4.
 *
 * <p>Policy only. Warmup, cooldown, cancellation and the fare are {@link TeleportService}'s,
 * which is the whole point of this class existing in place of the 311-line {@code
 * OutpostTeleport} it replaces: SPEC 32.7's "all teleports are cancelled by movement or damage
 * during warmup, and all are blocked while combat tagged" is one rule, and it had been written
 * three times. What is genuinely particular to an outpost is only what is here — who may go
 * (SPEC 39.2's city ranks), when nobody may (SPEC 7.4's war block), where they land (SPEC 7.4's
 * safe fallback), and what it costs, which under SPEC 39.5 depends on how far out the outpost is.
 *
 * <h2>The fare is charged on arrival</h2>
 *
 * <p>A change from {@code OutpostTeleport}, which charged first and travelled only if the charge
 * succeeded. {@link TeleportService} travels first and charges after, and that policy now covers
 * outposts too. Its reasoning applies here unchanged: teleporting a player and then teleporting
 * them back because their balance moved during an eight-second warmup is a worse outcome than a
 * fare the server occasionally fails to collect, and the affordability check before the warmup
 * keeps the window narrow. Recorded in {@code OPEN_QUESTIONS.md}, because it is a change to
 * shipped behaviour rather than new code.
 */
public final class OutpostTravel {

    private final OutpostService outposts;
    private final TeleportService travel;

    public OutpostTravel(OutpostService outposts, TeleportService travel) {
        this.outposts = Objects.requireNonNull(outposts, "outposts");
        this.travel = Objects.requireNonNull(travel, "travel");
    }

    /**
     * Starts the journey, or refuses with a reason.
     *
     * @return the warmup in seconds, zero if the player arrived at once
     */
    public Result<Long> request(Player player, City city, Outpost outpost) {
        if (!city.hasPermission(player.getUniqueId(), CityPermission.OUTPOST_TP)) {
            return Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.OUTPOST_TP.name()));
        }
        if (isCityAtWar(city)) {
            // SPEC 7.4: disabled entirely during a war, to prevent instant reinforcement.
            // SPEC 11.6 says it again from the other side, listing outpost warps among the
            // things that stay blocked even in war. A war is fought across ground somebody
            // has to cross.
            return Result.failure("CITY_AT_WAR", "outpost.tp-at-war");
        }

        Optional<Location> destination = destinationOf(outpost);
        if (destination.isEmpty()) {
            return Result.failure("WORLD_NOT_LOADED", "outpost.world-missing");
        }

        // Named, so the warmup reads "Travelling to North" rather than "to your outpost".
        // M23 fixed exactly this on the messages this class replaces, and losing it to a
        // refactor would be undoing a fix that took two milestones to notice.
        return travel.begin(player, TravelKind.OUTPOST_TP, safeLanding(destination.get()),
                fareFor(city, outpost), outpost.name());
    }

    /** SPEC 39.5's {@code 100 * D(d)}: what this outpost costs to reach, from this city. */
    public BigDecimal fareFor(City city, Outpost outpost) {
        return outposts.teleportCost(city, outpost);
    }

    /** Where the outpost's warp point is, if its world is loaded. */
    public Optional<Location> destinationOf(Outpost outpost) {
        Optional<Claim> chunk = outposts.claimOf(outpost);
        if (chunk.isEmpty()) {
            return Optional.empty();
        }
        World world = org.bukkit.Bukkit.getWorld(chunk.get().world());
        return world == null
                ? Optional.empty()
                : Optional.of(new Location(world, outpost.warpX(), outpost.warpY(),
                        outpost.warpZ(), outpost.warpYaw(), outpost.warpPitch()));
    }

    // ==================================================================================
    // Landing safely, SPEC 7.4
    // ==================================================================================

    /**
     * SPEC 7.4: an unsafe destination becomes the highest safe Y in the same chunk.
     *
     * <p>Unsafe means suffocating, standing in lava, or below the world. A warp point can become
     * unsafe long after it was set, because somebody built over it or the outpost was flooded,
     * and dropping a player into a wall is a worse answer than moving them.
     */
    public Location safeLanding(Location wanted) {
        if (isSafe(wanted)) {
            return wanted;
        }
        World world = wanted.getWorld();
        if (world == null) {
            return wanted;
        }

        int x = wanted.getBlockX();
        int z = wanted.getBlockZ();
        for (int y = world.getMaxHeight() - 2; y > world.getMinHeight(); y--) {
            Location candidate = new Location(world, x + 0.5, y, z + 0.5,
                    wanted.getYaw(), wanted.getPitch());
            if (isSafe(candidate)) {
                return candidate;
            }
        }
        return wanted;
    }

    private static boolean isSafe(Location location) {
        World world = location.getWorld();
        if (world == null || location.getY() < world.getMinHeight()
                || location.getY() > world.getMaxHeight()) {
            return false;
        }
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);

        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }
        if (isDeadly(feet.getType()) || isDeadly(head.getType())) {
            return false;
        }
        return ground.getType().isSolid();
    }

    private static boolean isDeadly(Material material) {
        return material == Material.LAVA || material == Material.FIRE
                || material == Material.CAMPFIRE || material == Material.SOUL_FIRE
                || material == Material.MAGMA_BLOCK;
    }

    // ==================================================================================
    // The war seam, SPEC 7.4 and 11.11
    // ==================================================================================

    private boolean isCityAtWar(City city) {
        return wars != null && wars.blocksOutposts(city.id());
    }

    private dev.civitas.core.war.WarRestrictions wars;

    /** SPEC 7.4 and 11.11, wired by M19. */
    public void useWars(dev.civitas.core.war.WarRestrictions restrictions) {
        this.wars = restrictions;
    }
}
