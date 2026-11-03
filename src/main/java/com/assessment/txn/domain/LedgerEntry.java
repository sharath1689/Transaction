package com.assessment.txn.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable ledger line. At most one exists per transactionId. */
public record LedgerEntry(
        String transactionId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        Instant postedAt) {}
