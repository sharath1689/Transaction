package com.assessment.txn;

import static com.assessment.txn.TestKit.count;
import static com.assessment.txn.TestKit.raw;
import static com.assessment.txn.TestKit.sameAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assessment.txn.domain.FailureReason;
import com.assessment.txn.domain.TransactionRecord;
import com.assessment.txn.domain.TransactionRequest;
import com.assessment.txn.domain.TransactionStatus;
import com.assessment.txn.domain.TransactionType;
import com.assessment.txn.domain.TransientProcessingException;
import com.assessment.txn.processing.ScriptedFaultInjector;
import com.assessment.txn.reporting.ProcessingReport;
import com.assessment.txn.store.InMemoryLedgerRepository;
import com.assessment.txn.store.PostingResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResilienceTest {

    @Test
    void transientFailureIsRetriedAndEffectAppliedExactlyOnce() {
        TestKit kit = new TestKit(ScriptedFaultInjector.parse("TXN-1:2"));

        kit.service.submit(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"));

        TransactionRecord record = kit.record("TXN-1");
        assertEquals(TransactionStatus.PROCESSED, record.status());
        assertEquals(3, record.attempts());
        assertEquals(List.of(
                        TransactionStatus.RECEIVED,
                        TransactionStatus.PROCESSING, TransactionStatus.RETRY_PENDING,
                        TransactionStatus.PROCESSING, TransactionStatus.RETRY_PENDING,
                        TransactionStatus.PROCESSING, TransactionStatus.PROCESSED),
                record.statusTrail());
        assertEquals(1, kit.ledger.entries().size());
        assertTrue(sameAmount("100", kit.balance("ACC-1")));
        assertEquals(2, kit.service.report().totalRetries());
    }

    @Test
    void retriesAreBoundedAndExhaustionEndsInFailedWithNoEffect() {
        TestKit kit = new TestKit(ScriptedFaultInjector.parse("TXN-1:99"), 3);

        kit.service.submit(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"));

        TransactionRecord record = kit.record("TXN-1");
        assertEquals(TransactionStatus.FAILED, record.status());
        assertEquals(FailureReason.RETRIES_EXHAUSTED, record.failureReason());
        assertEquals(3, record.attempts(), "exactly maxAttempts attempts, never more");
        assertTrue(kit.ledger.entries().isEmpty());
        assertTrue(sameAmount("0", kit.balance("ACC-1")));
    }

    @Test
    void failedRecordDoesNotBlockLaterTransactions() {
        TestKit kit = new TestKit(ScriptedFaultInjector.parse("TXN-1:99"), 2);

        kit.service.submitAll(List.of(
                raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"),
                raw(2, "TXN-2", "REQ-2", "2", "ACC-2", "CREDIT", "7")));

        assertEquals(TransactionStatus.FAILED, kit.status("TXN-1"));
        assertEquals(TransactionStatus.PROCESSED, kit.status("TXN-2"));
    }

    @Test
    void retryExhaustedTransactionCanBeReplayedOnceTheCauseIsGoneAndStaysIdempotent() {
        ScriptedFaultInjector faults = ScriptedFaultInjector.parse("TXN-1:99");
        TestKit kit = new TestKit(faults, 3);
        kit.service.submit(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"));
        assertEquals(TransactionStatus.FAILED, kit.status("TXN-1"));

        faults.clear();
        assertEquals(1, kit.service.replayRetryExhausted());

        TransactionRecord record = kit.record("TXN-1");
        assertEquals(TransactionStatus.PROCESSED, record.status());
        assertEquals(1, record.attempts(), "attempt counter restarts for a replay");
        assertTrue(sameAmount("100", kit.balance("ACC-1")));

        assertEquals(0, kit.service.replayRetryExhausted(), "nothing left to replay");
        assertEquals(1, kit.ledger.entries().size());
    }

    @Test
    void businessFailureIsNotRetriedAndNotReplayable() {
        TestKit kit = new TestKit();

        kit.service.submit(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "DEBIT", "50"));

        TransactionRecord record = kit.record("TXN-1");
        assertEquals(TransactionStatus.FAILED, record.status());
        assertEquals(FailureReason.INSUFFICIENT_FUNDS, record.failureReason());
        assertEquals(1, record.attempts());
        assertEquals(0, kit.service.report().totalRetries());
        assertEquals(0, kit.service.replayRetryExhausted());
    }

    @Test
    void failureInTheMiddleOfPostingLeavesNoPartialState() {
        InMemoryLedgerRepository ledger = new InMemoryLedgerRepository(Clock.systemUTC());
        TransactionRequest request = new TransactionRequest("TXN-1", "REQ-1", 1, "ACC-1",
                TransactionType.CREDIT, new BigDecimal("100"), "USD");

        assertThrows(TransientProcessingException.class, () -> ledger.post(request, () -> {
            throw new TransientProcessingException("boom");
        }));
        assertTrue(ledger.entries().isEmpty());
        assertTrue(ledger.balances().isEmpty());

        assertEquals(PostingResult.APPLIED, ledger.post(request, () -> {}));
        assertEquals(PostingResult.ALREADY_APPLIED, ledger.post(request, () -> {}),
                "the ledger itself refuses a second effect for the same transactionId");
        assertEquals(1, ledger.entries().size());
        assertTrue(sameAmount("100", ledger.balance("ACC-1", "USD")));
    }

    @Test
    void lifecycleRejectsIllegalTransitions() {
        TransactionRequest request = new TransactionRequest("TXN-1", "REQ-1", 1, "ACC-1",
                TransactionType.CREDIT, BigDecimal.TEN, "USD");
        TransactionRecord record = new TransactionRecord(request, Clock.systemUTC());

        assertThrows(IllegalStateException.class, () -> record.transitionTo(TransactionStatus.PROCESSED));
        record.transitionTo(TransactionStatus.PROCESSING);
        record.transitionTo(TransactionStatus.PROCESSED);
        assertThrows(IllegalStateException.class, () -> record.transitionTo(TransactionStatus.PROCESSING));
        assertSame(TransactionStatus.PROCESSED, record.status());
    }

    @Test
    void eventsNeverContainRawAccountIdentifiers() {
        TestKit kit = new TestKit(ScriptedFaultInjector.parse("TXN-2:1"));

        kit.service.submitAll(List.of(
                raw(1, "TXN-1", "REQ-1", "1", "ACC-SECRET-777", "CREDIT", "100"),
                raw(2, "TXN-2", "REQ-2", "2", "ACC-SECRET-777", "CREDIT", "5"),
                raw(3, "TXN-1", "REQ-1B", "1", "ACC-SECRET-777", "CREDIT", "100"),
                raw(4, "TXN-3", "REQ-3", "3", "ACC-SECRET-777", "CREDIT", "-1")));

        assertTrue(kit.events.lines.size() > 5, "events were emitted");
        assertTrue(kit.events.lines.stream().noneMatch(l -> l.contains("ACC-SECRET-777")));
        assertTrue(kit.service.report().toText().contains("***777"));
        assertTrue(!kit.service.report().toText().contains("ACC-SECRET-777"));
    }

    @Test
    void reportCountsEveryReceivedRecordExactlyOnce() {
        TestKit kit = new TestKit(ScriptedFaultInjector.parse("TXN-3:99"), 2);

        kit.service.submitAll(List.of(
                raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"),
                raw(2, "TXN-1", "REQ-1B", "1", "ACC-1", "CREDIT", "100"),
                raw(3, "TXN-2", "REQ-2", "2", "ACC-1", "DEBIT", "500"),
                raw(4, "TXN-3", "REQ-3", "3", "ACC-1", "CREDIT", "1"),
                raw(5, "TXN-4", "REQ-4", "4", "ACC-1", "CREDIT", "x"),
                raw(6, "TXN-6", "REQ-6", "6", "ACC-1", "CREDIT", "1")));

        ProcessingReport report = kit.service.report();
        assertEquals(6, report.totalReceived());
        assertEquals(1, count(report, TransactionStatus.PROCESSED));
        assertEquals(2, count(report, TransactionStatus.FAILED));
        assertEquals(1, count(report, TransactionStatus.DUPLICATE));
        assertEquals(1, count(report, TransactionStatus.REJECTED));
        assertEquals(1, count(report, TransactionStatus.PENDING_SEQUENCE));
        assertEquals(6, report.statusCounts().values().stream().mapToInt(Integer::intValue).sum());
    }
}
