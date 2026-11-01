package com.omkar.ledger.account;

import com.omkar.ledger.account.dto.AccountResponse;
import com.omkar.ledger.account.dto.CreateAccountRequest;
import com.omkar.ledger.common.PageResponse;
import com.omkar.ledger.idempotency.IdempotentRequest;
import com.omkar.ledger.idempotency.IdempotentRequestExecutor;
import com.omkar.ledger.idempotency.Outcome;
import com.omkar.ledger.ledger.dto.PostingResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A {@code @RestController} is a DRF ViewSet/APIView: methods are routed by the mapping annotations
 * and their return values are serialized to JSON.
 *
 * <p>Every POST goes through {@link IdempotentRequestExecutor}; it returns the response as a raw
 * JSON string so a replay is byte-for-byte identical to the original response.
 */
@RestController
@RequestMapping("/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;
    private final IdempotentRequestExecutor idempotent;

    public AccountController(AccountService accountService, IdempotentRequestExecutor idempotent) {
        this.accountService = accountService;
        this.idempotent = idempotent;
    }

    @PostMapping
    @Operation(summary = "Create an account")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = AccountResponse.class)))
    public ResponseEntity<String> create(@RequestHeader(IdempotentRequest.HEADER) String idempotencyKey,
                                         @Valid @RequestBody CreateAccountRequest body,
                                         HttpServletRequest http) {
        return idempotent.execute(IdempotentRequest.of(http, idempotencyKey, body), HttpStatus.CREATED,
                () -> Outcome.success(accountService.create(body)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an account and its cached balance")
    public AccountResponse get(@PathVariable("id") UUID id) {
        return accountService.get(id);
    }

    @GetMapping("/{id}/postings")
    @Operation(summary = "Account statement, newest first, cursor-paginated")
    public PageResponse<PostingResponse> postings(@PathVariable("id") UUID id,
                                                  @RequestParam(name = "limit", defaultValue = "20") int limit,
                                                  @RequestParam(name = "starting_after", required = false)
                                                  UUID startingAfter) {
        return accountService.statement(id, limit, startingAfter);
    }

    @PostMapping("/{id}/freeze")
    @Operation(summary = "Freeze an account (no money can move in or out)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AccountResponse.class)))
    public ResponseEntity<String> freeze(@RequestHeader(IdempotentRequest.HEADER) String idempotencyKey,
                                         @PathVariable("id") UUID id,
                                         HttpServletRequest http) {
        return idempotent.execute(IdempotentRequest.of(http, idempotencyKey, null), HttpStatus.OK,
                () -> Outcome.success(accountService.freeze(id)));
    }

    @PostMapping("/{id}/unfreeze")
    @Operation(summary = "Unfreeze an account")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AccountResponse.class)))
    public ResponseEntity<String> unfreeze(@RequestHeader(IdempotentRequest.HEADER) String idempotencyKey,
                                           @PathVariable("id") UUID id,
                                           HttpServletRequest http) {
        return idempotent.execute(IdempotentRequest.of(http, idempotencyKey, null), HttpStatus.OK,
                () -> Outcome.success(accountService.unfreeze(id)));
    }
}
