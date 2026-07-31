package dev.civitas.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The return type of every service mutation, per SPEC.md Section 2.3.
 *
 * <p>Expected failures are values, never exceptions. A {@link Failure} carries a
 * machine-readable {@code reason} for logging and branching, plus the {@code messageKey}
 * of the player-facing text in {@code lang/}. It never carries the message itself,
 * because no player-facing string may be hardcoded.
 *
 * @param <T> the value produced on success
 */
public sealed interface Result<T> permits Result.Success, Result.Failure {

    /**
     * A successful mutation.
     *
     * @param value the produced value, may be {@code null} for void-like operations
     * @param <T>   the value type
     */
    record Success<T>(T value) implements Result<T> { }

    /**
     * An expected, non-exceptional failure.
     *
     * @param reason       stable machine-readable identifier, e.g. {@code INSUFFICIENT_FUNDS}
     * @param messageKey   key into {@code lang/}, e.g. {@code city.create.insufficient-funds}
     * @param placeholders values substituted into the message, never pre-rendered text
     * @param <T>          the value type the operation would have produced
     */
    record Failure<T>(String reason, String messageKey, Map<String, String> placeholders)
            implements Result<T> {

        public Failure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(messageKey, "messageKey");
            placeholders = placeholders == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(placeholders));
        }
    }

    static <T> Result<T> success(T value) {
        return new Success<>(value);
    }

    /** A success carrying no value, for operations that only need to report outcome. */
    static Result<Void> ok() {
        return new Success<>(null);
    }

    static <T> Result<T> failure(String reason, String messageKey) {
        return new Failure<>(reason, messageKey, Map.of());
    }

    static <T> Result<T> failure(String reason, String messageKey, Map<String, String> placeholders) {
        return new Failure<>(reason, messageKey, placeholders);
    }

    /** Re-types an existing failure so it can be propagated out of a differently-typed call. */
    static <T> Result<T> propagate(Failure<?> failure) {
        return new Failure<>(failure.reason(), failure.messageKey(), failure.placeholders());
    }

    default boolean isSuccess() {
        return this instanceof Success<T>;
    }

    default boolean isFailure() {
        return this instanceof Failure<T>;
    }

    /** @return the success value, or empty if this is a failure or the value was {@code null} */
    default Optional<T> asOptional() {
        return this instanceof Success<T>(T v) ? Optional.ofNullable(v) : Optional.empty();
    }

    /** @throws NoSuchElementException if this is a failure */
    default T orElseThrow() {
        if (this instanceof Success<T>(T v)) {
            return v;
        }
        Failure<T> f = (Failure<T>) this;
        throw new NoSuchElementException("Result is a failure: " + f.reason());
    }

    default T orElse(T fallback) {
        return this instanceof Success<T>(T v) ? v : fallback;
    }

    default <R> Result<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (this instanceof Success<T>(T v)) {
            return success(mapper.apply(v));
        }
        return propagate((Failure<T>) this);
    }

    default <R> Result<R> flatMap(Function<? super T, ? extends Result<R>> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (this instanceof Success<T>(T v)) {
            return mapper.apply(v);
        }
        return propagate((Failure<T>) this);
    }

    default Result<T> ifSuccess(Consumer<? super T> action) {
        Objects.requireNonNull(action, "action");
        if (this instanceof Success<T>(T v)) {
            action.accept(v);
        }
        return this;
    }

    default Result<T> ifFailure(Consumer<? super Failure<T>> action) {
        Objects.requireNonNull(action, "action");
        if (this instanceof Failure<T> f) {
            action.accept(f);
        }
        return this;
    }
}
