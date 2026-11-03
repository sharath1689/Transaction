package com.assessment.txn.processing;

import java.time.Duration;

/** Bounded retry with exponential backoff (capped at 30 seconds). */
public record RetryPolicy(int maxAttempts, Duration baseBackoff) {
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (baseBackoff.isNegative()) {
            throw new IllegalArgumentException("baseBackoff must not be negative");
        }
    }

    /** Delay to wait after the given (1-based) failed attempt. */
    public Duration backoffFor(int attempt) {
        long factor = 1L << Math.min(Math.max(attempt - 1, 0), 10);
        Duration delay = baseBackoff.multipliedBy(factor);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }
}
