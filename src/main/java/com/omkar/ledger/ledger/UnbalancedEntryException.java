package com.omkar.ledger.ledger;

import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.common.ErrorType;
import org.springframework.http.HttpStatus;

/** The postings of a journal entry do not sum to zero. */
public class UnbalancedEntryException extends ApiException {

    public UnbalancedEntryException(long sum) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, ErrorType.INVALID_REQUEST_ERROR, "unbalanced_entry",
                "Journal entry postings must sum to zero but sum to " + sum + ".", null);
    }
}
