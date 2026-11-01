package com.omkar.ledger.transfer;

import com.omkar.ledger.idempotency.IdempotentRequest;
import com.omkar.ledger.idempotency.IdempotentRequestExecutor;
import com.omkar.ledger.idempotency.Outcome;
import com.omkar.ledger.transfer.dto.CreateTransferRequest;
import com.omkar.ledger.transfer.dto.TransferResponse;
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
@RequestMapping("/v1/transfers")
@Tag(name = "Transfers")
public class TransferController {

    private final TransferService transferService;
    private final IdempotentRequestExecutor idempotent;

    public TransferController(TransferService transferService, IdempotentRequestExecutor idempotent) {
        this.transferService = transferService;
        this.idempotent = idempotent;
    }

    @PostMapping
    @Operation(summary = "Move money between two accounts")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = TransferResponse.class)))
    public ResponseEntity<String> create(@RequestHeader(IdempotentRequest.HEADER) String idempotencyKey,
                                         @Valid @RequestBody CreateTransferRequest body,
                                         HttpServletRequest http) {
        return idempotent.execute(IdempotentRequest.of(http, idempotencyKey, body), HttpStatus.CREATED,
                () -> Outcome.success(transferService.transfer(body)));
    }
}
