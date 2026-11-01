package com.omkar.ledger.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.common.ErrorResponse;
import com.omkar.ledger.common.LedgerMetrics;
import com.omkar.ledger.common.RetryingTransactionRunner;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The reusable wrapper every POST controller uses. It implements the whole idempotency flow:
 *
 * <ol>
 *   <li>hash the body and claim the key (own short transaction);</li>
 *   <li>on replay: return the stored status + body, do nothing else;</li>
 *   <li>otherwise run the action and {@link IdempotencyService#complete} in ONE transaction
 *   (with deadlock retries) - there is no moment where money moved but the key isn't COMPLETED;</li>
 *   <li>deterministic error (4xx except 409): store it as the key's response;</li>
 *   <li>transient error (409/5xx/unexpected): release the key so the client can retry.</li>
 * </ol>
 *
 * Responses are returned as the exact JSON string that is stored, so a replay is byte-identical.
 */
@Component
public class IdempotentRequestExecutor {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final IdempotencyService idempotency;
    private final RequestHasher hasher;
    private final RetryingTransactionRunner transactions;
    private final ObjectMapper objectMapper;
    private final LedgerMetrics metrics;

    public IdempotentRequestExecutor(IdempotencyService idempotency, RequestHasher hasher,
                                     RetryingTransactionRunner transactions, ObjectMapper objectMapper,
                                     LedgerMetrics metrics) {
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public ResponseEntity<String> execute(IdempotentRequest request, HttpStatus successStatus,
                                          Supplier<? extends Outcome<?>> action) {
        validateKey(request.idempotencyKey());
        String requestHash = hasher.hash(request.body());

        IdempotencyClaim claim = idempotency.claim(request.clientId(), request.idempotencyKey(),
                request.endpoint(), requestHash);
        if (claim instanceof IdempotencyClaim.Replay replay) {
            metrics.idempotentReplay();
            return respond(replay.responseStatus(), replay.responseBody(), true);
        }
        var acquired = (IdempotencyClaim.Acquired) claim;

        StoredResponse stored;
        try {
            stored = transactions.inTransaction(() -> {
                Outcome<?> outcome = action.get();
                StoredResponse response = switch (outcome) {
                    case Outcome.Success<?> success -> new StoredResponse(successStatus.value(), toJson(success.body()));
                    case Outcome.Failure<?> failure -> errorResponse(failure.error());
                };
                idempotency.complete(acquired, response);
                return response;
            });
        } catch (ApiException e) {
            if (e.isClientRetryable()) {
                idempotency.release(acquired);
                throw e;
            }
            stored = errorResponse(e);
            idempotency.completeInNewTransaction(acquired, stored);
        } catch (RuntimeException e) {
            idempotency.release(acquired);
            throw e;
        }
        return respond(stored.status(), stored.body(), false);
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > IdempotentRequest.MAX_KEY_LENGTH) {
            throw ApiException.badRequest("idempotency_key_invalid",
                    "Idempotency-Key must be 1-" + IdempotentRequest.MAX_KEY_LENGTH + " characters.",
                    IdempotentRequest.HEADER);
        }
    }

    private StoredResponse errorResponse(ApiException e) {
        return new StoredResponse(e.getStatus().value(), toJson(ErrorResponse.of(e)));
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize response", e);
        }
    }

    private static ResponseEntity<String> respond(int status, String body, boolean replayed) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAYED_HEADER, Boolean.toString(replayed))
                .body(body);
    }
}
