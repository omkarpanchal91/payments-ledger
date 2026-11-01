package com.omkar.ledger.refund;

import com.omkar.ledger.idempotency.IdempotentRequest;
import com.omkar.ledger.idempotency.IdempotentRequestExecutor;
import com.omkar.ledger.idempotency.Outcome;
import com.omkar.ledger.refund.dto.CreateRefundRequest;
import com.omkar.ledger.refund.dto.RefundResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Refunds")
public class RefundController {

    private final RefundService refundService;
    private final IdempotentRequestExecutor idempotent;

    public RefundController(RefundService refundService, IdempotentRequestExecutor idempotent) {
        this.refundService = refundService;
        this.idempotent = idempotent;
    }

    @PostMapping("/v1/payments/{id}/refunds")
    @Operation(summary = "Refund a payment fully (no amount) or partially")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = RefundResponse.class)))
    public ResponseEntity<String> create(@RequestHeader(IdempotentRequest.HEADER) String idempotencyKey,
                                         @PathVariable("id") UUID paymentId,
                                         @Valid @RequestBody(required = false) CreateRefundRequest body,
                                         HttpServletRequest http) {
        // An empty body and {} mean the same thing, so they must hash the same.
        CreateRefundRequest request = body == null ? CreateRefundRequest.FULL : body;
        return idempotent.execute(IdempotentRequest.of(http, idempotencyKey, request), HttpStatus.CREATED,
                () -> Outcome.success(refundService.refund(paymentId, request)));
    }

    @GetMapping("/v1/refunds/{id}")
    @Operation(summary = "Get a refund")
    public RefundResponse get(@PathVariable("id") UUID id) {
        return refundService.get(id);
    }
}
