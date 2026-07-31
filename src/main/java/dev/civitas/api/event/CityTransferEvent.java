package dev.civitas.api.event;

import java.util.UUID;

import dev.civitas.core.city.City;
import org.bukkit.event.HandlerList;

/** Fired before mayorship moves to another player, SPEC 5.3. */
public final class CityTransferEvent extends CivitasEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final City city;
    private final UUID from;
    private final UUID to;

    public CityTransferEvent(City city, UUID from, UUID to) {
        this.city = city;
        this.from = from;
        this.to = to;
    }

    public City city() {
        return city;
    }

    public UUID from() {
        return from;
    }

    public UUID to() {
        return to;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
