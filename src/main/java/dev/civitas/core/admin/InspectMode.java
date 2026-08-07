package dev.civitas.core.admin;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ClaimRegistry;
import org.bukkit.Location;

/**
 * SPEC 9.4.1's {@code /ca inspect}: "Toggle inspect mode: clicking any block shows owning city,
 * claim date, claimer."
 *
 * <h2>A mode rather than a command per block</h2>
 * The question an admin is answering — "who owns this, and since when" — is asked about a dozen
 * blocks in a row while walking a boundary. A command per block would mean typing coordinates
 * they are standing on.
 *
 * <p>The mode is per-admin and in memory only. It is deliberately not persisted: an admin who
 * left it on last week and forgot should not find their next block-break silently cancelled.
 */
public final class InspectMode {

    private final ClaimRegistry claims;
    private final CityRegistry cities;

    /** Admins currently inspecting. Never large, and empty on almost every server. */
    private final Set<UUID> inspecting = ConcurrentHashMap.newKeySet();

    public InspectMode(ClaimRegistry claims, CityRegistry cities) {
        this.claims = Objects.requireNonNull(claims, "claims");
        this.cities = Objects.requireNonNull(cities, "cities");
    }

    /** @return whether inspect mode is now on for this admin */
    public boolean toggle(UUID admin) {
        if (inspecting.remove(admin)) {
            return false;
        }
        inspecting.add(admin);
        return true;
    }

    public boolean isInspecting(UUID admin) {
        return inspecting.contains(admin);
    }

    public void forget(UUID admin) {
        inspecting.remove(admin);
    }

    public int count() {
        return inspecting.size();
    }

    /** What an admin sees when they click. */
    public record Report(boolean claimed, String city, String cityDisplay, long claimedAt,
                         UUID claimedBy, String type, int chunkX, int chunkZ, String world,
                         boolean adminProtected) { }

    /**
     * Describes the chunk at a location.
     *
     * <p>From the cache, never from storage: this fires on a click and SPEC 2.1 forbids a
     * query there. Everything it reports is already in memory because the claim registry is
     * the runtime authority (SPEC 2.3).
     */
    public Report describe(Location at, boolean protectedChunk) {
        String world = at.getWorld() == null ? "?" : at.getWorld().getName();
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;

        Optional<Claim> claim = claims.at(world, chunkX, chunkZ);
        if (claim.isEmpty()) {
            return new Report(false, null, null, 0L, null, null, chunkX, chunkZ, world,
                    protectedChunk);
        }

        Optional<City> owner = cities.city(claim.get().cityId());
        return new Report(true,
                owner.map(City::name).orElse("#" + claim.get().cityId()),
                owner.map(City::displayName).orElse(null),
                claim.get().claimedAt(),
                claim.get().claimedBy(),
                claim.get().type().name(),
                chunkX, chunkZ, world, protectedChunk);
    }
}
