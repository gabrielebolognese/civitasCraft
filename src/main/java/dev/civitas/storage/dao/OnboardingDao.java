package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;

/**
 * {@code player_onboarding}, SPEC 34.3.
 *
 * <p>The primary key on {@code (uuid, step)} is the idempotency, not a comment: several starter
 * steps hang off events a player can repeat freely, and the database is the only place that can
 * settle two of them arriving in one tick.
 */
public final class OnboardingDao extends Dao<String> {

    public OnboardingDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "player_onboarding";
    }

    @Override
    protected String map(ResultSet rs) throws SQLException {
        return rs.getString("step");
    }

    public CompletableFuture<List<String>> findCompleted(UUID player) {
        return queryList("SELECT step FROM player_onboarding WHERE uuid = ?", player);
    }

    /**
     * Records a step, once.
     *
     * @return 1 when this call was the one that recorded it, 0 when it was already there
     */
    public int insertIfAbsent(Connection connection, UUID player, String step, long now)
            throws SQLException {
        // INSERT OR IGNORE on SQLite is INSERT IGNORE on MySQL, so neither is used: a SELECT
        // inside the same transaction gives the same answer on both, and the primary key is
        // still the thing that makes it true under concurrency.
        boolean present = queryOneSync(connection,
                "SELECT step FROM player_onboarding WHERE uuid = ? AND step = ?",
                this::map, player, step).isPresent();
        if (present) {
            return 0;
        }
        return updateSync(connection,
                "INSERT INTO player_onboarding (uuid, step, completed_at) VALUES (?, ?, ?)",
                player, step, now);
    }

    public int countCompleted(Connection connection, UUID player) throws SQLException {
        return queryOneSync(connection,
                "SELECT COUNT(*) AS total FROM player_onboarding WHERE uuid = ?",
                rs -> rs.getInt("total"), player).orElse(0);
    }

    /** Exposed so the service can run the insert and the payment in one transaction. */
    public <R> CompletableFuture<R> transaction(
            dev.civitas.storage.SqlFunction<Connection, R> work) {
        return db.transaction(work);
    }
}
