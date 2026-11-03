package com.assessment.txn.domain;

/** A deterministic business rejection (e.g. insufficient funds). Never retried. */
public final class BusinessRuleException extends RuntimeException {
    private final FailureReason reason;

    public BusinessRuleException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public FailureReason reason() {
        return reason;
    }
}
