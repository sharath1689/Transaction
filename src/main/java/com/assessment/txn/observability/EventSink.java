package com.assessment.txn.observability;

/**
 * Structured event output (key=value pairs). Replaceable: console today, a JSON logger or a metrics/tracing
 * exporter in production. Callers must never pass sensitive values (use {@link Masking}).
 */
public interface EventSink {

    void emit(String event, Object... keyValues);

    static EventSink noop() {
        return (event, keyValues) -> {};
    }
}
