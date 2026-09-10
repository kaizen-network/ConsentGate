package io.github.consentgate.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class GateSessionTest {
    @Test void rejectsUncheckedAndForeignActions() {
        var session = new GateSession();
        assertFalse(session.accept(session.token(), false));
        assertFalse(session.accept(new GateSession().token(), true));
        assertFalse(session.decline("invalid"));
        assertTrue(session.pending());
    }

    @Test void timeoutCannotBeOverriddenByLateAcceptance() {
        var session = new GateSession();
        session.end(GateSession.Decision.TIMED_OUT);
        assertFalse(session.accept(session.token(), true));
        assertEquals(GateSession.Decision.TIMED_OUT, session.result().toCompletableFuture().join());
    }

    @Test void aDecisionCompletesOnlyOnceUnderConcurrentClicks() {
        var session = new GateSession();
        long winners = IntStream.range(0, 100).parallel()
                .filter(i -> session.accept(session.token(), true)).count();
        assertEquals(1, winners);
        assertFalse(session.decline(session.token()));
    }

    @Test void callersCannotCompleteTheSessionThroughItsResult() {
        var session = new GateSession();
        session.result().toCompletableFuture().complete(GateSession.Decision.ACCEPTED);
        assertTrue(session.pending());
        assertThrows(IllegalArgumentException.class, () -> session.end(GateSession.Decision.ACCEPTED));
    }

    @Test void shutdownReleasesWaitersWithoutAcceptance() {
        var session = new GateSession();
        CompletableFuture<GateSession.Decision> waiter = session.result().toCompletableFuture();
        session.end(GateSession.Decision.SHUTDOWN);
        assertEquals(GateSession.Decision.SHUTDOWN, waiter.join());
    }
}
