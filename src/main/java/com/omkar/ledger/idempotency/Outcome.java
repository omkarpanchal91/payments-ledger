package com.omkar.ledger.idempotency;

import com.omkar.ledger.common.ApiException;

/**
 * What a business action returns to the idempotency layer.
 *
 * <ul>
 *   <li>{@link Success}: commit, respond with the body.</li>
 *   <li>{@link Failure}: a business failure whose side effects must <em>still be committed</em>
 *   (e.g. a FAILED payment row), responding with the error. Throwing would roll everything back.</li>
 * </ul>
 */
public sealed interface Outcome<T> {

    record Success<T>(T body) implements Outcome<T> {
    }

    record Failure<T>(ApiException error) implements Outcome<T> {
    }

    static <T> Outcome<T> success(T body) {
        return new Success<>(body);
    }

    static <T> Outcome<T> failure(ApiException error) {
        return new Failure<>(error);
    }
}
