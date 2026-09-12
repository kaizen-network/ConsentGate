package io.github.consentgate.core.admin;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts queued and running work so reload cannot replace settings underneath a job. */
public final class DatabaseJobs {
    private final Executor executor;
    private final AtomicInteger pending = new AtomicInteger();

    public DatabaseJobs(Executor executor) { this.executor = executor; }
    public int pending() { return pending.get(); }

    public void execute(Runnable operation) {
        pending.incrementAndGet();
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            executor.execute(() -> {
                started.set(true);
                try { operation.run(); }
                finally { pending.decrementAndGet(); }
            });
        } catch (RuntimeException ex) {
            if (!started.get()) pending.decrementAndGet();
            throw ex;
        }
    }
}
