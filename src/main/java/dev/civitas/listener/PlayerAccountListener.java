package dev.civitas.listener;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.contest.LoginFingerprint;
import dev.civitas.core.economy.PlayerAccountService;
import dev.civitas.storage.dao.PlayerLoginDao;
import org.bukkit.entity.Player;
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
 *
 * <p>A join also records the SPEC 13.4 connection fingerprint: a salted hash, never the
 * address. It is written after the account load rather than beside it so that the foreign key
 * on {@code player_logins.uuid} always has a row to point at.
 */
public final class PlayerAccountListener implements Listener {

    private final PlayerAccountService accounts;
    /**
     * SPEC 23.6's notification preferences. Loaded on join and dropped on quit, because the
     * router consults them before every message and cannot afford a database read there.
     */
    private dev.civitas.msg.TogglePreferences toggles;
    private dev.civitas.msg.Messenger messenger;
    private final PlayerLoginDao logins;
    private final LoginFingerprint fingerprints;
    private final Logger logger;

    public PlayerAccountListener(PlayerAccountService accounts, PlayerLoginDao logins,
                                 LoginFingerprint fingerprints, Logger logger) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.logins = Objects.requireNonNull(logins, "logins");
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Hands the listener the messaging state that follows a player's session. */
    public void useMessaging(dev.civitas.msg.TogglePreferences preferences,
                             dev.civitas.msg.Messenger router) {
        this.toggles = Objects.requireNonNull(preferences, "preferences");
        this.messenger = Objects.requireNonNull(router, "router");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Optional<String> fingerprint = fingerprints.hash(addressOf(player));

        if (toggles != null) {
            toggles.load(player.getUniqueId());
        }
        accounts.onJoin(player.getUniqueId(), player.getName(), now)
                .thenCompose(ignored -> fingerprint
                        .map(hash -> logins.upsert(player.getUniqueId(), hash, now))
                        .orElseGet(() -> java.util.concurrent.CompletableFuture.completedFuture(0)))
                .exceptionally(error -> {
                    logger.log(Level.SEVERE, "Could not load the account for " + player.getName(),
                            error);
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (toggles != null) {
            toggles.forget(event.getPlayer().getUniqueId());
        }
        if (messenger != null) {
            // Throttle and title history go with the session, so a rejoin starts clean.
            messenger.forget(event.getPlayer().getUniqueId());
        }
        accounts.onQuit(event.getPlayer().getUniqueId(), System.currentTimeMillis())
                .exceptionally(error -> {
                    logger.log(Level.SEVERE, "Could not save the account for "
                            + event.getPlayer().getName(), error);
                    return null;
                });
    }

    /** @return the connection address, or null when the server does not report one */
    private static String addressOf(Player player) {
        InetSocketAddress socket = player.getAddress();
        if (socket == null || socket.getAddress() == null) {
            return null;
        }
        return socket.getAddress().getHostAddress();
    }
}
