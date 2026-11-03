package com.assessment.txn.domain;

/** Why a transaction ended in FAILED. Only retryable reasons are eligible for operator replay. */
public enum FailureReason {
    INSUFFICIENT_FUNDS(false),
    RETRIES_EXHAUSTED(true),
    UNEXPECTED_ERROR(false);

    private final boolean replayable;

    FailureReason(boolean replayable) {
        this.replayable = replayable;
    }

    public boolean replayable() {
        return replayable;
    }
}
