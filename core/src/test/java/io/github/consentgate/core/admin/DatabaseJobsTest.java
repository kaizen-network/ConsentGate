package io.github.consentgate.core.admin;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;
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

    @Test void failedJobReleasesItsCount() {
        var queue = new ArrayDeque<Runnable>();
        var jobs = new DatabaseJobs(queue::add);
        jobs.execute(() -> { throw new IllegalStateException("Test failure"); });
        assertThrows(IllegalStateException.class, () -> queue.remove().run());
        assertEquals(0, jobs.pending());
    }

    @Test void directExecutorFailureIsNotCountedTwice() {
        var jobs = new DatabaseJobs(Runnable::run);
        assertThrows(IllegalStateException.class, () -> jobs.execute(() -> { throw new IllegalStateException("Test failure"); }));
        assertEquals(0, jobs.pending());
    }
}
