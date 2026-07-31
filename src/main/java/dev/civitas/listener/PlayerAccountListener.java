package dev.civitas.listener;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.economy.PlayerAccountService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Keeps the {@code players} row behind each online player up to date.
 *
 * <p>Both handlers hand straight off to async work, because SPEC 2.1 forbids storage on the
 * server thread and a join must never be delayed by a database round trip.
 */
public final class PlayerAccountListener implements Listener {

    private final PlayerAccountService accounts;
    private final Logger logger;

    public PlayerAccountListener(PlayerAccountService accounts, Logger logger) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        accounts.onJoin(event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                        System.currentTimeMillis())
                .exceptionally(error -> {
                    logger.log(Level.SEVERE, "Could not load the account for "
                            + event.getPlayer().getName(), error);
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        accounts.onQuit(event.getPlayer().getUniqueId(), System.currentTimeMillis())
                .exceptionally(error -> {
                    logger.log(Level.SEVERE, "Could not save the account for "
                            + event.getPlayer().getName(), error);
                    return null;
                });
    }
}
