package dev.civitas.util;

import dev.civitas.api.event.CivitasEvent;
import org.bukkit.Bukkit;

/**
 * Fires the plugin's cancellable events, SPEC 2.3.
 *
 * <p>An interface rather than a direct {@code Bukkit.getPluginManager().callEvent} call so a
 * service can be unit tested without a server, and so a test can assert that cancelling an
 * event really does abort the mutation.
 */
@FunctionalInterface
public interface EventBus {

    /**
     * @return true if the event was not cancelled and the caller should proceed
     */
    boolean fire(CivitasEvent event);

    /** The real bus. Must be called from the server thread. */
    static EventBus bukkit() {
        return event -> {
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        };
    }

    /** Fires nothing and always proceeds. For unit tests with no server. */
    static EventBus noop() {
        return event -> true;
    }
}
