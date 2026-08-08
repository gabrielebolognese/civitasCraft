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
import dev.civitas.storage.row.MiningClaimRow;

/**
 * {@code mining_claims} and {@code mining_claim_trust}, SPEC 32.6.
 *
 * <p>The unique index on {@code (world, chunk_x, chunk_z)} is the physical guarantee that two
 * players can never own one chunk, exactly as SPEC 3.4 gives city claims. A race is settled by
 * the database rather than by a lock, and the loser is refunded.
 */
public final class MiningClaimDao extends Dao<MiningClaimRow> {

    private static final String COLUMNS =
            "id, uuid, world, chunk_x, chunk_z, claimed_at, cost_paid, delinquent_since";

    public MiningClaimDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "mining_claims";
    }

    @Override
    protected MiningClaimRow map(ResultSet rs) throws SQLException {
        long delinquent = rs.getLong("delinquent_since");
        boolean wasNull = rs.wasNull();
        return new MiningClaimRow(
                rs.getLong("id"),
                uuid(rs, "uuid"),
                rs.getString("world"),
                rs.getInt("chunk_x"),
                rs.getInt("chunk_z"),
                rs.getLong("claimed_at"),
                money(rs, "cost_paid"),
                wasNull ? null : delinquent);
    }

    public CompletableFuture<List<MiningClaimRow>> findAll() {
        return queryList("SELECT " + COLUMNS + " FROM mining_claims ORDER BY id");
    }

    public Optional<MiningClaimRow> findAtSync(Connection connection, String world, int chunkX,
                                               int chunkZ) throws SQLException {
        return queryOneSync(connection,
                "SELECT " + COLUMNS + " FROM mining_claims "
                        + "WHERE world = ? AND chunk_x = ? AND chunk_z = ?",
                this::map, world, chunkX, chunkZ);
    }

    public List<MiningClaimRow> findOwnedSync(Connection connection, UUID owner)
            throws SQLException {
        return queryListSync(connection,
                "SELECT " + COLUMNS + " FROM mining_claims WHERE uuid = ? ORDER BY id",
                this::map, owner.toString());
    }

    public long insertSync(Connection connection, MiningClaimRow row) throws SQLException {
        return insertSync(connection,
                "INSERT INTO mining_claims (uuid, world, chunk_x, chunk_z, claimed_at, "
                        + "cost_paid, delinquent_since) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.uuid().toString(), row.world(), row.chunkX(), row.chunkZ(),
                row.claimedAt(), row.costPaid(), row.delinquentSince());
    }

    public int deleteSync(Connection connection, long id) throws SQLException {
        return updateSync(connection, "DELETE FROM mining_claims WHERE id = ?", id);
    }

    public CompletableFuture<Integer> delete(long id) {
        return db.call(connection -> deleteSync(connection, id));
    }

    /** Marks upkeep unpaid, or clears it. Null clears. */
    public int setDelinquentSync(Connection connection, long id, Long since)
            throws SQLException {
        return updateSync(connection,
                "UPDATE mining_claims SET delinquent_since = ? WHERE id = ?", since, id);
    }

    public CompletableFuture<Integer> setDelinquent(long id, Long since) {
        return db.call(connection -> setDelinquentSync(connection, id, since));
    }

    /** What every mining claim is worth, for the money-supply accounting. */
    public CompletableFuture<BigDecimal> totalInvested() {
        return db.call(connection -> queryOneSync(connection,
                "SELECT COALESCE(SUM(cost_paid), 0) AS total FROM mining_claims",
                rs -> money(rs, "total")).orElse(BigDecimal.ZERO));
    }

    // ==================================================================================
    // Trust, SPEC 32.6
    // ==================================================================================

    public List<UUID> findTrustedSync(Connection connection, UUID owner) throws SQLException {
        return queryListSync(connection,
                "SELECT trusted_uuid FROM mining_claim_trust WHERE owner_uuid = ? "
                        + "ORDER BY granted_at",
                rs -> UUID.fromString(rs.getString("trusted_uuid")), owner.toString());
    }

    public CompletableFuture<java.util.Map<UUID, List<UUID>>> findAllTrust() {
        return db.call(connection -> {
            java.util.Map<UUID, List<UUID>> byOwner = new java.util.HashMap<>();
            for (Object[] pair : queryListSync(connection,
                    "SELECT owner_uuid, trusted_uuid FROM mining_claim_trust "
                            + "ORDER BY granted_at",
                    rs -> new Object[] {UUID.fromString(rs.getString("owner_uuid")),
                            UUID.fromString(rs.getString("trusted_uuid"))})) {
                byOwner.computeIfAbsent((UUID) pair[0], key -> new java.util.ArrayList<>())
                        .add((UUID) pair[1]);
            }
            return byOwner;
        });
    }

    public int trustSync(Connection connection, UUID owner, UUID trusted, long now)
            throws SQLException {
        return updateSync(connection,
                "INSERT INTO mining_claim_trust (owner_uuid, trusted_uuid, granted_at) "
                        + "VALUES (?, ?, ?)",
                owner.toString(), trusted.toString(), now);
    }

    public CompletableFuture<Integer> untrust(UUID owner, UUID trusted) {
        return db.call(connection -> updateSync(connection,
                "DELETE FROM mining_claim_trust WHERE owner_uuid = ? AND trusted_uuid = ?",
                owner.toString(), trusted.toString()));
    }
}
