package dev.civitas.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

/**
 * Base for every event this plugin fires, SPEC 2.3.
 *
 * <p>All of them are cancellable and all of them fire <em>before</em> the change is written,
 * which is the only point at which cancelling can mean anything: each mutation is a single
 * database transaction, so once it commits there is nothing left to veto.
 *
 * <p>Handlers run on the server thread. They must not block.
 */
public abstract class CivitasEvent extends Event implements Cancellable {

    private boolean cancelled;

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
