package com.assessment.txn.validation;

import com.assessment.txn.domain.TransactionRequest;
import java.util.List;

/**
 * Result of validating a raw record.
 *
 * @param request      the request, present only if there are no errors
 * @param errors       human-readable problems; they name fields but never echo submitted values
 * @param sequenceHint the sequence number if that one field was valid, even when other fields were not;
 *                     lets the ordering gate skip the slot a rejected record occupied
 */
public record ValidationOutcome(TransactionRequest request, List<String> errors, Long sequenceHint) {

    public boolean valid() {
        return errors.isEmpty();
    }
}
