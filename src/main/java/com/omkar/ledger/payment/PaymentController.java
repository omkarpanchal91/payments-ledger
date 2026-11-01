package com.omkar.ledger.payment;

import com.omkar.ledger.idempotency.IdempotentRequest;
import com.omkar.ledger.idempotency.IdempotentRequestExecutor;
import com.omkar.ledger.payment.dto.CreatePaymentRequest;
import com.omkar.ledger.payment.dto.PaymentResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
@Tag(name = "Payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotentRequestExecutor idempotent;

    public PaymentController(PaymentService paymentService, IdempotentRequestExecutor idempotent) {
        this.paymentService = paymentService;
        this.idempotent = idempotent;
    }

    @PostMapping
    @Operation(summary = "Customer pays a merchant (platform fee applied)")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = PaymentResponse.class)))
    public ResponseEntity<String> create(@RequestHeader(IdempotentRequest.HEADER) String idempotencyKey,
                                         @Valid @RequestBody CreatePaymentRequest body,
                                         HttpServletRequest http) {
        return idempotent.execute(IdempotentRequest.of(http, idempotencyKey, body), HttpStatus.CREATED,
                () -> paymentService.create(body));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a payment")
    public PaymentResponse get(@PathVariable("id") UUID id) {
        return paymentService.get(id);
    }
}
