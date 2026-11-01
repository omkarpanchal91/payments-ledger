package com.omkar.ledger.webhook;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    @Query(value = """
            SELECT * FROM webhook_endpoints
            WHERE merchant_id = :merchantId
              AND status = 'ENABLED'
              AND (CAST(:eventType AS text) = ANY (enabled_events) OR '*' = ANY (enabled_events))
            """, nativeQuery = true)
    List<WebhookEndpoint> findEnabledForEvent(@Param("merchantId") String merchantId,
                                              @Param("eventType") String eventType);
}
