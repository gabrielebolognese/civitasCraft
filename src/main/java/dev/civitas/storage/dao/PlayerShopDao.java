package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.PlayerShopRow;

/** {@code player_shops}, added in V4 for SPEC 4.5. */
public final class PlayerShopDao extends Dao<PlayerShopRow> {

    private static final String COLUMNS = "id, owner_uuid, world, sign_x, sign_y, sign_z, "
            + "chest_x, chest_y, chest_z, material, quantity, buy_price, sell_price, created_at";

    private static final String INSERT_COLUMNS = "owner_uuid, world, sign_x, sign_y, sign_z, "
            + "chest_x, chest_y, chest_z, material, quantity, buy_price, sell_price, created_at";

    public PlayerShopDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "player_shops";
    }

    @Override
    protected PlayerShopRow map(ResultSet rs) throws SQLException {
        return new PlayerShopRow(
                rs.getLong("id"),
                uuid(rs, "owner_uuid"),
                rs.getString("world"),
                rs.getInt("sign_x"),
                rs.getInt("sign_y"),
                rs.getInt("sign_z"),
                rs.getInt("chest_x"),
                rs.getInt("chest_y"),
                rs.getInt("chest_z"),
                rs.getString("material"),
                rs.getInt("quantity"),
                nullableMoney(rs, "buy_price"),
                nullableMoney(rs, "sell_price"),
                rs.getLong("created_at"));
    }

    public CompletableFuture<List<PlayerShopRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM player_shops ORDER BY id");
    }

    public CompletableFuture<List<PlayerShopRow>> findByOwner(UUID owner) {
        return queryList("SELECT " + COLUMNS + " FROM player_shops WHERE owner_uuid = ? "
                + "ORDER BY id", owner);
    }

    public CompletableFuture<Long> countByOwner(UUID owner) {
        return db.call(connection -> countByOwner(connection, owner));
    }

    public long countByOwner(Connection connection, UUID owner) throws SQLException {
        return queryOneSync(connection,
                "SELECT COUNT(*) AS total FROM player_shops WHERE owner_uuid = ?",
                rs -> rs.getLong("total"), owner)
                .orElse(0L);
    }

    public CompletableFuture<Optional<PlayerShopRow>> findBySign(String world, int x, int y, int z) {
        return queryOne("SELECT " + COLUMNS + " FROM player_shops "
                + "WHERE world = ? AND sign_x = ? AND sign_y = ? AND sign_z = ?", world, x, y, z);
    }

    public CompletableFuture<List<PlayerShopRow>> findByChest(String world, int x, int y, int z) {
        return queryList("SELECT " + COLUMNS + " FROM player_shops "
                + "WHERE world = ? AND chest_x = ? AND chest_y = ? AND chest_z = ?",
                world, x, y, z);
    }

    public CompletableFuture<Long> insert(PlayerShopRow row) {
        return db.call(connection -> insert(connection, row));
    }

    /** @return the generated shop id */
    public long insert(Connection connection, PlayerShopRow row) throws SQLException {
        return insertSync(connection,
                "INSERT INTO player_shops (" + INSERT_COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.ownerUuid(), row.world(), row.signX(), row.signY(), row.signZ(),
                row.chestX(), row.chestY(), row.chestZ(), row.material(), row.quantity(),
                row.buyPrice(), row.sellPrice(), row.createdAt());
    }

    public CompletableFuture<Integer> delete(long id) {
        return db.call(connection -> delete(connection, id));
    }

    public int delete(Connection connection, long id) throws SQLException {
        return updateSync(connection, "DELETE FROM player_shops WHERE id = ?", id);
    }

    /** Used when a chest is destroyed: every shop sign pointing at it goes with it. */
    public CompletableFuture<Integer> deleteByChest(String world, int x, int y, int z) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM player_shops WHERE world = ? AND chest_x = ? AND chest_y = ? "
                        + "AND chest_z = ?", world, x, y, z));
    }
}
