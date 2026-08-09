package dev.civitas.core.defense;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPEC 27.3's Watchtower Keeper, which is the one unit that cannot fight.
 *
 * <p>"Detection radius 32 blocks. Applies Glowing to non-members and non-allies within radius,
 * 3-second refresh. Posts a message to city chat when an unknown player enters, rate-limited to
 * once per player per 5 minutes." SPEC 27.3 also says outright that this "is a repeating task,
 * not AI", which is why none of it goes anywhere near {@link TargetingRule}: the Keeper never
 * targets anybody, so the one handler has nothing to say about it.
 *
 * <p>This class is the rule and the rate limit. The task that gathers players and paints them
 * is {@code WatchtowerTask}, so that the two decisions worth getting wrong — who is glowed and
 * who is announced — can be asserted without a world.
 *
 * <h2>Who clears the glow</h2>
 *
 * <p>Only players this system glowed. A player glowing because a Keeper saw them and a player
 * glowing for some other reason are indistinguishable from the entity, so clearing everything
 * out of radius would silently un-glow somebody else's effect. {@link #stopGlowing} hands back
 * exactly the set that was painted and is no longer in range.
 */
public final class WatchtowerDetection {

    /** When a city last announced a given player, so SPEC 27.3's five minutes can be kept. */
    private final Map<Sighting, Long> announced = new ConcurrentHashMap<>();

    /** Who this system has glowing, per city, so it only ever clears its own. */
    private final Map<Integer, java.util.Set<UUID>> glowing = new ConcurrentHashMap<>();

    private record Sighting(int cityId, UUID player) {

        Sighting {
            Objects.requireNonNull(player, "player");
        }
    }

    /** Whether a player at this distance is inside a Keeper's radius. */
    public static boolean withinRadius(double distance, double radius) {
        return distance <= radius;
    }

    /**
     * Whether this city may announce this player now, SPEC 27.3's five-minute limit.
     *
     * <p>Per city rather than per Keeper, because a city with three towers announcing the same
     * visitor three times is the spam the limit exists to stop. Records the announcement as it
     * grants it.
     */
    public boolean claimAnnouncement(int cityId, UUID player, long now, long cooldownMillis) {
        Sighting sighting = new Sighting(cityId, player);
        Long previous = announced.get(sighting);
        if (previous != null && now - previous < cooldownMillis) {
            return false;
        }
        announced.put(sighting, now);
        return true;
    }

    /** Remembers that this city's towers are lighting these players up. */
    public void startGlowing(int cityId, UUID player) {
        glowing.computeIfAbsent(cityId, id -> ConcurrentHashMap.newKeySet()).add(player);
    }

    /**
     * The players this city was lighting up and is not any more.
     *
     * @param stillVisible who is still inside a Keeper's radius
     */
    public java.util.Set<UUID> stopGlowing(int cityId, java.util.Set<UUID> stillVisible) {
        java.util.Set<UUID> painted = glowing.get(cityId);
        if (painted == null || painted.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<UUID> gone = new java.util.LinkedHashSet<>(painted);
        gone.removeAll(stillVisible);
        painted.removeAll(gone);
        return gone;
    }

    /** Whether this city is currently lighting anybody up. */
    public boolean isGlowing(int cityId, UUID player) {
        java.util.Set<UUID> painted = glowing.get(cityId);
        return painted != null && painted.contains(player);
    }

    public void forget(UUID player) {
        announced.keySet().removeIf(sighting -> sighting.player().equals(player));
        glowing.values().forEach(set -> set.remove(player));
    }

    public void forgetCity(int cityId) {
        announced.keySet().removeIf(sighting -> sighting.cityId() == cityId);
        glowing.remove(cityId);
    }
}
