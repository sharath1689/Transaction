package com.assessment.txn.processing;

import com.assessment.txn.domain.TransactionRequest;
import com.assessment.txn.domain.TransientProcessingException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic fault simulation: "fail the next N attempts of transaction X". Spec format used by the CLI:
 * {@code TXN-2005:1,TXN-2006:99} (a bare id means one failure).
 */
public final class ScriptedFaultInjector implements FaultInjector {
    private final Map<String, AtomicInteger> remainingFailures = new ConcurrentHashMap<>();

    public ScriptedFaultInjector(Map<String, Integer> failuresByTransaction) {
        failuresByTransaction.forEach((id, n) -> remainingFailures.put(id, new AtomicInteger(n)));
    }

    public static ScriptedFaultInjector parse(String spec) {
        Map<String, Integer> failures = new HashMap<>();
        if (spec != null && !spec.isBlank()) {
            for (String part : spec.split(",")) {
                String[] kv = part.trim().split(":");
                if (kv.length == 0 || kv[0].isBlank() || kv.length > 2) {
                    throw new IllegalArgumentException("Invalid transient-failure spec entry");
                }
                int count = kv.length == 2 ? Integer.parseInt(kv[1].trim()) : 1;
                failures.put(kv[0].trim(), count);
            }
        }
        return new ScriptedFaultInjector(failures);
    }

    /** Models "the failing dependency has recovered". */
    public void clear() {
        remainingFailures.clear();
    }

    @Override
    public void beforeCommit(TransactionRequest request, int attempt) {
        AtomicInteger remaining = remainingFailures.get(request.transactionId());
        if (remaining != null && remaining.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
            throw new TransientProcessingException("Simulated transient failure on attempt " + attempt);
        }
    }
}
