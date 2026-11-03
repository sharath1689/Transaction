package com.assessment.txn.store;

import com.assessment.txn.domain.BalanceKey;
import com.assessment.txn.domain.BusinessRuleException;
import com.assessment.txn.domain.FailureReason;
import com.assessment.txn.domain.LedgerEntry;
import com.assessment.txn.domain.TransactionRequest;
import com.assessment.txn.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory ledger. {@code synchronized} makes each posting atomic and isolated. */
public final class InMemoryLedgerRepository implements LedgerRepository {
    private final Clock clock;
    private final Map<String, LedgerEntry> entriesByTransaction = new LinkedHashMap<>();
    private final Map<BalanceKey, BigDecimal> balances = new LinkedHashMap<>();

    public InMemoryLedgerRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized PostingResult post(TransactionRequest request, Runnable preCommit) {
        if (entriesByTransaction.containsKey(request.transactionId())) {
            return PostingResult.ALREADY_APPLIED;
        }
        BalanceKey key = new BalanceKey(request.accountId(), request.currency());
        BigDecimal current = balances.getOrDefault(key, BigDecimal.ZERO);
        BigDecimal updated = request.type() == TransactionType.CREDIT
                ? current.add(request.amount())
                : current.subtract(request.amount());
        if (updated.signum() < 0) {
            throw new BusinessRuleException(FailureReason.INSUFFICIENT_FUNDS, "Insufficient funds");
        }
        LedgerEntry entry = new LedgerEntry(request.transactionId(), request.accountId(), request.type(),
                request.amount(), request.currency(), updated, clock.instant());

        preCommit.run(); // may throw: nothing has been mutated yet

        balances.put(key, updated);
        entriesByTransaction.put(request.transactionId(), entry);
        return PostingResult.APPLIED;
    }

    @Override
    public synchronized BigDecimal balance(String accountId, String currency) {
        return balances.getOrDefault(new BalanceKey(accountId, currency), BigDecimal.ZERO);
    }

    @Override
    public synchronized List<LedgerEntry> entries() {
        return new ArrayList<>(entriesByTransaction.values());
    }

    @Override
    public synchronized Map<BalanceKey, BigDecimal> balances() {
        return new LinkedHashMap<>(balances);
    }
}
