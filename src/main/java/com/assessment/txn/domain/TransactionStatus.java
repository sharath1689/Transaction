package com.assessment.txn.domain;

/**
 * Status vocabulary.
 *
 * <p>Lifecycle of a unique transaction (stored on {@link TransactionRecord}):
 * <pre>
 *   RECEIVED -> PENDING_SEQUENCE -> PROCESSING -> PROCESSED
 *   RECEIVED -> PROCESSING
 *   PROCESSING -> RETRY_PENDING -> PROCESSING ...   (transient failure, attempts left)
 *   PROCESSING -> FAILED                            (business failure or retries exhausted)
 *   FAILED -> RETRY_PENDING                         (operator replay of retry-exhausted records only)
 * </pre>
 *
 * <p>DUPLICATE and REJECTED are <em>delivery-level</em> outcomes: they describe what happened to an incoming
 * record, never the state of a stored transaction, so a duplicate can never overwrite the original's state.
 */
public enum TransactionStatus {
    RECEIVED,
    PENDING_SEQUENCE,
    PROCESSING,
    RETRY_PENDING,
    PROCESSED,
    FAILED,
    DUPLICATE,
    REJECTED;

    public boolean canTransitionTo(TransactionStatus next) {
        return switch (this) {
            case RECEIVED -> next == PENDING_SEQUENCE || next == PROCESSING;
            case PENDING_SEQUENCE -> next == PROCESSING;
            case PROCESSING -> next == PROCESSED || next == RETRY_PENDING || next == FAILED;
            case RETRY_PENDING -> next == PROCESSING;
            case FAILED -> next == RETRY_PENDING;
            case PROCESSED, DUPLICATE, REJECTED -> false;
        };
    }
}
