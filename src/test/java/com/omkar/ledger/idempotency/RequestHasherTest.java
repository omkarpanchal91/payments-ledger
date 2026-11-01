package com.omkar.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestHasherTest {

    record Body(String customerId, long amountMinor) {
    }

    private final RequestHasher hasher = new RequestHasher(
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));

    @Test
    void keyOrderDoesNotMatter() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("a", 1);
        a.put("b", Map.of("y", 2, "x", 1));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("b", Map.of("x", 1, "y", 2));
        b.put("a", 1);
        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
    }

    @Test
    void differentValuesProduceDifferentHashes() {
        assertThat(hasher.hash(new Body("c_1", 100))).isNotEqualTo(hasher.hash(new Body("c_1", 101)));
    }

    @Test
    void recordAndEquivalentMapHashTheSame() {
        assertThat(hasher.hash(new Body("c_1", 100)))
                .isEqualTo(hasher.hash(Map.of("customer_id", "c_1", "amount_minor", 100L)));
    }

    @Test
    void producesHexSha256() {
        assertThat(hasher.hash(null)).matches("[0-9a-f]{64}");
    }
}
