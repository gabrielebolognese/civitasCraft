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
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.storage.row.RankedTotalRow;

/**
 * {@code ledger}, SPEC 3.6.
 *
 * <p>Append-only by design and by omission: this class offers no update and no delete,
 * because SPEC 1.5 makes the ledger the record admins reconstruct disputes from, and a
 * record that can be edited is not evidence. Reversing a transaction (SPEC 9.4.4
 * {@code /ca eco rollback}) writes compensating entries instead.
 */
public final class LedgerDao extends Dao<LedgerRow> {

    private static final String COLUMNS =
            "id, timestamp, type, actor_uuid, target_uuid, city_id, amount, balance_after, metadata";

    private static final String INSERT_COLUMNS =
            "timestamp, type, actor_uuid, target_uuid, city_id, amount, balance_after, metadata";

    public LedgerDao(DatabaseManager db) {
        super(db);
    }

    @Override
    public String table() {
        return "ledger";
    }

    @Override
    protected LedgerRow map(ResultSet rs) throws SQLException {
        return new LedgerRow(
                rs.getLong("id"),
                rs.getLong("timestamp"),
                rs.getString("type"),
                nullableUuid(rs, "actor_uuid"),
                nullableUuid(rs, "target_uuid"),
                nullableInt(rs, "city_id"),
                money(rs, "amount"),
                money(rs, "balance_after"),
                rs.getString("metadata"));
    }

    public CompletableFuture<Optional<LedgerRow>> findById(long id) {
        return db.call(connection ->
                queryOneSync(connection, "SELECT " + COLUMNS + " FROM ledger WHERE id = ?", this::map, id));
    }

    /** Every transaction a player was party to, newest first. SPEC 9.4.1. */
    public CompletableFuture<List<LedgerRow>> findByPlayer(UUID player, long since, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM ledger "
                + "WHERE (actor_uuid = ? OR target_uuid = ?) AND timestamp >= ? "
                + "ORDER BY timestamp DESC LIMIT ?", player, player, since, limit);
    }

    public CompletableFuture<List<LedgerRow>> findByCity(int cityId, long since, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM ledger "
                + "WHERE city_id = ? AND timestamp >= ? ORDER BY timestamp DESC LIMIT ?",
                cityId, since, limit);
    }

    public CompletableFuture<List<LedgerRow>> findByType(String type, long since, int limit) {
        return queryList("SELECT " + COLUMNS + " FROM ledger "
                + "WHERE type = ? AND timestamp >= ? ORDER BY timestamp DESC LIMIT ?",
                type, since, limit);
    }

    /**
     * How much one player has taken <em>out</em> under one type, for one city, since a
     * moment in time.
     *
     * <p>This is what makes the SPEC 8.5 withdrawal cap need no extra state: the ledger
     * already records every withdrawal with its actor, its city and its timestamp, so the
     * 24-hour total is a query rather than a counter that could drift.
     *
     * <p>Only negative amounts are counted. A treasury movement writes two rows under the
     * same type, actor and city, one for the side the money left and one for the side it
     * arrived at, so a plain {@code SUM} would net them to exactly zero and the cap would
     * never bite.
     *
     * @return the summed outflow, which is negative or zero
     */
    public CompletableFuture<BigDecimal> sumOutflowByActor(UUID actor, int cityId, String type,
                                                           long since) {
        return db.call(connection -> sumOutflowByActor(connection, actor, cityId, type, since));
    }

    public BigDecimal sumOutflowByActor(Connection connection, UUID actor, int cityId, String type,
                                        long since) throws SQLException {
        return queryOneSync(connection,
                "SELECT COALESCE(SUM(amount), 0) AS total FROM ledger "
                        + "WHERE actor_uuid = ? AND city_id = ? AND type = ? AND timestamp >= ? "
                        + "AND amount < 0",
                rs -> money(rs, "total"), actor, cityId, type, since)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * How much one player has been paid under a type since a moment in time.
     *
     * <p>What the SPEC 4.2 daily stipend cap is measured with. Like the SPEC 8.5 withdrawal
     * cap, deriving it from the ledger rather than from a counter means a restart cannot
     * reset it and the cap cannot disagree with the audit trail.
     *
     * @return the summed amount, zero if there is none
     */
    public CompletableFuture<BigDecimal> sumByActorAndType(UUID actor, String type, long since) {
        return db.call(connection -> queryOneSync(connection,
                "SELECT COALESCE(SUM(amount), 0) AS total FROM ledger "
                        + "WHERE actor_uuid = ? AND type = ? AND timestamp >= ? AND amount > 0",
                rs -> money(rs, "total"), actor, type, since)
                .orElse(BigDecimal.ZERO));
    }

    /**
     * The top {@code limit} players by what they have been credited under one type, over all
     * time, highest first.
     *
     * <p>What SPEC 13.3's Contribution board ranks. It has to come from here rather than from
     * {@code city_members.contributed_total}, which looks like the same number and is not:
     * that row is deleted when a player leaves a city, so it measures what someone has given
     * their <em>current</em> city. SPEC 13.3 asks for a lifetime figure, and the ledger is the
     * only place that keeps one, because SPEC 3.6 never deletes from it.
     *
     * <p>Only positive amounts count, for the reason {@link #sumOutflowByActor} gives: a
     * treasury movement writes both sides under the same type and actor, so an unfiltered
     * {@code SUM} nets to zero.
     */
    public CompletableFuture<List<RankedTotalRow>> topByTypeGroupedByActor(String type, int limit) {
        return db.call(connection -> queryListSync(connection,
                "SELECT l.actor_uuid AS uuid, p.last_known_name AS name, "
                        + "SUM(l.amount) AS total "
                        + "FROM ledger l "
                        + "JOIN players p ON p.uuid = l.actor_uuid "
                        + "WHERE l.type = ? AND l.amount > 0 "
                        + "GROUP BY l.actor_uuid, p.last_known_name "
                        + "ORDER BY total DESC, p.last_known_name ASC LIMIT ?",
                rs -> new RankedTotalRow(
                        uuid(rs, "uuid"),
                        rs.getString("name"),
                        money(rs, "total")),
                type, limit));
    }

    public CompletableFuture<Long> insert(LedgerRow row) {
        return db.call(connection -> insert(connection, row));
    }

    /** @return the generated ledger id */
    public long insert(Connection connection, LedgerRow row) throws SQLException {
        return insertSync(connection,
                "INSERT INTO ledger (" + INSERT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.timestamp(), row.type(), row.actorUuid(), row.targetUuid(), row.cityId(),
                row.amount(), row.balanceAfter(), row.metadata());
    }

    /**
     * Writes many entries in one transaction.
     *
     * <p>Used by batch sinks such as the daily upkeep sweep, where one row per city would
     * otherwise mean hundreds of separate commits.
     */
    public CompletableFuture<Integer> insertAll(List<LedgerRow> rows) {
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return db.transaction(connection -> {
            for (LedgerRow row : rows) {
                insert(connection, row);
            }
            return rows.size();
        });
    }
}
