package com.assessment.txn.ingestion;

import java.util.List;

/** Source backed by a fixed list; useful for tests and as a template for queue adapters. */
public final class InMemorySource implements TransactionSource {
    private final List<RawRecord> records;

    public InMemorySource(List<RawRecord> records) {
        this.records = List.copyOf(records);
    }

    @Override
    public Iterable<RawRecord> records() {
        return records;
    }
}
