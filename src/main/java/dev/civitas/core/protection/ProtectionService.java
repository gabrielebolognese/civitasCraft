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
            return checkPvp(city, world, chunkX, chunkZ);
        }

        if (isGriefPermitted(city, player, world, chunkX, chunkZ)) {
            // SPEC 11.6: inside an active war zone, the opposing side may do all of this.
            return ProtectionDecision.ALLOWED;
        }

        if (!city.isMember(player)) {
            if (isTrustedAlly(city, player, action)) {
                // SPEC 14.2: allies may grant each other reciprocal build access.
                return ProtectionDecision.ALLOWED;
            }
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
     * SPEC 14.2's reciprocal build access.
     *
     * <p>Two allied cities may grant each other {@code BUILD} and {@code INTERACT}, and SPEC
     * 14.2 says "never {@code CONTAINER}" in as many words. That exclusion is the whole
     * reason trust is safe to give: an ally can help you build and cannot empty your chests.
     * So the allowed set is fixed here rather than read from the acting player's own rank,
     * which would let a city grant itself more by editing its own permissions.
     */
    private boolean isTrustedAlly(City city, UUID player, ProtectionAction action) {
        if (diplomacy == null) {
            return false;
        }
        if (action != ProtectionAction.BUILD && action != ProtectionAction.INTERACT) {
            return false;
        }
        return cities.cityOf(player)
                .filter(theirs -> theirs.id() != city.id())
                .filter(theirs -> diplomacy.areTrusted(city.id(), theirs.id()))
                .isPresent();
    }

    /**
     * Told about diplomacy once it exists.
     *
     * <p>Set rather than injected because protection is built before diplomacy and answers
     * correctly without it: no diplomacy means no trusted allies, which is what a server
     * with no alliances has anyway.
     */
    public void useDiplomacy(dev.civitas.core.diplomacy.DiplomacyRegistry registry) {
        this.diplomacy = registry;
    }

    private dev.civitas.core.diplomacy.DiplomacyRegistry diplomacy;

    /**
     * SPEC 5.5: "PvP inside claims: disabled outside of war. Enabled only inside the claims
     * of cities that are party to an active war."
     */
    private ProtectionDecision checkPvp(City city, String world, int chunkX, int chunkZ) {
        if (isAtWar(city, world, chunkX, chunkZ)) {
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
        // SPEC 17.4 case 46: "Fluid flow across the war zone boundary is cancelled outright."
        //
        // Ownership alone very nearly covers this and not quite. A war zone includes a
        // one-chunk perimeter (SPEC 11.4) which is usually wilderness, and wilderness counts
        // as its own owner, so lava inside the perimeter would be free to flow on into the
        // wilderness beyond it. That flow is outside every zone, so M17 never logs it and M18
        // never restores it — permanent damage from a war, which SPEC 11.4 forbids outright:
        // "Nothing outside the war zone is ever affected."
        if (crossesZoneBoundary(world, fromX, fromZ, toX, toZ)) {
            return false;
        }
        return sameOwner(world, fromX, fromZ, toX, toZ);
    }

    /** Whether exactly one of these two chunks is inside a live war zone. */
    private boolean crossesZoneBoundary(String world, int fromX, int fromZ, int toX, int toZ) {
        if (wars == null || !wars.isAnyWarActive()) {
            return false;
        }
        return wars.isInActiveZone(world, fromX << 4, fromZ << 4)
                != wars.isInActiveZone(world, toX << 4, toZ << 4);
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
        return isAtWar(owner.get(), world, chunkX, chunkZ);
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
     * <p>The single switch that turns SPEC 5.5's four "except in war" clauses on. It stays
     * false on a server with no war running, which is every server most of the time.
     */
    private boolean isGriefPermitted(City city, UUID player, String world, int chunkX, int chunkZ) {
        return wars != null
                && wars.isGriefPermitted(city.id(), player, world, chunkX << 4, chunkZ << 4);
    }

    /** SPEC 5.5: whether this city is party to an active war, which enables PvP on its land. */
    private boolean isAtWar(City city, String world, int chunkX, int chunkZ) {
        return wars != null && wars.isEngaged(city.id())
                && !wars.isZoneClosed(world, chunkX << 4, chunkZ << 4);
    }

    private dev.civitas.core.war.WarRestrictions wars;

    /**
     * SPEC 11.6, wired by M19.
     *
     * <p>Optional in the same way diplomacy is: a protection service with no war system
     * refuses every wartime exception, which is the safe direction.
     */
    public void useWars(dev.civitas.core.war.WarRestrictions restrictions) {
        this.wars = restrictions;
    }
}
