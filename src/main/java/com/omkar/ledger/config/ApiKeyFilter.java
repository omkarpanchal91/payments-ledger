package com.omkar.ledger.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.common.ErrorResponse;
import com.omkar.ledger.common.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every /v1 request with the X-Api-Key header. Keys come from configuration
 * (ledger.security.api-keys); each maps to a client id that scopes idempotency keys.
 * Admin endpoints (/v1/admin/**) additionally require an admin key.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    public static final String CLIENT_ATTRIBUTE = "ledger.apiClient";

    private final Map<String, ApiClient> clientsByKey;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(LedgerProperties properties, ObjectMapper objectMapper) {
        this.clientsByKey = properties.security().apiKeys().stream()
                .collect(Collectors.toUnmodifiableMap(
                        LedgerProperties.ApiKey::key,
                        k -> new ApiClient(k.clientId(), k.admin()),
                        (first, second) -> first));
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        ApiClient client = key == null ? null : clientsByKey.get(key);
        if (client == null) {
            writeError(response, ApiException.unauthorized("invalid_api_key",
                    "Missing or invalid API key. Provide it in the X-Api-Key header."));
            return;
        }
        if (request.getRequestURI().startsWith("/v1/admin/") && !client.admin()) {
            writeError(response, ApiException.forbidden("admin_required", "This endpoint requires an admin API key."));
            return;
        }
        request.setAttribute(CLIENT_ATTRIBUTE, client);
        MDC.put(RequestIds.CLIENT_MDC_KEY, client.clientId());
        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, ApiException error) throws IOException {
        response.setStatus(error.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(error));
    }
}
