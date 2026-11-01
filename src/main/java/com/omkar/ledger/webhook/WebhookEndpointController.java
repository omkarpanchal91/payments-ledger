package com.omkar.ledger.webhook;

import com.omkar.ledger.idempotency.IdempotentRequest;
import com.omkar.ledger.idempotency.IdempotentRequestExecutor;
import com.omkar.ledger.idempotency.Outcome;
import com.omkar.ledger.webhook.dto.CreateWebhookEndpointRequest;
import com.omkar.ledger.webhook.dto.WebhookEndpointResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/webhook_endpoints")
@Tag(name = "Webhooks")
public class WebhookEndpointController {

    private final WebhookEndpointService service;
    private final IdempotentRequestExecutor idempotent;

    public WebhookEndpointController(WebhookEndpointService service, IdempotentRequestExecutor idempotent) {
        this.service = service;
        this.idempotent = idempotent;
    }

    @PostMapping
    @Operation(summary = "Register a merchant webhook URL; the signing secret is returned only here")
    @ApiResponse(responseCode = "201",
            content = @Content(schema = @Schema(implementation = WebhookEndpointResponse.class)))
    public ResponseEntity<String> create(@RequestHeader(IdempotentRequest.HEADER) String idempotencyKey,
                                         @Valid @RequestBody CreateWebhookEndpointRequest body,
                                         HttpServletRequest http) {
        return idempotent.execute(IdempotentRequest.of(http, idempotencyKey, body), HttpStatus.CREATED,
                () -> Outcome.success(service.register(body)));
    }
}
