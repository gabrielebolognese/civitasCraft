package dev.civitas.storage.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.storage.DatabaseManager;
import dev.civitas.storage.RowMapper;
import dev.civitas.storage.SqlDialect;
import dev.civitas.storage.SqlFunction;
import dev.civitas.storage.StorageException;

/**
 * Shared plumbing for the table DAOs.
 *
 * <p>Every mutation comes in two shapes: one that takes a {@link Connection} and runs on the
 * caller's thread, and one that returns a {@link CompletableFuture} and runs on the database
 * pool. The first exists so a service can compose several tables into one transaction, which
 * SPEC 5.1 needs when creating a city writes a city, five ranks, a member and a core claim
 * that must all land together or not at all.
 *
 * @param <T> the row record this DAO reads and writes
 */
public abstract class Dao<T> {

    protected final DatabaseManager db;

    protected Dao(DatabaseManager db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    /** The table this DAO owns. */
    public abstract String table();

    /** Maps the current row of a result set. */
    protected abstract T map(ResultSet resultSet) throws SQLException;

    protected SqlDialect dialect() {
        return db.dialect();
    }

    /** Runs {@code work} in a transaction on the database pool. */
    public <R> CompletableFuture<R> transaction(SqlFunction<Connection, R> work) {
        return db.transaction(work);
    }

    // --- counts and wholesale deletion ------------------------------------------------

    public CompletableFuture<Long> count() {
        return db.call(this::countSync);
    }

    public long countSync(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table());
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    /** Removes every row. Intended for admin purges and for test fixtures. */
    public CompletableFuture<Integer> deleteAll() {
        return db.call(this::deleteAllSync);
    }

    public int deleteAllSync(Connection connection) throws SQLException {
        return updateSync(connection, "DELETE FROM " + table());
    }

    // --- query helpers ----------------------------------------------------------------

    protected CompletableFuture<List<T>> queryList(String sql, Object... params) {
        return db.call(connection -> queryListSync(connection, sql, this::map, params));
    }

    protected CompletableFuture<Optional<T>> queryOne(String sql, Object... params) {
        return db.call(connection -> queryOneSync(connection, sql, this::map, params));
    }

    protected <R> List<R> queryListSync(Connection connection, String sql, RowMapper<R> mapper,
                                        Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<R> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(mapper.map(resultSet));
                }
                return rows;
            }
        }
    }

    protected <R> Optional<R> queryOneSync(Connection connection, String sql, RowMapper<R> mapper,
                                           Object... params) throws SQLException {
        List<R> rows = queryListSync(connection, sql, mapper, params);
        if (rows.size() > 1) {
            throw new StorageException("Expected at most one row from " + table()
                    + " but got " + rows.size());
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    protected int updateSync(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        }
    }

    /**
     * Executes an INSERT and returns its generated key.
     *
     * @throws StorageException if the statement generated no key, which means the table has
     *                          no auto-increment column and the caller wanted the wrong helper
     */
    protected long insertSync(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, params);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new StorageException("INSERT into " + table() + " returned no generated key");
        }
    }

    // --- parameter binding ------------------------------------------------------------

    /**
     * Binds positional parameters.
     *
     * <p>{@link BigDecimal} is routed through {@link SqlDialect#setMoney} because SQLite
     * stores money as integer minor units; binding it as a plain decimal there would lose
     * cents. {@link UUID} is bound as its string form to match the {@code CHAR(36)} columns.
     */
    protected void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            int index = i + 1;
            Object value = params[i];

            switch (value) {
                case null -> statement.setObject(index, null);
                case BigDecimal money -> dialect().setMoney(statement, index, money);
                case UUID uuid -> statement.setString(index, uuid.toString());
                case String text -> statement.setString(index, text);
                case Integer number -> statement.setInt(index, number);
                case Long number -> statement.setLong(index, number);
                case Double number -> statement.setDouble(index, number);
                case Float number -> statement.setFloat(index, number);
                case Boolean flag -> statement.setBoolean(index, flag);
                case byte[] bytes -> statement.setBytes(index, bytes);
                default -> throw new StorageException(
                        "Cannot bind parameter " + index + " of type " + value.getClass().getName());
            }
        }
    }

    // --- null-aware column readers ----------------------------------------------------

    /** Reads a nullable INT column. */
    protected static Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    /** Reads a nullable BIGINT column. */
    protected static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    /** Reads a CHAR(36) column as a UUID, or {@code null} if the column is NULL. */
    protected static UUID nullableUuid(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    /** Reads a non-null CHAR(36) column as a UUID. */
    protected static UUID uuid(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        if (value == null) {
            throw new StorageException("Column " + column + " is unexpectedly NULL");
        }
        return UUID.fromString(value);
    }

    protected BigDecimal money(ResultSet resultSet, String column) throws SQLException {
        return dialect().getMoney(resultSet, column);
    }
}
