package com.assessment.txn;

import static com.assessment.txn.TestKit.count;
import static com.assessment.txn.TestKit.raw;
import static com.assessment.txn.TestKit.sameAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assessment.txn.domain.TransactionStatus;
import com.assessment.txn.ingestion.CsvFileSource;
import com.assessment.txn.ingestion.RawRecord;
import com.assessment.txn.processing.SequenceGate.SequenceRange;
import com.assessment.txn.reporting.ProcessingReport;
import com.assessment.txn.validation.RequestValidator;
import com.assessment.txn.validation.ValidationOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidationAndIsolationTest {

    private final RequestValidator validator = new RequestValidator();

    @Test
    void negativeAmountIsRejectedWithoutEchoingTheValue() {
        ValidationOutcome outcome = validator.validate(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "DEBIT", "-10"));

        assertFalse(outcome.valid());
        assertNull(outcome.request());
        assertTrue(outcome.errors().stream().anyMatch(e -> e.startsWith("amount:")));
        assertTrue(outcome.errors().stream().noneMatch(e -> e.contains("-10")));
    }

    @Test
    void everyMandatoryFieldIsEnforcedAndAllProblemsAreReportedTogether() {
        Map<String, String> empty = new HashMap<>();
        ValidationOutcome outcome = validator.validate(RawRecord.of(1, empty));

        assertEquals(7, outcome.errors().size());
        assertNull(outcome.sequenceHint());
    }

    @Test
    void badTypeCurrencyIdAndSequenceAreRejected() {
        assertFalse(validator.validate(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "TRANSFER", "5")).valid());
        assertFalse(validator.validate(raw(1, "bad id!", "REQ-1", "1", "ACC-1", "CREDIT", "5")).valid());
        assertFalse(validator.validate(raw(1, "TXN-1", "REQ-1", "0", "ACC-1", "CREDIT", "5")).valid());
        assertFalse(validator.validate(raw(1, "TXN-1", "REQ-1", "abc", "ACC-1", "CREDIT", "5")).valid());
        assertFalse(validator.validate(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "0")).valid());
        assertFalse(validator.validate(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "1.23456")).valid());
        assertFalse(validator.validate(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "NaN")).valid());
        assertFalse(validator.validate(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "1E999999999")).valid(),
                "exponent notation must not smuggle in astronomically large amounts");
        assertTrue(validator.validate(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100")).valid());
        assertTrue(validator.validate(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "75.50")).valid());

        Map<String, String> fields = new HashMap<>(
                raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "5").fields());
        fields.put("currency", "XXQ");
        assertFalse(validator.validate(RawRecord.of(1, fields)).valid());
        fields.put("currency", "usd");
        assertFalse(validator.validate(RawRecord.of(1, fields)).valid());
    }

    @Test
    void validRecordIsAccepted() {
        ValidationOutcome outcome = validator.validate(raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "credit", "12.50"));

        assertTrue(outcome.valid());
        assertNotNull(outcome.request());
        assertEquals(1L, outcome.sequenceHint());
    }

    @Test
    void invalidRecordDoesNotStopTheBatchAndItsSequenceSlotIsSkipped() {
        TestKit kit = new TestKit();

        kit.service.submitAll(List.of(
                raw(1, "TXN-1", "REQ-1", "1", "ACC-1", "CREDIT", "100"),
                raw(2, "TXN-2", "REQ-2", "2", "ACC-1", "DEBIT", "-10"), // invalid, owns sequence 2
                raw(3, "TXN-3", "REQ-3", "3", "ACC-1", "CREDIT", "25")));

        ProcessingReport report = kit.service.report();
        assertEquals(1, count(report, TransactionStatus.REJECTED));
        assertEquals(TransactionStatus.PROCESSED, kit.status("TXN-3"));
        assertTrue(sameAmount("125", kit.balance("ACC-1")));
        assertTrue(kit.store.find("TXN-2").isEmpty(), "rejected records are not stored as transactions");
    }

    @Test
    void invalidRecordWithAnUnusableSequenceLeavesTheGapVisible() {
        TestKit kit = new TestKit();

        kit.service.submit(raw(1, "TXN-1", "REQ-1", "abc", "ACC-1", "CREDIT", "100"));
        kit.service.submit(raw(2, "TXN-2", "REQ-2", "2", "ACC-1", "CREDIT", "25"));

        ProcessingReport report = kit.service.report();
        assertEquals(1, count(report, TransactionStatus.REJECTED));
        assertEquals(TransactionStatus.PENDING_SEQUENCE, kit.status("TXN-2"));
        assertEquals(List.of(new SequenceRange(1, 1)), report.missingSequences());
    }

    @Test
    void csvSourceTurnsMalformedRowsIntoRejectionsAndKeepsGoing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("input.csv");
        Files.writeString(file, String.join("\n",
                "transactionId,requestId,sequenceNumber,accountId,transactionType,amount,currency",
                "TXN-1,REQ-1,1,ACC-1,CREDIT,100,USD",
                "this,row,is,short",
                "",
                "TXN-2,REQ-2,2,ACC-1,CREDIT,50,USD",
                ""));

        ProcessingReport report = kit().service.run(new CsvFileSource(file));

        assertEquals(2, count(report, TransactionStatus.PROCESSED));
        assertEquals(1, count(report, TransactionStatus.REJECTED));
        assertEquals(3L, report.rejections().get(0).position(), "position is the 1-based line number");
    }

    private static TestKit kit() {
        return new TestKit();
    }
}
