package com.omkar.ledger.funding;

import com.omkar.ledger.funding.dto.CreateFundingRequest;
import com.omkar.ledger.funding.dto.FundingResponse;
import com.omkar.ledger.idempotency.IdempotentRequest;
import com.omkar.ledger.idempotency.IdempotentRequestExecutor;
import com.omkar.ledger.idempotency.Outcome;
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
@RequestMapping("/v1/fundings")
@Tag(name = "Fundings")
public class FundingController {

    private final FundingService fundingService;
    private final IdempotentRequestExecutor idempotent;

    public FundingController(FundingService fundingService, IdempotentRequestExecutor idempotent) {
        this.fundingService = fundingService;
        this.idempotent = idempotent;
    }

    @PostMapping
    @Operation(summary = "Move money into an account from external_settlement")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = FundingResponse.class)))
    public ResponseEntity<String> create(@RequestHeader(IdempotentRequest.HEADER) String idempotencyKey,
                                         @Valid @RequestBody CreateFundingRequest body,
                                         HttpServletRequest http) {
        return idempotent.execute(IdempotentRequest.of(http, idempotencyKey, body), HttpStatus.CREATED,
                () -> Outcome.success(fundingService.fund(body)));
    }
}
