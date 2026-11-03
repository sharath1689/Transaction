package com.assessment.txn.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable processing state of one unique transaction. Enforces the lifecycle in {@link TransactionStatus}.
 * Not thread-safe by itself; the processing service serialises access.
 */
public final class TransactionRecord {

    /** One entry of the audit trail. */
    public record StatusChange(TransactionStatus status, Instant at) {}

    private final TransactionRequest request;
    private final Clock clock;
    private final List<StatusChange> history = new ArrayList<>();
    private TransactionStatus status;
    private int attempts;
    private int duplicateCount;
    private FailureReason failureReason;

    public TransactionRecord(TransactionRequest request, Clock clock) {
        this.request = request;
        this.clock = clock;
        this.status = TransactionStatus.RECEIVED;
        this.history.add(new StatusChange(status, clock.instant()));
    }

    public void transitionTo(TransactionStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Illegal transition " + status + " -> " + next + " for " + request.transactionId());
        }
        if (status == TransactionStatus.FAILED) {
            failureReason = null; // being replayed
        }
        status = next;
        history.add(new StatusChange(next, clock.instant()));
    }

    public void fail(FailureReason reason) {
        transitionTo(TransactionStatus.FAILED);
        this.failureReason = reason;
    }

    /** Starts a new processing attempt and returns the new attempt number (1-based). */
    public int recordAttempt() {
        return ++attempts;
    }

    public void resetAttempts() {
        attempts = 0;
    }

    public void recordDuplicate() {
        duplicateCount++;
    }

    public TransactionRequest request() {
        return request;
    }

    public String transactionId() {
        return request.transactionId();
    }

    public TransactionStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public int duplicateCount() {
        return duplicateCount;
    }

    public FailureReason failureReason() {
        return failureReason;
    }

    public List<StatusChange> history() {
        return Collections.unmodifiableList(history);
    }

    public List<TransactionStatus> statusTrail() {
        return history.stream().map(StatusChange::status).toList();
    }
}
