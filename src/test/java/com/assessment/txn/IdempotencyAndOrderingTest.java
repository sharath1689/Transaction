package com.assessment.txn;

import static com.assessment.txn.TestKit.count;
import static com.assessment.txn.TestKit.raw;
import static com.assessment.txn.TestKit.sameAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assessment.txn.domain.TransactionStatus;
import com.assessment.txn.reporting.ProcessingReport;
import com.assessment.txn.processing.SequenceGate.SequenceRange;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IdempotencyAndOrderingTest {

    @Test
    void futureSequenceIsBufferedUntilPredecessorArrives() {
        TestKit kit = new TestKit();

        kit.service.submit(raw(1, "TXN-2", "REQ-2", "2", "ACC-1", "CREDIT", "10"));
        assertEquals(TransactionStatus.PENDING_SEQUENCE, kit.status("TXN-2"));
        assertTrue(kit.ledgerIds().isEmpty(), "nothing may be posted before sequence 1");
        assertEquals(List.of(new SequenceRange(1, 1)), kit.service.report().missingSequences());

        kit.service.submit(raw(2, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "5"));
        assertEquals(TransactionStatus.PROCESSED, kit.status("TXN-1"));
        assertEquals(TransactionStatus.PROCESSED, kit.status("TXN-2"));
        assertEquals(List.of("TXN-1", "TXN-2"), kit.ledgerIds());
        assertTrue(kit.service.report().missingSequences().isEmpty());
    }

    @Test
    void debitArrivingBeforeItsFundingCreditSucceedsBecauseOfOrdering() {
        TestKit kit = new TestKit();

        kit.service.submit(raw(1, "TXN-2", "REQ-2", "2", "ACC-1", "DEBIT", "40"));
        kit.service.submit(raw(2, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"));

        assertEquals(TransactionStatus.PROCESSED, kit.status("TXN-2"));
        assertTrue(sameAmount("60", kit.balance("ACC-1")));
    }

    @Test
    void reportListsMissingSequenceRanges() {
        TestKit kit = new TestKit();

        kit.service.submit(raw(1, "TXN-3", "REQ-3", "3", "ACC-1", "CREDIT", "10"));
        kit.service.submit(raw(2, "TXN-7", "REQ-7", "7", "ACC-1", "CREDIT", "10"));

        ProcessingReport report = kit.service.report();
        assertEquals(List.of(new SequenceRange(1, 2), new SequenceRange(4, 6)), report.missingSequences());
        assertEquals(2, count(report, TransactionStatus.PENDING_SEQUENCE));
    }

    @Test
    void duplicateArrivingAfterProcessingHasNoSideEffect() {
        TestKit kit = new TestKit();
        kit.service.submit(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"));

        for (int i = 0; i < 5; i++) {
            kit.service.submit(raw(2 + i, "TXN-1", "REQ-1-RETRY-" + i, "1", "ACC-1", "CREDIT", "100"));
        }

        ProcessingReport report = kit.service.report();
        assertEquals(1, kit.ledger.entries().size());
        assertTrue(sameAmount("100", kit.balance("ACC-1")));
        assertEquals(5, count(report, TransactionStatus.DUPLICATE));
        assertEquals(5, kit.record("TXN-1").duplicateCount());
        assertEquals(TransactionStatus.PROCESSED, kit.status("TXN-1"), "a duplicate never alters the original");
        assertTrue(report.duplicates().stream().noneMatch(d -> d.payloadConflict()));
    }

    @Test
    void duplicateArrivingWhileOriginalIsStillBufferedIsDetected() {
        TestKit kit = new TestKit();
        kit.service.submit(raw(1, "TXN-2", "REQ-2", "2", "ACC-1", "DEBIT", "40"));
        kit.service.submit(raw(2, "TXN-2", "REQ-2-DUP", "2", "ACC-1", "DEBIT", "40"));
        kit.service.submit(raw(3, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"));

        assertEquals(1, count(kit.service.report(), TransactionStatus.DUPLICATE));
        assertEquals(List.of("TXN-1", "TXN-2"), kit.ledgerIds());
        assertTrue(sameAmount("60", kit.balance("ACC-1")), "the debit must be applied exactly once");
    }

    @Test
    void sameTransactionIdWithDifferentPayloadIsFlaggedAndNeverReprocessed() {
        TestKit kit = new TestKit();
        kit.service.submit(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"));
        kit.service.submit(raw(2, "TXN-1", "REQ-1B", "1", "ACC-1", "CREDIT", "999"));

        ProcessingReport report = kit.service.report();
        assertEquals(1, report.duplicates().size());
        assertTrue(report.duplicates().get(0).payloadConflict());
        assertTrue(sameAmount("100", kit.balance("ACC-1")));
    }

    @Test
    void differentTransactionClaimingAnAlreadyConsumedSequenceIsRejected() {
        TestKit kit = new TestKit();
        kit.service.submit(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"));
        kit.service.submit(raw(2, "TXN-OTHER", "REQ-X", "1", "ACC-1", "CREDIT", "5"));

        ProcessingReport report = kit.service.report();
        assertEquals(1, count(report, TransactionStatus.REJECTED));
        assertTrue(kit.store.find("TXN-OTHER").isEmpty());
        assertTrue(sameAmount("100", kit.balance("ACC-1")));
    }

    @Test
    void concurrentDeliveriesOfTheSameTransactionApplyItOnce() throws Exception {
        TestKit kit = new TestKit();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                kit.service.submit(raw(n, "TXN-1", "REQ-" + n, "1", "ACC-1", "CREDIT", "100"));
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        ProcessingReport report = kit.service.report();
        assertEquals(1, count(report, TransactionStatus.PROCESSED));
        assertEquals(threads - 1, count(report, TransactionStatus.DUPLICATE));
        assertEquals(1, kit.ledger.entries().size());
        assertTrue(sameAmount("100", kit.balance("ACC-1")));
        assertFalse(report.transactions().isEmpty());
    }
}
