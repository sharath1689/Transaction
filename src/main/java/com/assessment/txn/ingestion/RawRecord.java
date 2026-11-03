package com.assessment.txn.ingestion;

import java.util.Map;

/**
 * An unvalidated record as delivered by a source. Sources never validate business rules; they only hand over
 * field name/value pairs, or flag a record they could not even parse.
 *
 * @param position    source-specific position (CSV line number, queue offset, ...) used to trace rejects
 * @param fields      raw field values
 * @param sourceError non-null if the source could not parse the record
 */
public record RawRecord(long position, Map<String, String> fields, String sourceError) {

    public static RawRecord of(long position, Map<String, String> fields) {
        return new RawRecord(position, Map.copyOf(fields), null);
    }

    public static RawRecord malformed(long position, String error) {
        return new RawRecord(position, Map.of(), error);
    }
}
