package com.assessment.txn;

import com.assessment.txn.domain.LedgerEntry;
import com.assessment.txn.domain.TransactionRecord;
import com.assessment.txn.domain.TransactionStatus;
import com.assessment.txn.ingestion.RawRecord;
import com.assessment.txn.observability.EventSink;
import com.assessment.txn.processing.FaultInjector;
import com.assessment.txn.processing.RetryPolicy;
import com.assessment.txn.processing.TransactionProcessingService;
import com.assessment.txn.reporting.ProcessingReport;
import com.assessment.txn.store.InMemoryLedgerRepository;
import com.assessment.txn.store.InMemoryTransactionStateStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Test fixture: a fully wired in-memory service with instant retries and captured events. */
final class TestKit {

    /** Captures events as single lines so tests can assert on what was (not) logged. */
    static final class CapturingEventSink implements EventSink {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void emit(String event, Object... keyValues) {
            StringBuilder sb = new StringBuilder(event);
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                sb.append(' ').append(keyValues[i]).append('=').append(keyValues[i + 1]);
            }
            lines.add(sb.toString());
        }
    }

    final CapturingEventSink events = new CapturingEventSink();
    final InMemoryTransactionStateStore store = new InMemoryTransactionStateStore();
    final InMemoryLedgerRepository ledger = new InMemoryLedgerRepository(Clock.systemUTC());
    final TransactionProcessingService service;

    TestKit() {
        this(FaultInjector.none(), 3);
    }

    TestKit(FaultInjector faults) {
        this(faults, 3);
    }

    TestKit(FaultInjector faults, int maxAttempts) {
        service = new TransactionProcessingService(store, ledger, faults,
                new RetryPolicy(maxAttempts, Duration.ZERO), duration -> {}, events,
                Clock.systemUTC(), 1, 100);
    }

    static RawRecord raw(long position, String txn, String req, String seq, String account, String type,
                         String amount) {
        Map<String, String> f = new HashMap<>();
        f.put("transactionId", txn);
        f.put("requestId", req);
        f.put("sequenceNumber", seq);
        f.put("accountId", account);
        f.put("transactionType", type);
        f.put("amount", amount);
        f.put("currency", "USD");
        return RawRecord.of(position, f);
    }

    TransactionRecord record(String transactionId) {
        return store.find(transactionId).orElseThrow();
    }

    TransactionStatus status(String transactionId) {
        return record(transactionId).status();
    }

    BigDecimal balance(String accountId) {
        return ledger.balance(accountId, "USD");
    }

    List<String> ledgerIds() {
        return ledger.entries().stream().map(LedgerEntry::transactionId).toList();
    }

    static int count(ProcessingReport report, TransactionStatus status) {
        return report.statusCounts().get(status);
    }

    static boolean sameAmount(String expected, BigDecimal actual) {
        return new BigDecimal(expected).compareTo(actual) == 0;
    }
}
