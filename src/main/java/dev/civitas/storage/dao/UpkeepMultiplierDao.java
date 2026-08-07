package dev.civitas.storage.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.UpkeepMultiplierRow;

/** {@code city_upkeep_multipliers}, SPEC 9.4.2. */
public final class UpkeepMultiplierDao extends Dao<UpkeepMultiplierRow> {

    private static final String COLUMNS =
            "city_id, multiplier, set_by, set_at, expires_at, reason";

    public UpkeepMultiplierDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "city_upkeep_multipliers";
    }

    @Override
    protected UpkeepMultiplierRow map(ResultSet rs) throws SQLException {
        return new UpkeepMultiplierRow(
                rs.getInt("city_id"),
                rs.getDouble("multiplier"),
                nullableUuid(rs, "set_by"),
                rs.getLong("set_at"),
                nullableLong(rs, "expires_at"),
                rs.getString("reason"));
    }

    /** Every override, read once at startup into memory. */
    public CompletableFuture<List<UpkeepMultiplierRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM city_upkeep_multipliers");
    }

    public CompletableFuture<Integer> set(UpkeepMultiplierRow row) {
        return db.call(connection -> {
            updateSync(connection, "DELETE FROM city_upkeep_multipliers WHERE city_id = ?",
                    row.cityId());
            return updateSync(connection,
                    "INSERT INTO city_upkeep_multipliers (" + COLUMNS + ") "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    row.cityId(), row.multiplier(), row.setBy(), row.setAt(),
                    row.expiresAt(), row.reason());
        });
    }

    public CompletableFuture<Integer> clear(int cityId) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM city_upkeep_multipliers WHERE city_id = ?", cityId));
    }
}
