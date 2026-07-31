package dev.civitas.api.event;

import java.util.UUID;

import dev.civitas.core.city.City;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player leaves a city of their own accord.
 *
 * <p>A kick fires {@link CityKickEvent} instead, so a handler can tell the two apart.
 */
public final class CityLeaveEvent extends CivitasEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final City city;
    private final UUID player;

    public CityLeaveEvent(City city, UUID player) {
        this.city = city;
        this.player = player;
    }

    public City city() {
        return city;
    }

    public UUID player() {
        return player;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
