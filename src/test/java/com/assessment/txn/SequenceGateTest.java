package com.assessment.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assessment.txn.domain.TransactionRequest;
import com.assessment.txn.domain.TransactionType;
import com.assessment.txn.processing.SequenceGate;
import com.assessment.txn.processing.SequenceGate.Disposition;
import com.assessment.txn.processing.SequenceGate.SequenceRange;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SequenceGateTest {

    private static TransactionRequest req(long seq) {
        return new TransactionRequest("TXN-" + seq, "REQ-" + seq, seq, "ACC-1", TransactionType.CREDIT,
                BigDecimal.ONE, "USD");
    }

    private static List<Long> seqs(SequenceGate.Result result) {
        return result.released().stream().map(TransactionRequest::sequenceNumber).toList();
    }

    @Test
    void inOrderRequestsAreReleasedImmediately() {
        SequenceGate gate = new SequenceGate(1, 10);

        SequenceGate.Result r1 = gate.offer(req(1));
        SequenceGate.Result r2 = gate.offer(req(2));

        assertEquals(Disposition.READY, r1.disposition());
        assertEquals(List.of(1L), seqs(r1));
        assertEquals(Disposition.READY, r2.disposition());
        assertEquals(List.of(2L), seqs(r2));
        assertEquals(3, gate.nextExpected());
    }

    @Test
    void bufferedRequestsAreReleasedTogetherInOrderWhenTheGapCloses() {
        SequenceGate gate = new SequenceGate(1, 10);

        assertEquals(Disposition.BUFFERED, gate.offer(req(3)).disposition());
        assertEquals(Disposition.BUFFERED, gate.offer(req(2)).disposition());
        SequenceGate.Result r = gate.offer(req(1));

        assertEquals(Disposition.READY, r.disposition());
        assertEquals(List.of(1L, 2L, 3L), seqs(r));
        assertTrue(gate.missingSequences().isEmpty());
    }

    @Test
    void collisionAndStaleSequencesAreRejected() {
        SequenceGate gate = new SequenceGate(1, 10);
        gate.offer(req(2));

        assertEquals(Disposition.REJECTED_COLLISION, gate.offer(req(2)).disposition());
        gate.offer(req(1));
        assertEquals(Disposition.REJECTED_ALREADY_CONSUMED, gate.offer(req(1)).disposition());
    }

    @Test
    void skippedSlotUnblocksSuccessorsAndOnlyUnresolvedSlotsCanBeSkipped() {
        SequenceGate gate = new SequenceGate(1, 10);
        gate.offer(req(2));

        assertFalse(gate.canSkip(2), "slot already held by a request");
        assertTrue(gate.canSkip(1));
        List<TransactionRequest> released = gate.skip(1);

        assertEquals(List.of(2L), released.stream().map(TransactionRequest::sequenceNumber).toList());
        assertFalse(gate.canSkip(1), "slot already consumed");
        assertEquals(3, gate.nextExpected());
    }

    @Test
    void bufferIsBoundedButTheNextExpectedSequenceIsAlwaysAccepted() {
        SequenceGate gate = new SequenceGate(1, 2);
        gate.offer(req(3));
        gate.offer(req(4));

        assertEquals(Disposition.REJECTED_BUFFER_FULL, gate.offer(req(5)).disposition());
        SequenceGate.Result r = gate.offer(req(1));
        assertEquals(Disposition.READY, r.disposition());
    }

    @Test
    void missingSequencesAreReportedAsRanges() {
        SequenceGate gate = new SequenceGate(1, 10);
        gate.offer(req(1));
        gate.offer(req(4));
        gate.offer(req(5));
        gate.offer(req(9));

        assertEquals(List.of(new SequenceRange(2, 3), new SequenceRange(6, 8)), gate.missingSequences());
        assertEquals("2-3", new SequenceRange(2, 3).toString());
        assertEquals("7", new SequenceRange(7, 7).toString());
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SequenceGate(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new SequenceGate(1, 0));
    }
}
