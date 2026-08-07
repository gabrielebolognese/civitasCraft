package dev.civitas.storage.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.row.BountyRow;

/** {@code bounties}, SPEC 4.7. */
public final class BountyDao extends Dao<BountyRow> {

    private static final String COLUMNS = "id, placer_uuid, target_uuid, amount, placed_at, "
            + "expires_at, state, claimed_by, claimed_at";

    /** SPEC 4.7's three states. A row never leaves the table, only changes state. */
    public static final String OPEN = "OPEN";
    public static final String CLAIMED = "CLAIMED";
    public static final String REFUNDED = "REFUNDED";

    public BountyDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "bounties";
    }

    @Override
    protected BountyRow map(ResultSet rs) throws SQLException {
        return new BountyRow(
                rs.getLong("id"),
                uuid(rs, "placer_uuid"),
                uuid(rs, "target_uuid"),
                money(rs, "amount"),
                rs.getLong("placed_at"),
                rs.getLong("expires_at"),
                rs.getString("state"),
                nullableUuid(rs, "claimed_by"),
                nullableLong(rs, "claimed_at"));
    }

    public CompletableFuture<Long> insert(BountyRow row) {
        return db.call(connection -> insert(connection, row));
    }

    public long insert(Connection connection, BountyRow row) throws SQLException {
        return insertSync(connection,
                "INSERT INTO bounties (placer_uuid, target_uuid, amount, placed_at, expires_at, "
                        + "state, claimed_by, claimed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.placerUuid(), row.targetUuid(), row.amount(), row.placedAt(),
                row.expiresAt(), row.state(), row.claimedBy(), row.claimedAt());
    }

    /** Every open bounty on one player, newest first. */
    public CompletableFuture<List<BountyRow>> findOpenOn(UUID target) {
        return queryList("SELECT " + COLUMNS + " FROM bounties WHERE target_uuid = ? "
                + "AND state = ? ORDER BY placed_at DESC", target, OPEN);
    }

    public List<BountyRow> findOpenOnSync(Connection connection, UUID target)
            throws SQLException {
        return queryListSync(connection, "SELECT " + COLUMNS + " FROM bounties "
                + "WHERE target_uuid = ? AND state = ? ORDER BY placed_at", this::map,
                target, OPEN);
    }

    /** Every open bounty, for {@code /bounty list}. */
    public CompletableFuture<List<BountyRow>> findAllOpen(int limit) {
        return queryList("SELECT " + COLUMNS + " FROM bounties WHERE state = ? "
                + "ORDER BY amount DESC LIMIT ?", OPEN, limit);
    }

    /** What one player has staked and not yet had settled. */
    public CompletableFuture<List<BountyRow>> findOpenBy(UUID placer) {
        return queryList("SELECT " + COLUMNS + " FROM bounties WHERE placer_uuid = ? "
                + "AND state = ? ORDER BY placed_at", placer, OPEN);
    }

    /** SPEC 4.7: bounties expire after 30 days and refund. */
    public CompletableFuture<List<BountyRow>> findExpired(long now) {
        return queryList("SELECT " + COLUMNS + " FROM bounties WHERE state = ? "
                + "AND expires_at <= ? ORDER BY expires_at", OPEN, now);
    }

    public int settle(Connection connection, long id, String state, UUID claimedBy,
                      Long claimedAt) throws SQLException {
        // The state is part of the WHERE clause, so two claims of the same bounty cannot both
        // pay out: the second updates no rows and the caller sees it.
        return updateSync(connection,
                "UPDATE bounties SET state = ?, claimed_by = ?, claimed_at = ? "
                        + "WHERE id = ? AND state = ?",
                state, claimedBy, claimedAt, id, OPEN);
    }

    public CompletableFuture<Integer> settle(long id, String state, UUID claimedBy,
                                             Long claimedAt) {
        return db.call(connection -> settle(connection, id, state, claimedBy, claimedAt));
    }
}
