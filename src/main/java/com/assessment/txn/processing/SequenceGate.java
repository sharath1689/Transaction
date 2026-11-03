package com.assessment.txn.processing;

import com.assessment.txn.domain.TransactionRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Ordering strategy (Option A, sequence-based processing) as a pure data structure with no I/O.
 *
 * <p>Requests are released strictly in sequence order starting at {@code firstSequence}. A request whose
 * predecessors have not all been resolved is buffered. A sequence slot is "resolved" when its request has
 * been released or when it was explicitly {@linkplain #skip skipped} (a rejected record that can never be
 * processed must not stall the stream forever).
 *
 * <p>Not thread-safe; the processing service serialises access.
 */
public final class SequenceGate {

    public enum Disposition {
        /** The request (and possibly buffered successors) can be processed now. */
        READY(null),
        /** An earlier sequence is still missing; the request is held. */
        BUFFERED(null),
        REJECTED_ALREADY_CONSUMED("sequenceNumber: already consumed by another transaction"),
        REJECTED_COLLISION("sequenceNumber: already held by another pending transaction"),
        REJECTED_BUFFER_FULL("sequenceNumber: out-of-order buffer is full");

        private final String rejectionMessage;

        Disposition(String rejectionMessage) {
            this.rejectionMessage = rejectionMessage;
        }

        public boolean rejected() {
            return rejectionMessage != null;
        }

        public String rejectionMessage() {
            return rejectionMessage;
        }
    }

    /** @param released requests now safe to process, in sequence order (includes the offered one if READY) */
    public record Result(Disposition disposition, List<TransactionRequest> released) {}

    /** Inclusive range of missing sequence numbers. */
    public record SequenceRange(long from, long to) {
        @Override
        public String toString() {
            return from == to ? String.valueOf(from) : from + "-" + to;
        }
    }

    /** A slot holds a request, or null if the sequence number was skipped. */
    private record Slot(TransactionRequest request) {}

    private final TreeMap<Long, Slot> slots = new TreeMap<>();
    private final int maxBuffered;
    private long nextExpected;

    public SequenceGate(long firstSequence, int maxBuffered) {
        if (firstSequence < 1 || maxBuffered < 1) {
            throw new IllegalArgumentException("firstSequence and maxBuffered must be >= 1");
        }
        this.nextExpected = firstSequence;
        this.maxBuffered = maxBuffered;
    }

    public Result offer(TransactionRequest request) {
        long seq = request.sequenceNumber();
        if (seq < nextExpected) {
            return new Result(Disposition.REJECTED_ALREADY_CONSUMED, List.of());
        }
        if (slots.containsKey(seq)) {
            return new Result(Disposition.REJECTED_COLLISION, List.of());
        }
        if (seq != nextExpected && slots.size() >= maxBuffered) {
            return new Result(Disposition.REJECTED_BUFFER_FULL, List.of());
        }
        slots.put(seq, new Slot(request));
        List<TransactionRequest> released = drain();
        Disposition d = nextExpected > seq ? Disposition.READY : Disposition.BUFFERED;
        return new Result(d, released);
    }

    public boolean canSkip(long sequence) {
        return sequence >= nextExpected && !slots.containsKey(sequence);
    }

    /** Marks a sequence number as consumed without a request; returns requests that became releasable. */
    public List<TransactionRequest> skip(long sequence) {
        if (!canSkip(sequence)) {
            return List.of();
        }
        slots.put(sequence, new Slot(null));
        return drain();
    }

    public long nextExpected() {
        return nextExpected;
    }

    /** Sequence numbers (as ranges) between the next expected one and the highest buffered one. */
    public List<SequenceRange> missingSequences() {
        List<SequenceRange> gaps = new ArrayList<>();
        long previous = nextExpected - 1;
        for (long key : slots.keySet()) {
            if (key > previous + 1) {
                gaps.add(new SequenceRange(previous + 1, key - 1));
            }
            previous = key;
        }
        return gaps;
    }

    private List<TransactionRequest> drain() {
        List<TransactionRequest> released = new ArrayList<>();
        Slot slot;
        while ((slot = slots.remove(nextExpected)) != null) {
            nextExpected++;
            if (slot.request() != null) {
                released.add(slot.request());
            }
        }
        return released;
    }
}
