package com.assessment.txn.domain;

import java.math.BigDecimal;

/**
 * A validated, immutable transaction request.
 *
 * @param transactionId business identifier; the idempotency key
 * @param requestId     delivery identifier; differs between retries/duplicates of the same transaction
 */
public record TransactionRequest(
        String transactionId,
        String requestId,
        long sequenceNumber,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency) {

    /**
     * Canonical payload signature. A repeated transactionId with a different fingerprint is flagged as a
     * payload conflict (still never re-processed).
     */
    public String fingerprint() {
        return type + "|" + accountId + "|" + amount.stripTrailingZeros().toPlainString()
                + "|" + currency + "|" + sequenceNumber;
    }
}
