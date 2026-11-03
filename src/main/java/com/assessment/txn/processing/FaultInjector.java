package com.assessment.txn.processing;

import com.assessment.txn.domain.TransactionRequest;

/**
 * Simulation seam for transient failures. Invoked inside the ledger unit of work, after validation and
 * before commit, so a thrown {@link com.assessment.txn.domain.TransientProcessingException} proves that a
 * failure mid-transaction leaves no partial state. Production wiring uses {@link #none()}.
 */
@FunctionalInterface
public interface FaultInjector {

    void beforeCommit(TransactionRequest request, int attempt);

    static FaultInjector none() {
        return (request, attempt) -> {};
    }
}
