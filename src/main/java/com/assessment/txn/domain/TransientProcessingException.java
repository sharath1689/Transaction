package com.assessment.txn.domain;

/** A failure that may succeed if retried (timeout, deadlock, downstream unavailable, ...). */
public final class TransientProcessingException extends RuntimeException {
    public TransientProcessingException(String message) {
        super(message);
    }
}
