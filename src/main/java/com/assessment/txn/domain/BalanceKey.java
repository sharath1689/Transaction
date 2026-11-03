package com.assessment.txn.domain;

/** Balances are tracked per account and currency, so no cross-currency arithmetic is ever needed. */
public record BalanceKey(String accountId, String currency) {}
