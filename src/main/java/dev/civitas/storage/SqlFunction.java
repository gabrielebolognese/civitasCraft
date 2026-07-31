package dev.civitas.storage;

import java.sql.SQLException;

/**
 * A function that may throw {@link SQLException}, so JDBC work can be written as a lambda
 * without wrapping every statement in a try/catch.
 *
 * @param <T> input type
 * @param <R> result type
 */
@FunctionalInterface
public interface SqlFunction<T, R> {

    R apply(T input) throws SQLException;
}
