package dev.civitas.storage.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.PlayerRow;

/** {@code players}, SPEC 3.1. */
public final class PlayerDao extends Dao<PlayerRow> {

    private static final String COLUMNS =
            "uuid, last_known_name, balance, city_id, rank_id, first_join, last_seen, "
                    + "total_playtime_ms, active_playtime_ms, daily_streak, last_daily_claim, "
                    + "newcomer_until, frozen, last_city_leave, last_city_disband";

    public PlayerDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "players";
    }

    @Override
    protected PlayerRow map(ResultSet rs) throws SQLException {
        return new PlayerRow(
                uuid(rs, "uuid"),
                rs.getString("last_known_name"),
                money(rs, "balance"),
                nullableInt(rs, "city_id"),
                nullableInt(rs, "rank_id"),
                rs.getLong("first_join"),
                rs.getLong("last_seen"),
                rs.getLong("total_playtime_ms"),
                rs.getLong("active_playtime_ms"),
                rs.getInt("daily_streak"),
                rs.getLong("last_daily_claim"),
                rs.getLong("newcomer_until"),
                rs.getBoolean("frozen"),
                rs.getLong("last_city_leave"),
                rs.getLong("last_city_disband"));
    }

    public CompletableFuture<Optional<PlayerRow>> findByUuid(UUID uuid) {
        return db.call(connection -> findByUuid(connection, uuid));
    }

    public Optional<PlayerRow> findByUuid(Connection connection, UUID uuid) throws SQLException {
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM players WHERE uuid = ?", this::map, uuid);
    }

    /** Case-insensitive, because players type names as they remember them. */
    public CompletableFuture<Optional<PlayerRow>> findByName(String name) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM players WHERE LOWER(last_known_name) = LOWER(?)",
                this::map, name));
    }

    public CompletableFuture<List<PlayerRow>> findByCity(int cityId) {
        return queryList("SELECT " + COLUMNS + " FROM players WHERE city_id = ?", cityId);
    }

    /**
     * Every player who currently belongs to a city.
     *
     * <p>Read once at startup to seed the active-member counts the SPEC 6.2 claim price
     * divides by, so pricing a chunk never needs a database round trip.
     */
    public CompletableFuture<List<PlayerRow>> findAllWithCity() {
        return queryList("SELECT " + COLUMNS + " FROM players WHERE city_id IS NOT NULL");
    }

    /** Feeds the SPEC 13.3 Wealth leaderboard. */
    public CompletableFuture<List<PlayerRow>> findTopByBalance(int limit) {
        return queryList("SELECT " + COLUMNS + " FROM players ORDER BY balance DESC LIMIT ?", limit);
    }

    public CompletableFuture<Integer> insert(PlayerRow row) {
        return db.call(connection -> insert(connection, row));
    }

    public int insert(Connection connection, PlayerRow row) throws SQLException {
        return updateSync(connection,
                "INSERT INTO players (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.uuid(), row.lastKnownName(), row.balance(), row.cityId(), row.rankId(),
                row.firstJoin(), row.lastSeen(), row.totalPlaytimeMs(), row.activePlaytimeMs(),
                row.dailyStreak(), row.lastDailyClaim(), row.newcomerUntil(), row.frozen(),
                row.lastCityLeave(), row.lastCityDisband());
    }

    public CompletableFuture<Integer> update(PlayerRow row) {
        return db.call(connection -> update(connection, row));
    }

    public int update(Connection connection, PlayerRow row) throws SQLException {
        return updateSync(connection,
                "UPDATE players SET last_known_name = ?, balance = ?, city_id = ?, rank_id = ?, "
                        + "first_join = ?, last_seen = ?, total_playtime_ms = ?, active_playtime_ms = ?, "
                        + "daily_streak = ?, last_daily_claim = ?, newcomer_until = ?, frozen = ?, "
                        + "last_city_leave = ?, last_city_disband = ? "
                        + "WHERE uuid = ?",
                row.lastKnownName(), row.balance(), row.cityId(), row.rankId(),
                row.firstJoin(), row.lastSeen(), row.totalPlaytimeMs(), row.activePlaytimeMs(),
                row.dailyStreak(), row.lastDailyClaim(), row.newcomerUntil(), row.frozen(),
                row.lastCityLeave(), row.lastCityDisband(),
                row.uuid());
    }

    /**
     * Sets a balance in one statement.
     *
     * <p>Separate from {@link #update} so a balance change never races a concurrent edit to
     * an unrelated column such as playtime.
     */
    public CompletableFuture<Integer> updateBalance(UUID uuid, BigDecimal balance) {
        return db.call(connection -> updateBalance(connection, uuid, balance));
    }

    public int updateBalance(Connection connection, UUID uuid, BigDecimal balance) throws SQLException {
        return updateSync(connection, "UPDATE players SET balance = ? WHERE uuid = ?", balance, uuid);
    }

    /** Sets the city and rank a player belongs to, or clears both when passed nulls. */
    public CompletableFuture<Integer> updateCity(UUID uuid, Integer cityId, Integer rankId) {
        return db.call(connection -> updateCity(connection, uuid, cityId, rankId));
    }

    public int updateCity(Connection connection, UUID uuid, Integer cityId, Integer rankId)
            throws SQLException {
        return updateSync(connection,
                "UPDATE players SET city_id = ?, rank_id = ? WHERE uuid = ?", cityId, rankId, uuid);
    }

    /** Stamps the SPEC 5.2 city-switch cooldown. */
    public int updateLastCityLeave(Connection connection, UUID uuid, long timestamp) throws SQLException {
        return updateSync(connection,
                "UPDATE players SET last_city_leave = ? WHERE uuid = ?", timestamp, uuid);
    }

    /** Stamps the SPEC 17.1 case 7 cooldown before the player may found another city. */
    public int updateLastCityDisband(Connection connection, UUID uuid, long timestamp) throws SQLException {
        return updateSync(connection,
                "UPDATE players SET last_city_disband = ? WHERE uuid = ?", timestamp, uuid);
    }

    /** Clears city and rank for every member of a city, used when a city is disbanded. */
    public CompletableFuture<Integer> clearCity(int cityId) {
        return db.call(connection -> clearCity(connection, cityId));
    }

    public int clearCity(Connection connection, int cityId) throws SQLException {
        return updateSync(connection,
                "UPDATE players SET city_id = NULL, rank_id = NULL WHERE city_id = ?", cityId);
    }

    /** Adds to a balance in one statement, so two concurrent credits both land. */
    public int addBalance(Connection connection, UUID uuid, BigDecimal delta) throws SQLException {
        return updateSync(connection,
                "UPDATE players SET balance = balance + ? WHERE uuid = ?", delta, uuid);
    }

    public CompletableFuture<Integer> delete(UUID uuid) {
        return db.call(connection -> updateSync(connection, "DELETE FROM players WHERE uuid = ?", uuid));
    }

    /** Total money held by players, for the SPEC 4.8 circulation check. */
    public CompletableFuture<BigDecimal> totalCirculation() {
        return db.call(connection -> queryOneSync(connection,
                "SELECT COALESCE(SUM(balance), 0) AS total FROM players",
                rs -> money(rs, "total")).orElse(BigDecimal.ZERO));
    }
}
