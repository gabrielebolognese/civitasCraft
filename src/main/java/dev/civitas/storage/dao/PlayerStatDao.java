package dev.civitas.storage.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.PlayerStatRow;
import dev.civitas.storage.row.RankedTotalRow;

/**
 * {@code player_stats}, the lifetime counters behind SPEC 13.3's Builder and Farmer boards.
 *
 * <p>Counters only ever move up, and only by addition. There is no setter, deliberately: a
 * leaderboard whose numbers can be assigned is a leaderboard an admin can be accused of
 * rigging, and SPEC 1.5 makes auditability a pillar. An admin correction belongs in a later
 * milestone with an {@code audit_log} row behind it.
 */
public final class PlayerStatDao extends Dao<PlayerStatRow> {

    private static final String COLUMNS = "uuid, stat, value, updated_at";

    public PlayerStatDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "player_stats";
    }

    @Override
    protected PlayerStatRow map(ResultSet rs) throws SQLException {
        return new PlayerStatRow(
                uuid(rs, "uuid"),
                rs.getString("stat"),
                rs.getLong("value"),
                rs.getLong("updated_at"));
    }

    public CompletableFuture<List<PlayerStatRow>> findByPlayer(UUID player) {
        return queryList("SELECT " + COLUMNS + " FROM player_stats WHERE uuid = ?", player);
    }

    public CompletableFuture<Optional<PlayerStatRow>> find(UUID player, String stat) {
        return queryOne("SELECT " + COLUMNS + " FROM player_stats WHERE uuid = ? AND stat = ?",
                player, stat);
    }

    /**
     * Adds {@code delta} to a counter, creating the row if this is the player's first.
     *
     * <p>The update is SQL arithmetic rather than read-modify-write, so two flushes racing
     * cannot lose one another's increment. The insert runs only when the update matched
     * nothing, which is the same shape {@code MarketStockDao.upsertDefinition} uses and the
     * only one that works identically on both dialects.
     */
    public CompletableFuture<Integer> add(UUID player, String stat, long delta, long now) {
        return db.transaction(connection -> add(connection, player, stat, delta, now));
    }

    public int add(Connection connection, UUID player, String stat, long delta, long now)
            throws SQLException {
        int updated = updateSync(connection,
                "UPDATE player_stats SET value = value + ?, updated_at = ? WHERE uuid = ? AND stat = ?",
                delta, now, player, stat);
        if (updated > 0) {
            return updated;
        }
        return updateSync(connection,
                "INSERT INTO player_stats (" + COLUMNS + ") VALUES (?, ?, ?, ?)",
                player, stat, delta, now);
    }

    /**
     * Applies a whole batch of increments in one transaction.
     *
     * <p>What {@code StatsService} flushes. One transaction rather than one per player
     * because a flush of a busy server is hundreds of small updates, and committing each
     * separately would be hundreds of fsyncs.
     *
     * @param deltas counter name to the amount to add, per player
     * @return how many statements were applied
     */
    public CompletableFuture<Integer> addAll(Map<UUID, Map<String, Long>> deltas, long now) {
        if (deltas.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return db.transaction(connection -> {
            int applied = 0;
            for (Map.Entry<UUID, Map<String, Long>> player : deltas.entrySet()) {
                for (Map.Entry<String, Long> counter : player.getValue().entrySet()) {
                    if (counter.getValue() == 0L) {
                        continue;
                    }
                    applied += add(connection, player.getKey(), counter.getKey(),
                            counter.getValue(), now);
                }
            }
            return applied;
        });
    }

    /**
     * The top {@code limit} players for one counter, highest first.
     *
     * <p>Joined to {@code players} so the board has a name to print without a second lookup,
     * and inner-joined deliberately: a counter whose player row is gone belongs to nobody and
     * cannot be displayed.
     *
     * <p>The value is read with {@code getLong}, never through the money reader. It is a
     * count, and on SQLite the money reader would divide it by a hundred.
     */
    public CompletableFuture<List<RankedTotalRow>> topByStat(String stat, int limit) {
        return db.call(connection -> queryListSync(connection,
                "SELECT s.uuid AS uuid, p.last_known_name AS name, s.value AS value "
                        + "FROM player_stats s "
                        + "JOIN players p ON p.uuid = s.uuid "
                        + "WHERE s.stat = ? AND s.value > 0 "
                        + "ORDER BY s.value DESC, p.last_known_name ASC LIMIT ?",
                rs -> new RankedTotalRow(
                        uuid(rs, "uuid"),
                        rs.getString("name"),
                        BigDecimal.valueOf(rs.getLong("value"))),
                stat, limit));
    }
}
