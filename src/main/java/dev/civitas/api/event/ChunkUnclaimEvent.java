package dev.civitas.api.event;

import java.math.BigDecimal;
import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.claim.Claim;
import org.bukkit.event.HandlerList;

/**
 * Fired before a chunk is given up, once the SPEC 6.4 checks have passed.
 *
 * <p>Cancelling keeps the land and pays no refund.
 */
public final class ChunkUnclaimEvent extends CivitasEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final City city;
    private final UUID actor;
    private final Claim claim;
    private final BigDecimal refund;

    public ChunkUnclaimEvent(City city, UUID actor, Claim claim, BigDecimal refund) {
        this.city = city;
        this.actor = actor;
        this.claim = claim;
        this.refund = refund;
    }

    public City city() {
        return city;
    }

    /** Who ordered it, or null when the plugin did. */
    public UUID actor() {
        return actor;
    }

    public Claim claim() {
        return claim;
    }

    /** What the treasury is about to receive back, SPEC 6.4. */
    public BigDecimal refund() {
        return refund;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
