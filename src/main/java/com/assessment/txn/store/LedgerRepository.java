package com.assessment.txn.store;

import com.assessment.txn.domain.BalanceKey;
import com.assessment.txn.domain.LedgerEntry;
import com.assessment.txn.domain.TransactionRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Business side-effect port (balances + ledger).
 *
 * <p>{@link #post} must be atomic: the balance change and the ledger entry are committed together or not at
 * all, and the ledger is idempotent on transactionId. This is the second line of defence behind the state
 * store, and what makes a crash between "ledger committed" and "status updated" safe to retry.
 */
public interface LedgerRepository {

    /**
     * @param preCommit hook invoked after all checks pass and immediately before the commit; if it throws,
     *                  nothing has been changed. Production code passes a no-op; tests and the simulator
     *                  use it to inject a failure in the middle of a unit of work.
     * @throws com.assessment.txn.domain.BusinessRuleException if a business rule rejects the posting
     */
    PostingResult post(TransactionRequest request, Runnable preCommit);

    BigDecimal balance(String accountId, String currency);

    List<LedgerEntry> entries();

    Map<BalanceKey, BigDecimal> balances();
}
