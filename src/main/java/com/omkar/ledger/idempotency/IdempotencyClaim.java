package com.omkar.ledger.idempotency;

import java.util.UUID;

/** Result of trying to claim an idempotency key. */
public sealed interface IdempotencyClaim {

    /**
     * This request now owns the key and must run the business logic.
     *
     * @param lockOwner random token; only the owner can complete or release the key
     * @param takenOver true if a previous request's lock had expired and we took it over
     */
    record Acquired(UUID recordId, UUID lockOwner, boolean takenOver) implements IdempotencyClaim {
    }

    /** The key already completed: answer with the stored response, run nothing. */
    record Replay(int responseStatus, String responseBody) implements IdempotencyClaim {
    }
}
