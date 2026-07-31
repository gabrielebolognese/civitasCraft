package dev.civitas.util;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.dao.PlayerDao;
import dev.civitas.storage.row.PlayerRow;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Resolves a typed player name to a UUID.
 *
 * <p>Online players resolve without touching the database, which is the common case. An
 * offline name falls back to {@code players.last_known_name}, so a member who has not logged
 * in for a month can still be kicked or ranked by the name their city remembers.
 */
public final class PlayerLookup {

    private final PlayerDao players;

    public PlayerLookup(PlayerDao players) {
        this.players = Objects.requireNonNull(players, "players");
    }

    /**
     * @param name a player name as typed, case-insensitive
     * @return the UUID and the name as recorded, or empty if the server has never seen them
     */
    public CompletableFuture<Optional<Resolved>> resolve(String name) {
        if (name == null || name.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(
                    Optional.of(new Resolved(online.getUniqueId(), online.getName(), true)));
        }

        return players.findByName(name)
                .thenApply(row -> row.map(PlayerLookup::toResolved));
    }

    private static Resolved toResolved(PlayerRow row) {
        boolean online = Bukkit.getPlayer(row.uuid()) != null;
        return new Resolved(row.uuid(), row.lastKnownName(), online);
    }

    /**
     * @param uuid   who
     * @param name   the name as last recorded
     * @param online whether they are on the server right now
     */
    public record Resolved(UUID uuid, String name, boolean online) { }
}
