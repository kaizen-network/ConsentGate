package io.github.consentgate.core.admin;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ServiceConfigurationError;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseJobsTest {
    @Test void countsBothQueuedAndRunningWork() {
        var queue = new ArrayDeque<Runnable>();
        var jobs = new DatabaseJobs(queue::add);
        jobs.execute(() -> assertEquals(2, jobs.pending()));
        jobs.execute(() -> assertEquals(1, jobs.pending()));
        assertEquals(2, jobs.pending());
        queue.remove().run();
        assertEquals(1, jobs.pending());
        queue.remove().run();
        assertEquals(0, jobs.pending());
    }

    @Test void rejectionDoesNotLeavePhantomWork() {
        var jobs = new DatabaseJobs(task -> { throw new RejectedExecutionException(); });
        assertThrows(RejectedExecutionException.class, () -> jobs.execute(() -> fail("Must not run")));
        assertEquals(0, jobs.pending());
    }

    // An Error such as ServiceConfigurationError used to end the worker thread; it is now reported and the worker keeps running.
    @Test void failedJobIsReportedReleasesItsCountAndDoesNotEndTheWorker() {
        var queue = new ArrayDeque<Runnable>();
        var jobs = new DatabaseJobs(queue::add);
        jobs.execute(() -> { throw new ServiceConfigurationError("Test failure"); });
        assertInstanceOf(ServiceConfigurationError.class, reported(() -> queue.remove().run()));
        assertEquals(0, jobs.pending());
    }

    @Test void directExecutorFailureIsNotCountedTwice() {
        var jobs = new DatabaseJobs(Runnable::run);
        assertInstanceOf(IllegalStateException.class, reported(() -> jobs.execute(() -> { throw new IllegalStateException("Test failure"); })));
        assertEquals(0, jobs.pending());
    }

    private static Throwable reported(Runnable action) {
        var reported = new AtomicReference<Throwable>();
        var thread = Thread.currentThread();
        var previous = thread.getUncaughtExceptionHandler();
        thread.setUncaughtExceptionHandler((ignored, failure) -> reported.set(failure));
        try { assertDoesNotThrow(action::run); }
        finally { thread.setUncaughtExceptionHandler(previous); }
        return reported.get();
    }
}
