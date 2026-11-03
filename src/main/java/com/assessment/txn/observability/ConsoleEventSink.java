package com.assessment.txn.observability;

import java.io.PrintStream;
import java.time.Clock;

/** Writes one key=value line per event. Values are sanitised to prevent log injection. */
public final class ConsoleEventSink implements EventSink {
    private final PrintStream out;
    private final Clock clock;

    public ConsoleEventSink(PrintStream out, Clock clock) {
        this.out = out;
        this.clock = clock;
    }

    @Override
    public void emit(String event, Object... keyValues) {
        StringBuilder sb = new StringBuilder("ts=").append(clock.instant()).append(" event=").append(event);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            sb.append(' ').append(keyValues[i]).append('=').append(sanitize(String.valueOf(keyValues[i + 1])));
        }
        out.println(sb);
    }

    static String sanitize(String value) {
        return value.replaceAll("[^\\x21-\\x7E]", "_");
    }
}
