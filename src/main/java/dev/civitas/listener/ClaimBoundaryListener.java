package dev.civitas.listener;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.BorderRenderer;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ChunkKey;
import dev.civitas.core.claim.ClaimService;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Result;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Tells players whose land they are standing on, SPEC 6.5, and drives auto-claim, SPEC 6.3.
 *
 * <h2>Cost</h2>
 * {@code PlayerMoveEvent} fires several times per player per tick. Everything here is
 * therefore behind one integer comparison: if the player is still in the chunk they were in
 * last time, the handler returns immediately without a map lookup, a config read or an
 * allocation. Only an actual chunk crossing does any work, and even then the ownership
 * lookup is the O(1) packed-key hit that SPEC 2.3 designed the cache around.
 */
public final class ClaimBoundaryListener implements Listener {

    private final CityRegistry cities;
    private final ClaimService claims;
    private final BorderRenderer borders;
    private final LangManager lang;

    /** The chunk each player was last known to be in, as a packed pair of chunk coordinates. */
    private final Map<UUID, Long> lastChunk = new ConcurrentHashMap<>();

    public ClaimBoundaryListener(CityRegistry cities, ClaimService claims,
                                 BorderRenderer borders, LangManager lang) {
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.borders = Objects.requireNonNull(borders, "borders");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        handleCrossing(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        handleCrossing(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Seed the remembered chunk so the first step does not announce a crossing that did
        // not happen, and so a player who logs in inside a claim is told where they are.
        Player player = event.getPlayer();
        lastChunk.put(player.getUniqueId(), chunkOf(player.getLocation()));
        announce(player, ownerAt(player.getLocation()), Optional.empty());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastChunk.remove(uuid);
        claims.stopAutoClaiming(uuid);
        borders.stop(uuid);
    }

    private void handleCrossing(Player player, Location to) {
        if (to == null) {
            return;
        }
        long chunk = chunkOf(to);
        Long previous = lastChunk.get(player.getUniqueId());
        if (previous != null && previous == chunk) {
            return;
        }

        Optional<City> from = previous == null ? Optional.empty() : cityAt(player, previous);
        lastChunk.put(player.getUniqueId(), chunk);

        Optional<City> into = ownerAt(to);
        announce(player, into, from);

        if (into.isEmpty()) {
            tryAutoClaim(player, to);
        }
    }

    /**
     * The action bar line for a crossing, SPEC 6.5.
     *
     * <p>Nothing is sent when the owner has not actually changed, so walking along a border
     * between two chunks of the same city is silent.
     */
    private void announce(Player player, Optional<City> into, Optional<City> from) {
        Integer intoId = into.map(City::id).orElse(null);
        Integer fromId = from.map(City::id).orElse(null);
        if (Objects.equals(intoId, fromId)) {
            return;
        }

        if (into.isPresent()) {
            player.sendActionBar(lang.get("claim.entering",
                    LangManager.placeholder("city", into.get().displayName())));
        } else if (from.isPresent()) {
            player.sendActionBar(lang.get("claim.leaving",
                    LangManager.placeholder("city", from.get().displayName())));
        } else {
            player.sendActionBar(lang.get("claim.wilderness"));
        }
    }

    /**
     * Buys the chunk the player just walked into, if they have auto-claim on, SPEC 6.3.
     *
     * <p>Auto-claim turns itself off on the first refusal. Otherwise a player who runs out of
     * money while exploring would generate one failure message per chunk for as long as they
     * kept walking.
     */
    private void tryAutoClaim(Player player, Location at) {
        UUID uuid = player.getUniqueId();
        if (!claims.isAutoClaiming(uuid)) {
            return;
        }
        Optional<City> city = cities.cityOf(uuid);
        if (city.isEmpty()) {
            claims.stopAutoClaiming(uuid);
            return;
        }

        String world = at.getWorld().getName();
        int chunkX = ChunkKey.toChunk(at.getBlockX());
        int chunkZ = ChunkKey.toChunk(at.getBlockZ());

        claims.claim(uuid, city.get(), world, chunkX, chunkZ).thenAccept(result -> {
            if (result instanceof Result.Failure<Claim> failure) {
                claims.stopAutoClaiming(uuid);
                lang.send(player, "claim.auto-stopped");
                lang.send(player, failure.messageKey(),
                        LangManager.placeholders(failure.placeholders()));
                return;
            }
            lang.send(player, "claim.auto-claimed",
                    LangManager.placeholder("chunk", chunkX + "," + chunkZ),
                    LangManager.placeholder("cost",
                            result.orElseThrow().costPaid().toPlainString()));
            borders.highlightChunk(player, world, chunkX, chunkZ);
        });
    }

    private Optional<City> ownerAt(Location location) {
        return claims.registry()
                .atBlock(location.getWorld().getName(), location.getBlockX(), location.getBlockZ())
                .map(Claim::cityId)
                .flatMap(cities::city);
    }

    private Optional<City> cityAt(Player player, long packedChunk) {
        int chunkX = (int) (packedChunk >> 32);
        int chunkZ = (int) packedChunk;
        return claims.registry()
                .at(player.getWorld().getName(), chunkX, chunkZ)
                .map(Claim::cityId)
                .flatMap(cities::city);
    }

    /**
     * Chunk coordinates packed into a long, for the has-the-player-moved comparison only.
     *
     * <p>Not {@link ChunkKey}: this never leaves the listener, needs no world component, and
     * must not throw for a coordinate outside the claimable range, because a player standing
     * there is not doing anything wrong.
     */
    private static long chunkOf(Location location) {
        long x = ChunkKey.toChunk(location.getBlockX());
        long z = ChunkKey.toChunk(location.getBlockZ());
        return (x << 32) | (z & 0xFFFFFFFFL);
    }
}
