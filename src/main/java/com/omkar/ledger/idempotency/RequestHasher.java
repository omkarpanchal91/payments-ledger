package com.omkar.ledger.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * SHA-256 of the canonical JSON of a request body. The parsed body is converted to nested maps and
 * written with sorted keys and no whitespace, so {"a":1,"b":2} and { "b": 2, "a": 1 } hash the same.
 * Hashing the parsed DTO (not raw bytes) also means only fields the API understands count.
 */
@Component
public class RequestHasher {

    private final ObjectMapper canonicalMapper;

    public RequestHasher(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.INDENT_OUTPUT, false);
    }

    public String hash(Object body) {
        try {
            // Serialize, then read back as plain Maps/Lists so every object is a Map and
            // ORDER_MAP_ENTRIES_BY_KEYS sorts it on the second write.
            Object tree = body == null ? null
                    : canonicalMapper.readValue(canonicalMapper.writeValueAsBytes(body), Object.class);
            byte[] canonical = canonicalMapper.writeValueAsBytes(tree);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return HexFormat.of().formatHex(digest);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot hash request body", e);
        }
    }
}
