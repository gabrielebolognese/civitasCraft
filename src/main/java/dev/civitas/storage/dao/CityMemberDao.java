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
import dev.civitas.storage.row.CityMemberRow;

/** {@code city_members}, SPEC 3.9. */
public final class CityMemberDao extends Dao<CityMemberRow> {

    private static final String COLUMNS = "uuid, city_id, rank_id, joined_at, contributed_total";

    public CityMemberDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "city_members";
    }

    @Override
    protected CityMemberRow map(ResultSet rs) throws SQLException {
        return new CityMemberRow(
                uuid(rs, "uuid"),
                rs.getInt("city_id"),
                rs.getInt("rank_id"),
                rs.getLong("joined_at"),
                money(rs, "contributed_total"));
    }

    public CompletableFuture<Optional<CityMemberRow>> findByUuid(UUID uuid) {
        return db.call(connection -> findByUuid(connection, uuid));
    }

    public Optional<CityMemberRow> findByUuid(Connection connection, UUID uuid) throws SQLException {
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM city_members WHERE uuid = ?", this::map, uuid);
    }

    public CompletableFuture<List<CityMemberRow>> findByCity(int cityId) {
        return db.call(connection -> findByCity(connection, cityId));
    }

    public List<CityMemberRow> findByCity(Connection connection, int cityId) throws SQLException {
        return queryListSync(connection,
                "SELECT " + COLUMNS + " FROM city_members WHERE city_id = ? ORDER BY joined_at",
                this::map, cityId);
    }

    /** Feeds the SPEC 8.5 contribution leaderboard. */
    public CompletableFuture<List<CityMemberRow>> findByCityOrderedByContribution(int cityId) {
        return queryList("SELECT " + COLUMNS + " FROM city_members WHERE city_id = ? "
                + "ORDER BY contributed_total DESC", cityId);
    }

    public CompletableFuture<Integer> countByCity(int cityId) {
        return db.call(connection -> countByCity(connection, cityId));
    }

    public int countByCity(Connection connection, int cityId) throws SQLException {
        return queryOneSync(connection,
                "SELECT COUNT(*) AS total FROM city_members WHERE city_id = ?",
                rs -> rs.getInt("total"), cityId).orElse(0);
    }

    public CompletableFuture<Integer> insert(CityMemberRow row) {
        return db.call(connection -> insert(connection, row));
    }

    public int insert(Connection connection, CityMemberRow row) throws SQLException {
        return updateSync(connection,
                "INSERT INTO city_members (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)",
                row.uuid(), row.cityId(), row.rankId(), row.joinedAt(), row.contributedTotal());
    }

    public CompletableFuture<Integer> updateRank(UUID uuid, int rankId) {
        return db.call(connection -> updateRank(connection, uuid, rankId));
    }

    public int updateRank(Connection connection, UUID uuid, int rankId) throws SQLException {
        return updateSync(connection,
                "UPDATE city_members SET rank_id = ? WHERE uuid = ?", rankId, uuid);
    }

    /** Adds to lifetime contribution in one statement, so concurrent deposits both count. */
    public CompletableFuture<Integer> addContribution(UUID uuid, BigDecimal amount) {
        return db.call(connection -> addContribution(connection, uuid, amount));
    }

    public int addContribution(Connection connection, UUID uuid, BigDecimal amount) throws SQLException {
        return updateSync(connection,
                "UPDATE city_members SET contributed_total = contributed_total + ? WHERE uuid = ?",
                amount, uuid);
    }

    /** Moves every member of {@code fromRankId} onto another rank, used when deleting a rank. */
    public CompletableFuture<Integer> reassignRank(int fromRankId, int toRankId) {
        return db.call(connection -> updateSync(connection,
                "UPDATE city_members SET rank_id = ? WHERE rank_id = ?", toRankId, fromRankId));
    }

    public CompletableFuture<Integer> delete(UUID uuid) {
        return db.call(connection -> delete(connection, uuid));
    }

    public int delete(Connection connection, UUID uuid) throws SQLException {
        return updateSync(connection, "DELETE FROM city_members WHERE uuid = ?", uuid);
    }

    public CompletableFuture<Integer> deleteByCity(int cityId) {
        return db.call(connection ->
                updateSync(connection, "DELETE FROM city_members WHERE city_id = ?", cityId));
    }
}
