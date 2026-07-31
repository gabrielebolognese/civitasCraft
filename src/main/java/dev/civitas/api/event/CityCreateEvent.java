package dev.civitas.api.event;

import java.util.UUID;

import org.bukkit.event.HandlerList;

/**
 * Fired when a player has passed every SPEC 5.1 precondition and is about to found a city.
 *
 * <p>Cancelling stops the city being created and charges the founder nothing.
 */
public final class CityCreateEvent extends CivitasEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID founder;
    private final String cityName;

    public CityCreateEvent(UUID founder, String cityName) {
        this.founder = founder;
        this.cityName = cityName;
    }

    public UUID founder() {
        return founder;
    }

    public String cityName() {
        return cityName;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
