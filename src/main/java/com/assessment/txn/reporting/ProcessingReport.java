package com.assessment.txn.reporting;

import com.assessment.txn.domain.BalanceKey;
import com.assessment.txn.domain.FailureReason;
import com.assessment.txn.domain.TransactionStatus;
import com.assessment.txn.observability.Masking;
import com.assessment.txn.processing.SequenceGate.SequenceRange;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Point-in-time operational snapshot.
 *
 * <p>Counting rule: every submitted record is counted exactly once. A first delivery that is accepted counts
 * under the current status of its transaction; a repeated delivery counts as DUPLICATE; an invalid one counts
 * as REJECTED. Hence {@code sum(statusCounts) == totalReceived}.
 */
public record ProcessingReport(
        long totalReceived,
        Map<TransactionStatus, Integer> statusCounts,
        List<TransactionSummary> transactions,
        List<Rejection> rejections,
        List<DuplicateEvent> duplicates,
        List<SequenceRange> missingSequences,
        Map<BalanceKey, BigDecimal> balances,
        int totalRetries,
        int ledgerEntries) {

    public record TransactionSummary(String transactionId, long sequenceNumber, TransactionStatus status,
                                     int attempts, FailureReason failureReason, int duplicateCount) {}

    /** @param position source position (e.g. CSV line) of the rejected record */
    public record Rejection(long position, List<String> reasons) {}

    public record DuplicateEvent(String transactionId, String requestId, boolean payloadConflict) {}

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Transaction Processing Report ===\n");
        sb.append("Records received : ").append(totalReceived).append('\n');
        sb.append("Retries performed: ").append(totalRetries).append('\n');
        sb.append("Ledger entries   : ").append(ledgerEntries).append("\n\n");

        sb.append("Status counts:\n");
        statusCounts.forEach((status, count) ->
                sb.append(String.format("  %-17s %d%n", status, count)));

        sb.append("\nTransactions (arrival order):\n");
        if (transactions.isEmpty()) {
            sb.append("  (none)\n");
        }
        for (TransactionSummary t : transactions) {
            sb.append(String.format("  %-10s seq=%-3d %-16s attempts=%d duplicates=%d%s%n",
                    t.transactionId(), t.sequenceNumber(), t.status(), t.attempts(), t.duplicateCount(),
                    t.failureReason() == null ? "" : " reason=" + t.failureReason()));
        }

        sb.append("\nDuplicate deliveries:\n");
        if (duplicates.isEmpty()) {
            sb.append("  (none)\n");
        }
        for (DuplicateEvent d : duplicates) {
            sb.append(String.format("  %-10s requestId=%s%s%n", d.transactionId(), d.requestId(),
                    d.payloadConflict() ? " PAYLOAD_CONFLICT" : ""));
        }

        sb.append("\nRejected records:\n");
        if (rejections.isEmpty()) {
            sb.append("  (none)\n");
        }
        for (Rejection r : rejections) {
            sb.append("  position=").append(r.position()).append(": ")
                    .append(String.join("; ", r.reasons())).append('\n');
        }

        sb.append("\nMissing sequence numbers (blocking buffered transactions):\n");
        if (missingSequences.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            sb.append("  ").append(missingSequences.stream().map(SequenceRange::toString)
                    .reduce((a, b) -> a + ", " + b).orElse("")).append('\n');
        }

        sb.append("\nBalances:\n");
        if (balances.isEmpty()) {
            sb.append("  (none)\n");
        }
        balances.forEach((key, value) -> sb.append(String.format("  %s %s = %s%n",
                Masking.account(key.accountId()), key.currency(), value.toPlainString())));
        return sb.toString();
    }
}
