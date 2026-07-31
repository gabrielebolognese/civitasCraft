package dev.civitas.api.event;

import java.util.UUID;

import dev.civitas.core.city.City;
import org.bukkit.event.HandlerList;

/** Fired before a player is added to a city. Cancelling refuses the join. */
public final class CityJoinEvent extends CivitasEvent {

    /** How the player came to be joining, SPEC 5.2. */
    public enum Method {
        /** Accepted an invite. */
        INVITE,
        /** Walked into an open-join city. */
        OPEN_JOIN,
        /** Force-added by an admin, bypassing cooldowns and caps. */
        ADMIN
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final City city;
    private final UUID player;
    private final Method method;

    public CityJoinEvent(City city, UUID player, Method method) {
        this.city = city;
        this.player = player;
        this.method = method;
    }

    public City city() {
        return city;
    }

    public UUID player() {
        return player;
    }

    public Method method() {
        return method;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
