package com.assessment.txn.processing;

import com.assessment.txn.domain.BusinessRuleException;
import com.assessment.txn.domain.FailureReason;
import com.assessment.txn.domain.TransactionRecord;
import com.assessment.txn.domain.TransactionRequest;
import com.assessment.txn.domain.TransactionStatus;
import com.assessment.txn.domain.TransientProcessingException;
import com.assessment.txn.observability.EventSink;
import com.assessment.txn.observability.Masking;
import com.assessment.txn.store.LedgerRepository;
import com.assessment.txn.store.PostingResult;

/**
 * Business processing of ONE already-ordered, already-deduplicated transaction: run the atomic ledger
 * posting, retrying transient failures up to the policy limit, and drive the status lifecycle.
 *
 * <p>Retry safety: every attempt calls {@link LedgerRepository#post}, which is atomic and idempotent on
 * transactionId. A failed attempt changed nothing; an attempt that finds the entry already committed (for
 * example the process died after commit but before the status update) reports ALREADY_APPLIED and the
 * record is simply marked PROCESSED. The effect can therefore never be applied twice.
 */
public final class TransactionProcessor {
    private final LedgerRepository ledger;
    private final FaultInjector faults;
    private final RetryPolicy retryPolicy;
    private final Sleeper sleeper;
    private final EventSink events;
    private int totalRetries;

    public TransactionProcessor(LedgerRepository ledger, FaultInjector faults, RetryPolicy retryPolicy,
                                Sleeper sleeper, EventSink events) {
        this.ledger = ledger;
        this.faults = faults;
        this.retryPolicy = retryPolicy;
        this.sleeper = sleeper;
        this.events = events;
    }

    public int totalRetries() {
        return totalRetries;
    }

    public void process(TransactionRecord record) {
        TransactionRequest request = record.request();
        while (true) {
            record.transitionTo(TransactionStatus.PROCESSING);
            int attempt = record.recordAttempt();
            events.emit("transaction.processing", "transactionId", request.transactionId(),
                    "attempt", attempt);

            PostingResult result;
            try {
                result = ledger.post(request, () -> faults.beforeCommit(request, attempt));
            } catch (TransientProcessingException e) {
                if (attempt >= retryPolicy.maxAttempts()) {
                    record.fail(FailureReason.RETRIES_EXHAUSTED);
                    events.emit("transaction.failed", "transactionId", request.transactionId(),
                            "reason", FailureReason.RETRIES_EXHAUSTED, "attempts", attempt);
                    return;
                }
                record.transitionTo(TransactionStatus.RETRY_PENDING);
                totalRetries++;
                events.emit("transaction.retry_scheduled", "transactionId", request.transactionId(),
                        "attempt", attempt, "backoffMs", retryPolicy.backoffFor(attempt).toMillis());
                sleeper.sleep(retryPolicy.backoffFor(attempt));
                continue;
            } catch (BusinessRuleException e) {
                record.fail(e.reason());
                events.emit("transaction.failed", "transactionId", request.transactionId(),
                        "reason", e.reason(), "attempts", attempt);
                return;
            } catch (RuntimeException e) {
                // Unknown failure: isolate this record, keep the batch going, and do not leak the message.
                record.fail(FailureReason.UNEXPECTED_ERROR);
                events.emit("transaction.failed", "transactionId", request.transactionId(),
                        "reason", FailureReason.UNEXPECTED_ERROR, "errorType", e.getClass().getSimpleName(),
                        "attempts", attempt);
                return;
            }

            record.transitionTo(TransactionStatus.PROCESSED);
            events.emit("transaction.processed", "transactionId", request.transactionId(),
                    "account", Masking.account(request.accountId()), "type", request.type(),
                    "attempts", attempt, "ledger", result);
            return;
        }
    }
}
