package com.assessment.txn.store;

import com.assessment.txn.domain.TransactionRecord;
import java.util.List;
import java.util.Optional;

/**
 * Idempotency / transaction-state port. {@link #registerIfAbsent} is the atomic "claim" operation: in a real
 * database it is an INSERT guarded by a unique constraint on transactionId.
 */
public interface TransactionStateStore {

    Optional<TransactionRecord> find(String transactionId);

    /** @return true if this call registered the record, false if the transactionId was already known */
    boolean registerIfAbsent(TransactionRecord record);

    /** Snapshot in arrival order. */
    List<TransactionRecord> all();
}
