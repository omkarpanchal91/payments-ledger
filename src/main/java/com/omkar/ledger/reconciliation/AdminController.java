package com.omkar.ledger.reconciliation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Requires an admin API key (enforced by ApiKeyFilter for /v1/admin/**). */
@RestController
@RequestMapping("/v1/admin")
@Tag(name = "Admin")
public class AdminController {

    private final ReconciliationService reconciliationService;

    public AdminController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Check cached balances and zero-sum invariants against the postings")
    public ReconciliationReport reconciliation() {
        return reconciliationService.run();
    }
}
