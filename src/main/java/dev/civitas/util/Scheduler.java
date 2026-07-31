package dev.civitas.util;

import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Hops back to the server thread after async work.
 *
 * <p>An interface rather than a direct call to Bukkit's scheduler so services can be tested
 * without a running server: {@link #direct()} runs the task inline, which turns an
 * asynchronous service into a synchronous one for the duration of a test without changing
 * the code under test.
 */
@FunctionalInterface
public interface Scheduler {

    /** Runs {@code task} on the server thread, or immediately if already there. */
    void runOnMain(Runnable task);

    /** The real scheduler. */
    static Scheduler bukkit(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return task -> {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, task);
            }
            // If the plugin is disabling, the scheduler rejects new tasks. Dropping the
            // callback is correct here: the database write already committed, and the cache
            // it would have updated is about to be discarded anyway.
        };
    }

    /** Runs tasks inline. For tests, and for code paths already on the server thread. */
    static Scheduler direct() {
        return Runnable::run;
    }
}
