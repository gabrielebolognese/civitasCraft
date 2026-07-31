package dev.civitas.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SPEC 2.3: every service mutation returns a Result, and expected failures are values. */
class ResultTest {

    @Test
    @DisplayName("a success carries its value and reports success")
    void successCarriesValue() {
        Result<String> result = Result.success("roma");

        assertTrue(result.isSuccess());
        assertFalse(result.isFailure());
        assertEquals("roma", result.orElseThrow());
        assertEquals("roma", result.asOptional().orElseThrow());
    }

    @Test
    @DisplayName("ok() is a success with no value")
    void okIsValuelessSuccess() {
        Result<Void> result = Result.ok();

        assertTrue(result.isSuccess());
        assertTrue(result.asOptional().isEmpty());
    }

    @Test
    @DisplayName("a failure carries a reason and a message key, never rendered text")
    void failureCarriesReasonAndKey() {
        Result<String> result = Result.failure("INSUFFICIENT_FUNDS", "city.create.insufficient-funds");

        assertTrue(result.isFailure());
        assertTrue(result.asOptional().isEmpty());
        assertEquals("fallback", result.orElse("fallback"));

        Result.Failure<String> failure = (Result.Failure<String>) result;
        assertEquals("INSUFFICIENT_FUNDS", failure.reason());
        assertEquals("city.create.insufficient-funds", failure.messageKey());
        assertTrue(failure.placeholders().isEmpty());
    }

    @Test
    @DisplayName("placeholder maps are defensively copied and unmodifiable")
    void placeholdersAreImmutable() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("cost", "10000");

        Result.Failure<String> failure =
                (Result.Failure<String>) Result.<String>failure("NO_FUNDS", "key", mutable);
        mutable.put("cost", "tampered");

        assertEquals("10000", failure.placeholders().get("cost"));
        assertThrows(UnsupportedOperationException.class, () -> failure.placeholders().put("x", "y"));
    }

    @Test
    @DisplayName("a null placeholder map is treated as empty")
    void nullPlaceholdersBecomeEmpty() {
        Result.Failure<String> failure = new Result.Failure<>("R", "k", null);

        assertTrue(failure.placeholders().isEmpty());
    }

    @Test
    @DisplayName("orElseThrow on a failure throws rather than returning null")
    void orElseThrowOnFailure() {
        Result<String> result = Result.failure("FROZEN", "city.frozen");

        NoSuchElementException thrown = assertThrows(NoSuchElementException.class, result::orElseThrow);
        assertTrue(thrown.getMessage().contains("FROZEN"));
    }

    @Test
    @DisplayName("map transforms a success and passes a failure through unchanged")
    void mapTransformsSuccessAndPropagatesFailure() {
        assertEquals(4, Result.success("roma").map(String::length).orElseThrow());

        Result<Integer> mapped = Result.<String>failure("FROZEN", "city.frozen").map(String::length);
        Result.Failure<Integer> failure = (Result.Failure<Integer>) mapped;
        assertEquals("FROZEN", failure.reason());
        assertEquals("city.frozen", failure.messageKey());
    }

    @Test
    @DisplayName("flatMap chains successes and short-circuits on the first failure")
    void flatMapChains() {
        Result<Integer> chained = Result.success("roma").flatMap(name -> Result.success(name.length()));
        assertEquals(4, chained.orElseThrow());

        Result<Integer> shortCircuited = Result.<String>failure("FROZEN", "city.frozen")
                .flatMap(name -> Result.success(name.length()));
        assertTrue(shortCircuited.isFailure());
    }

    @Test
    @DisplayName("ifSuccess and ifFailure fire on exactly one branch and return this")
    void sideEffectHooks() {
        AtomicReference<String> seen = new AtomicReference<>();
        Result<String> success = Result.success("roma");

        assertSame(success, success.ifSuccess(seen::set));
        assertEquals("roma", seen.get());

        seen.set(null);
        success.ifFailure(f -> seen.set(f.reason()));
        assertEquals(null, seen.get());

        Result<String> failure = Result.failure("FROZEN", "city.frozen");
        failure.ifSuccess(seen::set);
        assertEquals(null, seen.get());
        failure.ifFailure(f -> seen.set(f.reason()));
        assertEquals("FROZEN", seen.get());
    }

    @Test
    @DisplayName("propagate re-types a failure without losing its reason, key or placeholders")
    void propagateRetypes() {
        Result.Failure<String> original =
                (Result.Failure<String>) Result.<String>failure("NO_FUNDS", "k", Map.of("cost", "500"));

        Result<Integer> propagated = Result.propagate(original);
        Result.Failure<Integer> retyped = (Result.Failure<Integer>) propagated;

        assertEquals("NO_FUNDS", retyped.reason());
        assertEquals("k", retyped.messageKey());
        assertEquals("500", retyped.placeholders().get("cost"));
    }

    @Test
    @DisplayName("the sealed hierarchy has exactly two permitted implementations")
    void hierarchyIsExhaustive() {
        assertTrue(Result.class.isSealed());
        assertEquals(2, Result.class.getPermittedSubclasses().length);
    }
}
