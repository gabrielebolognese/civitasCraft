package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.PlayerLoginRow;

/**
 * {@code player_logins}, added by V9.
 *
 * <p>Holds one salted hash per player, for the single question SPEC 13.4 asks: does this
 * voter connect from the same place as a member of the city they are voting for. There is no
 * method here that returns anything but a hash, and no way to ask what address produced one.
 */
public final class PlayerLoginDao extends Dao<PlayerLoginRow> {

    private static final String COLUMNS = "uuid, login_hash, updated_at";

    public PlayerLoginDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "player_logins";
    }

    @Override
    protected PlayerLoginRow map(ResultSet rs) throws SQLException {
        return new PlayerLoginRow(
                uuid(rs, "uuid"),
                rs.getString("login_hash"),
                rs.getLong("updated_at"));
    }

    /**
     * One player's fingerprint, on the caller's thread.
     *
     * <p>Needed by SPEC 21.4 F7, where the check has to happen inside the bounty payout's own
     * transaction: reading it beforehand would leave a gap in which the answer could change.
     */
    public Optional<PlayerLoginRow> findSync(java.sql.Connection connection, UUID player)
            throws java.sql.SQLException {
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM player_logins WHERE uuid = ?", this::map,
                player.toString());
    }

    public CompletableFuture<Optional<PlayerLoginRow>> find(UUID player) {
        return queryOne("SELECT " + COLUMNS + " FROM player_logins WHERE uuid = ?", player);
    }

    /** Records the connection a player last arrived on. */
    public CompletableFuture<Integer> upsert(UUID player, String loginHash, long now) {
        return db.transaction(connection -> upsert(connection, player, loginHash, now));
    }

    public int upsert(Connection connection, UUID player, String loginHash, long now)
            throws SQLException {
        int updated = updateSync(connection,
                "UPDATE player_logins SET login_hash = ?, updated_at = ? WHERE uuid = ?",
                loginHash, now, player);
        if (updated > 0) {
            return updated;
        }
        return updateSync(connection,
                "INSERT INTO player_logins (" + COLUMNS + ") VALUES (?, ?, ?)",
                player, loginHash, now);
    }

    /**
     * Whether any of {@code players} shares {@code loginHash}.
     *
     * <p>Answers SPEC 13.4's rule in one query rather than fetching every member's hash and
     * comparing in Java, which would mean moving a table of hashes around to learn one bit.
     *
     * @return false for an empty player set, which is the right answer: a city with no members
     *         has nobody to share a connection with
     */
    public CompletableFuture<Boolean> anyShares(Collection<UUID> players, String loginHash) {
        if (players.isEmpty() || loginHash == null || loginHash.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        String placeholders = players.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        Object[] params = new Object[players.size() + 1];
        params[0] = loginHash;
        int index = 1;
        for (UUID player : players) {
            params[index++] = player;
        }
        return db.call(connection -> !queryListSync(connection,
                "SELECT uuid FROM player_logins WHERE login_hash = ? AND uuid IN (" + placeholders + ") "
                        + "LIMIT 1",
                rs -> uuid(rs, "uuid"), params).isEmpty());
    }

    public CompletableFuture<List<PlayerLoginRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM player_logins");
    }

    public CompletableFuture<Integer> delete(UUID player) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM player_logins WHERE uuid = ?", player));
    }
}
