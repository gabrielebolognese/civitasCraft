package dev.civitas.api.event;

import java.util.UUID;

import dev.civitas.core.city.City;
import org.bukkit.event.HandlerList;

/**
 * Fired before a city is soft-deleted.
 *
 * <p>Cancelling leaves the city, its claims and its treasury untouched.
 */
public final class CityDisbandEvent extends CivitasEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final City city;
    private final UUID actor;

    public CityDisbandEvent(City city, UUID actor) {
        this.city = city;
        this.actor = actor;
    }

    public City city() {
        return city;
    }

    /** Who ordered the disband, or null when the plugin did it. */
    public UUID actor() {
        return actor;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
