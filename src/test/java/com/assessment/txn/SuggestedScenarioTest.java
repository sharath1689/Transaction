package com.assessment.txn;

import static com.assessment.txn.TestKit.count;
import static com.assessment.txn.TestKit.raw;
import static com.assessment.txn.TestKit.sameAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assessment.txn.domain.TransactionStatus;
import com.assessment.txn.processing.ScriptedFaultInjector;
import com.assessment.txn.reporting.ProcessingReport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The exact dataset from section 9.8 of the problem statement, end to end. */
class SuggestedScenarioTest {

    @Test
    void suggestedDatasetProducesExpectedOutcomes() {
        TestKit kit = new TestKit(ScriptedFaultInjector.parse("TXN-2005:1"));

        kit.service.submitAll(List.of(
                raw(2, "TXN-2002", "REQ-2", "2", "ACC-201", "DEBIT", "40"),
                raw(3, "TXN-2001", "REQ-1", "1", "ACC-201", "CREDIT", "100"),
                raw(4, "TXN-2002", "REQ-2-DUP", "2", "ACC-201", "DEBIT", "40"),
                raw(5, "TXN-2003", "REQ-3", "3", "ACC-201", "CREDIT", "25"),
                raw(6, "TXN-2004", "REQ-4", "4", "ACC-202", "DEBIT", "-10"),
                raw(7, "TXN-2005", "REQ-5", "5", "ACC-203", "CREDIT", "500")));

        ProcessingReport report = kit.service.report();

        assertEquals(6, report.totalReceived());
        assertEquals(4, count(report, TransactionStatus.PROCESSED));
        assertEquals(1, count(report, TransactionStatus.DUPLICATE));
        assertEquals(1, count(report, TransactionStatus.REJECTED));
        assertEquals(0, count(report, TransactionStatus.FAILED));
        assertEquals(0, count(report, TransactionStatus.PENDING_SEQUENCE));
        assertEquals(6, report.statusCounts().values().stream().mapToInt(Integer::intValue).sum(),
                "every received record is counted exactly once");

        // Posted strictly in sequence order even though TXN-2002 arrived first.
        assertEquals(List.of("TXN-2001", "TXN-2002", "TXN-2003", "TXN-2005"), kit.ledgerIds());

        // 100 - 40 + 25, with the duplicate debit applied zero extra times.
        assertTrue(sameAmount("85", kit.balance("ACC-201")));
        assertTrue(sameAmount("500", kit.balance("ACC-203")));
        assertTrue(sameAmount("0", kit.balance("ACC-202")));

        // Retry once, then processed.
        assertEquals(2, kit.record("TXN-2005").attempts());
        assertEquals(1, report.totalRetries());
        assertTrue(report.missingSequences().isEmpty(), "the rejected sequence 4 must not stall sequence 5");
    }
}
