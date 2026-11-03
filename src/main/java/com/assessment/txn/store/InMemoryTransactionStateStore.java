package com.assessment.txn.store;

import com.assessment.txn.domain.TransactionRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory implementation; lost on restart (see DESIGN.md for the durable equivalent). */
public final class InMemoryTransactionStateStore implements TransactionStateStore {
    private final Map<String, TransactionRecord> records = new LinkedHashMap<>();

    @Override
    public synchronized Optional<TransactionRecord> find(String transactionId) {
        return Optional.ofNullable(records.get(transactionId));
    }

    @Override
    public synchronized boolean registerIfAbsent(TransactionRecord record) {
        return records.putIfAbsent(record.transactionId(), record) == null;
    }

    @Override
    public synchronized List<TransactionRecord> all() {
        return new ArrayList<>(records.values());
    }
}
