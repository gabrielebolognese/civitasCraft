package dev.civitas.api.event;

import java.util.UUID;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRank;
import org.bukkit.event.HandlerList;

/** Fired before a member's rank changes, whether by promotion, demotion or direct assignment. */
public final class CityRankChangeEvent extends CivitasEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final City city;
    private final UUID actor;
    private final UUID target;
    private final CityRank from;
    private final CityRank to;

    public CityRankChangeEvent(City city, UUID actor, UUID target, CityRank from, CityRank to) {
        this.city = city;
        this.actor = actor;
        this.target = target;
        this.from = from;
        this.to = to;
    }

    public City city() {
        return city;
    }

    /** Who ordered the change, or null when the plugin did. */
    public UUID actor() {
        return actor;
    }

    public UUID target() {
        return target;
    }

    public CityRank from() {
        return from;
    }

    public CityRank to() {
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
