package dev.civitas.core.protection;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ClaimRegistry;

/**
 * Every rule in SPEC 5.5, as pure functions.
 *
 * <p>No Bukkit types cross this boundary. The listeners translate an event into a world name
 * and a chunk position and ask here; the answer is decided from the claim cache and the city
 * cache and nothing else. That keeps the whole rule set testable without a server, and keeps
 * the hot path to two hash lookups.
 *
 * <p><strong>Ordering matters.</strong> Bypass is checked first, then wilderness, then
 * dormancy, then war, then membership. Each of those is a reason the answer is yes before
 * membership is ever consulted, and consulting membership first would make an admin with
 * bypass unable to fix a broken city.
 */
public final class ProtectionService {

    private final ClaimRegistry claims;
    private final CityRegistry cities;
    private final ConfigManager configs;

    public ProtectionService(ClaimRegistry claims, CityRegistry cities, ConfigManager configs) {
        this.claims = Objects.requireNonNull(claims, "claims");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /**
     * May {@code player} perform {@code action} in this chunk?
     *
     * @param hasBypass whether the player holds {@code civitas.bypass.claim}, resolved by the
     *                  caller because permission lookup needs the Bukkit sender
     */
    public ProtectionDecision check(UUID player, boolean hasBypass, String world,
                                    int chunkX, int chunkZ, ProtectionAction action) {
        if (hasBypass) {
            return ProtectionDecision.ALLOWED;
        }

        Optional<Claim> claim = claims.at(world, chunkX, chunkZ);
        if (claim.isEmpty()) {
            // Wilderness. SPEC 5.5 protects claims, not the world.
            return ProtectionDecision.ALLOWED;
        }

        Optional<City> owner = cities.city(claim.get().cityId());
        if (owner.isEmpty()) {
            // A claim whose city is gone. Disbanding deletes claims, so this is corruption
            // rather than a state the game produces. Treating it as wilderness lets players
            // and admins clear it up; treating it as protected would freeze the land for
            // good with nobody able to release it.
            return ProtectionDecision.ALLOWED;
        }
        City city = owner.get();

        if (isDormant(city)) {
            // SPEC 17.1 case 2: a city inactive long enough loses protection until someone
            // logs in. The sweep that sets this is not built yet, so this never fires today.
            return ProtectionDecision.ALLOWED;
        }

        if (action == ProtectionAction.PVP) {
            return checkPvp(city);
        }

        if (isGriefPermitted(city, player)) {
            // SPEC 11.6: inside an active war zone, the opposing side may do all of this.
            return ProtectionDecision.ALLOWED;
        }

        if (!city.isMember(player)) {
            return ProtectionDecision.deny("NOT_A_MEMBER", action.messageKey(),
                    Map.of("city", city.name()));
        }

        for (CityPermission permission : action.anyOf()) {
            if (city.hasPermission(player, permission)) {
                return ProtectionDecision.ALLOWED;
            }
        }

        return ProtectionDecision.deny("NO_CITY_PERMISSION", action.messageKey(),
                Map.of("city", city.name(),
                        "permission", action.anyOf().iterator().next().name()));
    }

    /**
     * SPEC 5.5: "PvP inside claims: disabled outside of war. Enabled only inside the claims
     * of cities that are party to an active war."
     */
    private ProtectionDecision checkPvp(City city) {
        if (isAtWar(city)) {
            return ProtectionDecision.ALLOWED;
        }
        return ProtectionDecision.deny("PVP_DISABLED", ProtectionAction.PVP.messageKey(),
                Map.of("city", city.name()));
    }

    // ==================================================================================
    // Cross-boundary rules, SPEC 5.5
    // ==================================================================================

    /**
     * Whether a piston may move blocks between two chunks.
     *
     * <p>SPEC 5.5 blocks this <em>entirely</em> across any claim boundary, not merely into a
     * foreign claim, and calls out that it is a duplication vector as well as a grief one.
     * So the test is ownership equality: wilderness to wilderness is fine, claim to the same
     * claim's city is fine, everything else is not.
     */
    public boolean allowsPistonBetween(String world, int fromX, int fromZ, int toX, int toZ) {
        return sameOwner(world, fromX, fromZ, toX, toZ);
    }

    /**
     * Whether fluid, fire or any other spreading block may cross from one chunk to another.
     *
     * <p>SPEC 5.5 blocks flow "across claim boundaries into a foreign claim" and fire and
     * lava "across claim borders". Spreading within one city's own land is left alone: a
     * city's own lava is its own problem.
     */
    public boolean allowsSpreadBetween(String world, int fromX, int fromZ, int toX, int toZ) {
        return sameOwner(world, fromX, fromZ, toX, toZ);
    }

    /** Whether a block at this position may be destroyed by an explosion, SPEC 5.5. */
    public boolean allowsExplosionAt(String world, int chunkX, int chunkZ) {
        Optional<Claim> claim = claims.at(world, chunkX, chunkZ);
        if (claim.isEmpty()) {
            return true;
        }
        Optional<City> owner = cities.city(claim.get().cityId());
        if (owner.isEmpty() || isDormant(owner.get())) {
            return true;
        }
        // SPEC 5.5: "TNT and end crystal explosions (fully disabled outside war)".
        return isAtWar(owner.get());
    }

    /**
     * Whether two chunks answer to the same owner, counting wilderness as an owner of its own.
     */
    private boolean sameOwner(String world, int fromX, int fromZ, int toX, int toZ) {
        if (fromX == toX && fromZ == toZ) {
            return true;
        }
        Optional<Integer> from = claims.at(world, fromX, fromZ).map(Claim::cityId);
        Optional<Integer> to = claims.at(world, toX, toZ).map(Claim::cityId);
        return from.equals(to);
    }

    // ==================================================================================
    // Config-gated rules
    // ==================================================================================

    /** SPEC 5.5: "Villager trading (config toggle, default allowed)". */
    public boolean villagerTradingEnabled() {
        return configs.get(ConfigFile.CITIES)
                .getBoolean("protection.allow-villager-trading", true);
    }

    /** How long to wait before repeating a refusal to the same player, so denial cannot spam. */
    public long denyMessageCooldownMillis() {
        return configs.get(ConfigFile.CITIES)
                .getLong("protection.deny-message-cooldown-ms", 2000L);
    }

    /** Exposed so a listener can answer "who owns this" without reaching past the service. */
    public Optional<City> cityAt(String world, int chunkX, int chunkZ) {
        return claims.at(world, chunkX, chunkZ)
                .map(Claim::cityId)
                .flatMap(cities::city);
    }

    // ==================================================================================
    // Seams for milestones that do not exist yet
    // ==================================================================================

    /**
     * SPEC 17.1 case 2: a city inactive for long enough has unprotected claims until any
     * member logs in.
     *
     * <p>Always false until the inactivity sweep exists. Written out so the protection path
     * already has the branch, and so the milestone that adds dormancy changes one method
     * rather than auditing eight listeners.
     */
    private boolean isDormant(City city) {
        return false;
    }

    /**
     * SPEC 11.6: whether {@code player} may grief this city right now because their side is
     * at war with it and this chunk is inside the war zone.
     *
     * <p>Always false until M19. This is the single switch that turns SPEC 5.5's four
     * "except in war" clauses on.
     */
    private boolean isGriefPermitted(City city, UUID player) {
        return false;
    }

    /** SPEC 5.5: whether this city is party to an active war, which enables PvP on its land. */
    private boolean isAtWar(City city) {
        return false;
    }
}
