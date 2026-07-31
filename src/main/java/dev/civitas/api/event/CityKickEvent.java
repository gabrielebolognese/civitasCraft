package dev.civitas.api.event;

import java.util.UUID;

import dev.civitas.core.city.City;
import org.bukkit.event.HandlerList;

/** Fired before a member is removed from a city by someone else, SPEC 5.3. */
public final class CityKickEvent extends CivitasEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final City city;
    private final UUID actor;
    private final UUID target;

    public CityKickEvent(City city, UUID actor, UUID target) {
        this.city = city;
        this.actor = actor;
        this.target = target;
    }

    public City city() {
        return city;
    }

    /** Who did the kicking, or null when the plugin did. */
    public UUID actor() {
        return actor;
    }

    public UUID target() {
        return target;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
