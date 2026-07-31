package dev.civitas.storage.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.CityRow;

/** {@code cities}, SPEC 3.2. */
public final class CityDao extends Dao<CityRow> {

    private static final String COLUMNS =
            "id, name, display_name, tag, mayor_uuid, founded_at, treasury, core_world, "
                    + "core_chunk_x, core_chunk_z, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch, "
                    + "open_join, motd, upkeep_due, delinquent_since, war_protection_until, frozen, "
                    + "deleted_at";

    private static final String INSERT_COLUMNS =
            "name, display_name, tag, mayor_uuid, founded_at, treasury, core_world, "
                    + "core_chunk_x, core_chunk_z, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch, "
                    + "open_join, motd, upkeep_due, delinquent_since, war_protection_until, frozen, "
                    + "deleted_at";

    public CityDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "cities";
    }

    @Override
    protected CityRow map(ResultSet rs) throws SQLException {
        return new CityRow(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("display_name"),
                rs.getString("tag"),
                uuid(rs, "mayor_uuid"),
                rs.getLong("founded_at"),
                money(rs, "treasury"),
                rs.getString("core_world"),
                rs.getInt("core_chunk_x"),
                rs.getInt("core_chunk_z"),
                rs.getDouble("spawn_x"),
                rs.getDouble("spawn_y"),
                rs.getDouble("spawn_z"),
                rs.getFloat("spawn_yaw"),
                rs.getFloat("spawn_pitch"),
                rs.getBoolean("open_join"),
                rs.getString("motd"),
                rs.getLong("upkeep_due"),
                nullableLong(rs, "delinquent_since"),
                rs.getLong("war_protection_until"),
                rs.getBoolean("frozen"),
                nullableLong(rs, "deleted_at"));
    }

    public CompletableFuture<Optional<CityRow>> findById(int id) {
        return db.call(connection -> findById(connection, id));
    }

    public Optional<CityRow> findById(Connection connection, int id) throws SQLException {
        return queryOneSync(connection, "SELECT " + COLUMNS + " FROM cities WHERE id = ?", this::map, id);
    }

    /**
     * Case-insensitive, matching the SPEC 5.1 precondition that a name is taken regardless
     * of capitalisation. The column collation makes the index agree with this query.
     */
    public CompletableFuture<Optional<CityRow>> findByName(String name) {
        return db.call(connection -> findByName(connection, name));
    }

    public Optional<CityRow> findByName(Connection connection, String name) throws SQLException {
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM cities WHERE LOWER(name) = LOWER(?)", this::map, name);
    }

    public CompletableFuture<Optional<CityRow>> findByTag(String tag) {
        return db.call(connection -> findByTag(connection, tag));
    }

    public Optional<CityRow> findByTag(Connection connection, String tag) throws SQLException {
        if (tag == null) {
            return Optional.empty();
        }
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM cities WHERE LOWER(tag) = LOWER(?)", this::map, tag);
    }

    /** Live cities only. Soft-deleted rows are excluded, SPEC 5.3. */
    public CompletableFuture<List<CityRow>> findAllActive() {
        return queryList("SELECT " + COLUMNS + " FROM cities WHERE deleted_at IS NULL ORDER BY id");
    }

    /** Soft-deleted cities still inside their restore window, SPEC 9.4.2. */
    public CompletableFuture<List<CityRow>> findDeletedSince(long deletedAfter) {
        return queryList("SELECT " + COLUMNS + " FROM cities "
                + "WHERE deleted_at IS NOT NULL AND deleted_at >= ? ORDER BY deleted_at DESC",
                deletedAfter);
    }

    /** Cities whose next upkeep charge is due, SPEC 4.3. */
    public CompletableFuture<List<CityRow>> findUpkeepDue(long now) {
        return queryList("SELECT " + COLUMNS + " FROM cities "
                + "WHERE deleted_at IS NULL AND upkeep_due <= ? ORDER BY upkeep_due", now);
    }

    public CompletableFuture<Integer> insert(CityRow row) {
        return db.call(connection -> insert(connection, row));
    }

    /** @return the generated city id */
    public int insert(Connection connection, CityRow row) throws SQLException {
        long id = insertSync(connection,
                "INSERT INTO cities (" + INSERT_COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.name(), row.displayName(), row.tag(), row.mayorUuid(), row.foundedAt(),
                row.treasury(), row.coreWorld(), row.coreChunkX(), row.coreChunkZ(),
                row.spawnX(), row.spawnY(), row.spawnZ(), row.spawnYaw(), row.spawnPitch(),
                row.openJoin(), row.motd(), row.upkeepDue(), row.delinquentSince(),
                row.warProtectionUntil(), row.frozen(), row.deletedAt());
        return Math.toIntExact(id);
    }

    public CompletableFuture<Integer> update(CityRow row) {
        return db.call(connection -> update(connection, row));
    }

    public int update(Connection connection, CityRow row) throws SQLException {
        return updateSync(connection,
                "UPDATE cities SET name = ?, display_name = ?, tag = ?, mayor_uuid = ?, "
                        + "founded_at = ?, treasury = ?, core_world = ?, core_chunk_x = ?, "
                        + "core_chunk_z = ?, spawn_x = ?, spawn_y = ?, spawn_z = ?, spawn_yaw = ?, "
                        + "spawn_pitch = ?, open_join = ?, motd = ?, upkeep_due = ?, "
                        + "delinquent_since = ?, war_protection_until = ?, frozen = ?, deleted_at = ? "
                        + "WHERE id = ?",
                row.name(), row.displayName(), row.tag(), row.mayorUuid(), row.foundedAt(),
                row.treasury(), row.coreWorld(), row.coreChunkX(), row.coreChunkZ(),
                row.spawnX(), row.spawnY(), row.spawnZ(), row.spawnYaw(), row.spawnPitch(),
                row.openJoin(), row.motd(), row.upkeepDue(), row.delinquentSince(),
                row.warProtectionUntil(), row.frozen(), row.deletedAt(), row.id());
    }

    /** Single-column treasury write, so it cannot race an unrelated edit to the same row. */
    public CompletableFuture<Integer> updateTreasury(int cityId, BigDecimal treasury) {
        return db.call(connection -> updateTreasury(connection, cityId, treasury));
    }

    public int updateTreasury(Connection connection, int cityId, BigDecimal treasury) throws SQLException {
        return updateSync(connection, "UPDATE cities SET treasury = ? WHERE id = ?", treasury, cityId);
    }

    /** Soft delete, SPEC 5.3. The row is retained for the admin restore window. */
    public CompletableFuture<Integer> softDelete(int cityId, long deletedAt) {
        return db.call(connection -> softDelete(connection, cityId, deletedAt));
    }

    public int softDelete(Connection connection, int cityId, long deletedAt) throws SQLException {
        return updateSync(connection,
                "UPDATE cities SET deleted_at = ? WHERE id = ?", deletedAt, cityId);
    }

    public CompletableFuture<Integer> restore(int cityId) {
        return db.call(connection ->
                updateSync(connection, "UPDATE cities SET deleted_at = NULL WHERE id = ?", cityId));
    }

    /**
     * Hard delete. Used only by an admin purge and by tests; ordinary disbanding is a soft
     * delete so SPEC 5.3's 14-day restore window still works.
     */
    public CompletableFuture<Integer> hardDelete(int cityId) {
        return db.call(connection -> updateSync(connection, "DELETE FROM cities WHERE id = ?", cityId));
    }

    /** Total money held in treasuries, for the SPEC 4.8 circulation check. */
    public CompletableFuture<BigDecimal> totalTreasuries() {
        return db.call(connection -> queryOneSync(connection,
                "SELECT COALESCE(SUM(treasury), 0) AS total FROM cities WHERE deleted_at IS NULL",
                rs -> money(rs, "total")).orElse(BigDecimal.ZERO));
    }
}
