package com.assessment.txn.ingestion;

/**
 * Input port. Anything that can yield raw records (file, in-memory list, queue consumer, DB poller, REST
 * endpoint) can drive the service by implementing this interface.
 */
public interface TransactionSource {
    Iterable<RawRecord> records();
}
