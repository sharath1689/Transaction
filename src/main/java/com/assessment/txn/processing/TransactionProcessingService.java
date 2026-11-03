package com.assessment.txn.processing;

import com.assessment.txn.domain.TransactionRecord;
import com.assessment.txn.domain.TransactionRequest;
import com.assessment.txn.domain.TransactionStatus;
import com.assessment.txn.ingestion.RawRecord;
import com.assessment.txn.ingestion.TransactionSource;
import com.assessment.txn.observability.EventSink;
import com.assessment.txn.observability.Masking;
import com.assessment.txn.reporting.ProcessingReport;
import com.assessment.txn.reporting.ProcessingReport.DuplicateEvent;
import com.assessment.txn.reporting.ProcessingReport.Rejection;
import com.assessment.txn.reporting.ProcessingReport.TransactionSummary;
import com.assessment.txn.store.LedgerRepository;
import com.assessment.txn.store.TransactionStateStore;
import com.assessment.txn.validation.RequestValidator;
import com.assessment.txn.validation.ValidationOutcome;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the pipeline for each incoming record:
 *
 * <pre>
 *   validate -> duplicate check (idempotency) -> ordering gate -> atomic processing with retry
 * </pre>
 *
 * <p>All entry points are {@code synchronized}: records are handled one at a time, which makes the
 * check-then-register sequence race-free in this single-process design. For multiple workers, partition by
 * account and rely on the store's atomic {@code registerIfAbsent} (see DESIGN.md).
 */
public final class TransactionProcessingService {
    private final TransactionStateStore store;
    private final LedgerRepository ledger;
    private final RequestValidator validator = new RequestValidator();
    private final SequenceGate gate;
    private final TransactionProcessor processor;
    private final EventSink events;
    private final Clock clock;

    private final List<Rejection> rejections = new ArrayList<>();
    private final List<DuplicateEvent> duplicates = new ArrayList<>();
    private long totalReceived;

    public TransactionProcessingService(TransactionStateStore store, LedgerRepository ledger,
                                        FaultInjector faults, RetryPolicy retryPolicy, Sleeper sleeper,
                                        EventSink events, Clock clock, long firstSequence, int maxBuffered) {
        this.store = store;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
        this.gate = new SequenceGate(firstSequence, maxBuffered);
        this.processor = new TransactionProcessor(ledger, faults, retryPolicy, sleeper, events);
    }

    /** Drains a source. Each record is isolated: one bad record never stops the batch. */
    public synchronized ProcessingReport run(TransactionSource source) {
        for (RawRecord raw : source.records()) {
            submit(raw);
        }
        return report();
    }

    public synchronized void submitAll(Iterable<RawRecord> records) {
        for (RawRecord raw : records) {
            submit(raw);
        }
    }

    public synchronized void submit(RawRecord raw) {
        totalReceived++;
        try {
            doSubmit(raw);
        } catch (RuntimeException e) {
            rejections.add(new Rejection(raw.position(), List.of("INTERNAL_ERROR")));
            events.emit("record.error", "position", raw.position(), "errorType", e.getClass().getSimpleName());
        }
    }

    private void doSubmit(RawRecord raw) {
        ValidationOutcome outcome = validator.validate(raw);
        if (!outcome.valid()) {
            rejectInvalid(raw, outcome);
            return;
        }
        TransactionRequest request = outcome.request();

        Optional<TransactionRecord> existing = store.find(request.transactionId());
        if (existing.isPresent()) {
            registerDuplicate(existing.get(), request);
            return;
        }

        SequenceGate.Result offer = gate.offer(request);
        if (offer.disposition().rejected()) {
            reject(raw.position(), List.of(offer.disposition().rejectionMessage()));
            return;
        }

        TransactionRecord record = new TransactionRecord(request, clock);
        if (!store.registerIfAbsent(record)) {
            throw new IllegalStateException("Concurrent registration of an already known transaction");
        }
        events.emit("transaction.received", "transactionId", request.transactionId(),
                "requestId", request.requestId(), "sequence", request.sequenceNumber(),
                "account", Masking.account(request.accountId()), "type", request.type());

        if (offer.disposition() == SequenceGate.Disposition.BUFFERED) {
            record.transitionTo(TransactionStatus.PENDING_SEQUENCE);
            events.emit("transaction.buffered", "transactionId", request.transactionId(),
                    "sequence", request.sequenceNumber(), "nextExpected", gate.nextExpected());
            return;
        }
        processReleased(offer.released());
    }

    private void rejectInvalid(RawRecord raw, ValidationOutcome outcome) {
        reject(raw.position(), outcome.errors());
        Long hint = outcome.sequenceHint();
        if (hint != null && gate.canSkip(hint)) {
            // The rejected record owned this sequence slot. Without skipping it, every later transaction
            // would wait for a record that can never be processed.
            events.emit("sequence.skipped", "sequence", hint, "position", raw.position());
            processReleased(gate.skip(hint));
        }
    }

    private void reject(long position, List<String> reasons) {
        rejections.add(new Rejection(position, List.copyOf(reasons)));
        events.emit("record.rejected", "position", position, "reasons", String.join(";", reasons));
    }

    private void registerDuplicate(TransactionRecord original, TransactionRequest incoming) {
        boolean conflict = !original.request().fingerprint().equals(incoming.fingerprint());
        original.recordDuplicate();
        duplicates.add(new DuplicateEvent(incoming.transactionId(), incoming.requestId(), conflict));
        events.emit("transaction.duplicate", "transactionId", incoming.transactionId(),
                "requestId", incoming.requestId(), "originalStatus", original.status(),
                "payloadConflict", conflict);
    }

    private void processReleased(List<TransactionRequest> released) {
        for (TransactionRequest request : released) {
            TransactionRecord record = store.find(request.transactionId()).orElseThrow();
            processor.process(record);
        }
    }

    /**
     * Operator recovery: re-process transactions that failed only because retries ran out (transient
     * cause). Business failures are never replayed. Safe because posting is idempotent.
     *
     * @return number of transactions replayed
     */
    public synchronized int replayRetryExhausted() {
        int replayed = 0;
        for (TransactionRecord record : store.all()) {
            if (record.status() == TransactionStatus.FAILED
                    && record.failureReason() != null && record.failureReason().replayable()) {
                record.transitionTo(TransactionStatus.RETRY_PENDING);
                record.resetAttempts();
                events.emit("transaction.replay", "transactionId", record.transactionId());
                processor.process(record);
                replayed++;
            }
        }
        return replayed;
    }

    public synchronized ProcessingReport report() {
        Map<TransactionStatus, Integer> counts = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus s : TransactionStatus.values()) {
            counts.put(s, 0);
        }
        List<TransactionSummary> summaries = new ArrayList<>();
        for (TransactionRecord r : store.all()) {
            counts.merge(r.status(), 1, Integer::sum);
            summaries.add(new TransactionSummary(r.transactionId(), r.request().sequenceNumber(), r.status(),
                    r.attempts(), r.failureReason(), r.duplicateCount()));
        }
        counts.put(TransactionStatus.DUPLICATE, duplicates.size());
        counts.put(TransactionStatus.REJECTED, rejections.size());
        return new ProcessingReport(totalReceived, counts, summaries, List.copyOf(rejections),
                List.copyOf(duplicates), gate.missingSequences(), ledger.balances(),
                processor.totalRetries(), ledger.entries().size());
    }
}
