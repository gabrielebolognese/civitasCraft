package dev.civitas.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC 36.4's public API. <b>Read-only, deliberately and completely.</b>
 *
 * <h2>What is exposed and what is not</h2>
 *
 * <p>SPEC 36.4: "The plugin exposes: read-only accessors for cities, claims, members, ranks,
 * balances, and war state, and the full set of cancellable custom events already listed in Part I
 * 2.3. <b>All economy mutation goes through the service layer and is not exposed, deliberately, so
 * no third-party plugin can create money outside the ledger.</b>"
 *
 * <p>That last clause is the whole design. SPEC 1.5 makes the ledger the authority on where every
 * coin came from, and an API that let another plugin move a balance would put money into the world
 * with no ledger row behind it — which would defeat SPEC 21.4's supply accounting, the circuit
 * breakers, and every fraud heuristic in one step. So there is no method here that changes
 * anything, and adding one would need this sentence deleted first.
 *
 * <p>The mutation route a third-party plugin does have is the Vault economy provider, which goes
 * through {@code EconomyService} and therefore writes a ledger row like everything else.
 *
 * <h2>Obtaining it</h2>
 *
 * <pre>{@code
 * CivitasApi api = Bukkit.getServicesManager().load(CivitasApi.class);
 * }</pre>
 *
 * <p>Registered on enable and unregistered on disable. It answers {@link #isReady()} false during
 * SPEC 2's null window — the plugin opens its database asynchronously, and a caller that reads
 * before storage is open gets an honest "not yet" rather than an empty world.
 */
public interface CivitasApi {

    /** Whether storage is open. Everything below answers empty until this is true. */
    boolean isReady();

    // ==================================================================================
    // Cities and members
    // ==================================================================================

    /** A city by its id. */
    Optional<CityView> city(int cityId);

    /** A city by name, case-insensitively, as SPEC 3.2's unique index treats it. */
    Optional<CityView> cityByName(String name);

    /** The city a player belongs to, if any. SPEC 34.1: many players never join one. */
    Optional<CityView> cityOf(UUID player);

    Collection<CityView> cities();

    /** A read-only view of a city. */
    interface CityView {

        int id();

        String name();

        String tag();

        UUID mayor();

        BigDecimal treasury();

        long foundedAt();

        Collection<UUID> members();

        /** The rank name a member holds, empty if they are not in this city. */
        Optional<String> rankOf(UUID player);

        /** Whether a member holds a SPEC 5.4 permission flag, by its enum name. */
        boolean hasPermission(UUID player, String flag);

        int claimCount();

        boolean isAtWar();
    }

    // ==================================================================================
    // Claims
    // ==================================================================================

    /** Which city owns a chunk, empty for wilderness. */
    Optional<CityView> claimAt(String world, int chunkX, int chunkZ);

    /** Whether a player may build at a chunk, which is the question most callers actually have. */
    boolean canBuild(UUID player, String world, int chunkX, int chunkZ);

    // ==================================================================================
    // Balances
    // ==================================================================================

    /**
     * A player's balance.
     *
     * <p>Read from the in-memory cache, so it never blocks. There is deliberately no setter: see
     * the class javadoc.
     */
    BigDecimal balance(UUID player);

    // ==================================================================================
    // War
    // ==================================================================================

    /** The war a city is in, if any. */
    Optional<WarView> warOf(int cityId);

    interface WarView {

        int id();

        int attackerCityId();

        int defenderCityId();

        /** {@code PREP}, {@code ACTIVE}, {@code ROLLING_BACK}, {@code RESOLVED} and so on. */
        String state();

        long endsAt();

        /** Whether a chunk is inside this war's zone, SPEC 11.4. */
        boolean zoneContains(String world, int chunkX, int chunkZ);
    }
}
