package com.assessment.txn;

import com.assessment.txn.ingestion.CsvFileSource;
import com.assessment.txn.observability.ConsoleEventSink;
import com.assessment.txn.processing.RetryPolicy;
import com.assessment.txn.processing.ScriptedFaultInjector;
import com.assessment.txn.processing.Sleeper;
import com.assessment.txn.processing.TransactionProcessingService;
import com.assessment.txn.reporting.ProcessingReport;
import com.assessment.txn.store.InMemoryLedgerRepository;
import com.assessment.txn.store.InMemoryTransactionStateStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * Command-line runner (composition root). Events go to stderr, the final report to stdout.
 *
 * <pre>
 *   --input &lt;file&gt;               CSV input (default samples/transactions-suggested.csv)
 *   --max-attempts &lt;n&gt;           attempts per transaction incl. the first (default 3)
 *   --backoff-ms &lt;n&gt;             base retry backoff in milliseconds (default 200)
 *   --transient-failures &lt;spec&gt;  simulated failures, e.g. TXN-2005:1,TXN-2006:99 (default TXN-2005:1)
 *   --replay-failed              after the batch, clear simulated faults and replay retry-exhausted transactions
 * </pre>
 */
public final class App {
    private App() {}

    public static void main(String[] args) {
        String input = "samples/transactions-suggested.csv";
        int maxAttempts = 3;
        long backoffMs = 200;
        String failureSpec = "TXN-2005:1";
        boolean replayFailed = false;

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--input" -> input = args[++i];
                    case "--max-attempts" -> maxAttempts = Integer.parseInt(args[++i]);
                    case "--backoff-ms" -> backoffMs = Long.parseLong(args[++i]);
                    case "--transient-failures" -> failureSpec = args[++i];
                    case "--replay-failed" -> replayFailed = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }

            Clock clock = Clock.systemUTC();
            ScriptedFaultInjector faults = ScriptedFaultInjector.parse(failureSpec);
            TransactionProcessingService service = new TransactionProcessingService(
                    new InMemoryTransactionStateStore(),
                    new InMemoryLedgerRepository(clock),
                    faults,
                    new RetryPolicy(maxAttempts, Duration.ofMillis(backoffMs)),
                    Sleeper.real(),
                    new ConsoleEventSink(System.err, clock),
                    clock,
                    1,
                    10_000);

            ProcessingReport report = service.run(new CsvFileSource(Path.of(input)));
            System.out.println(report.toText());

            if (replayFailed) {
                faults.clear(); // models "the failing dependency has recovered"
                int replayed = service.replayRetryExhausted();
                System.out.println("--- After replay of " + replayed + " retry-exhausted transaction(s) ---");
                System.out.println(service.report().toText());
            }
        } catch (RuntimeException e) {
            System.err.println("Fatal: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
