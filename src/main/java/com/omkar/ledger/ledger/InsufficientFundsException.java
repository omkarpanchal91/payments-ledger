package com.omkar.ledger.ledger;

import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.common.ErrorType;
import org.springframework.http.HttpStatus;

/** Thrown before any balance is changed, so callers may catch it and still commit (see PaymentService). */
public class InsufficientFundsException extends ApiException {

    public InsufficientFundsException(String accountCode) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, ErrorType.INVALID_REQUEST_ERROR, "insufficient_funds",
                "Account " + accountCode + " has insufficient funds.", "amount_minor");
    }
}
