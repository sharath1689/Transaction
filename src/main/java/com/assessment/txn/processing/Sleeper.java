package com.assessment.txn.processing;

import java.time.Duration;

/** Abstraction over waiting so tests run instantly. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration);

    static Sleeper real() {
        return duration -> {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
