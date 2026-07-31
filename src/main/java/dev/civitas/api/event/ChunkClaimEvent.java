package dev.civitas.api.event;

import java.math.BigDecimal;
import java.util.UUID;

import dev.civitas.core.city.City;
import org.bukkit.event.HandlerList;

/**
 * Fired before a chunk is bought, once every SPEC 6.3 precondition has passed and the price
 * is known.
 *
 * <p>Cancelling leaves the chunk wilderness and charges the treasury nothing.
 */
public final class ChunkClaimEvent extends CivitasEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final City city;
    private final UUID actor;
    private final String world;
    private final int chunkX;
    private final int chunkZ;
    private final BigDecimal cost;

    public ChunkClaimEvent(City city, UUID actor, String world, int chunkX, int chunkZ,
                           BigDecimal cost) {
        this.city = city;
        this.actor = actor;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.cost = cost;
    }

    public City city() {
        return city;
    }

    public UUID actor() {
        return actor;
    }

    public String world() {
        return world;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    /** What the treasury is about to be charged. */
    public BigDecimal cost() {
        return cost;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
