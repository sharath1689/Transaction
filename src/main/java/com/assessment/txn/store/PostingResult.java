package com.assessment.txn.store;

/** Outcome of a ledger posting. ALREADY_APPLIED means the ledger already holds an entry for the transaction. */
public enum PostingResult {
    APPLIED,
    ALREADY_APPLIED
}
