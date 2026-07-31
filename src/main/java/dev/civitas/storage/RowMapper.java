package dev.civitas.storage;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps the current row of a {@link ResultSet} to a row record.
 *
 * @param <T> the row record type
 */
@FunctionalInterface
public interface RowMapper<T> {

    /**
     * @param resultSet positioned on the row to read; do not call {@code next()}
     * @return the mapped row
     * @throws SQLException if a column cannot be read
     */
    T map(ResultSet resultSet) throws SQLException;
}
