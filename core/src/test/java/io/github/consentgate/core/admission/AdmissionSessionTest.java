package io.github.consentgate.core.admission;

import io.github.consentgate.core.GateSession;
import io.github.consentgate.core.document.DocumentPage;
import io.github.consentgate.core.storage.ShownDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdmissionSessionTest {
    @Test void requiresEveryDocument() {
        var session = session();
        assertFalse(session.accept(session.token(), Map.of("rules", true, "privacy", false)));
        assertTrue(session.pending());
        assertTrue(session.accept(session.token(), Map.of("rules", true, "privacy", true)));
        assertEquals(GateSession.Decision.ACCEPTED, session.result().toCompletableFuture().join());
    }

    @Test void preservesValidSelectionsAcrossNavigation() {
        var session = session();
        assertTrue(session.updateSelections(session.token(), Map.of("rules", true, "privacy", false)));
        assertEquals(Map.of("rules", true, "privacy", false), session.selections());
    }

    @Test void rejectsForgedStaleAndIncompleteResponses() {
        var session = session();
        assertFalse(session.updateSelections("wrong", Map.of("rules", true, "privacy", true)));
        assertFalse(session.updateSelections(session.token(), Map.of("rules", true)));
        assertFalse(session.updateSelections(session.token(), Map.of("rules", true, "other", true)));
        assertEquals(Map.of("rules", false, "privacy", false), session.selections());
        assertTrue(session.decline(session.token()));
        assertFalse(session.accept(session.token(), Map.of("rules", true, "privacy", true)));
    }

    @Test void rejectsDuplicateDocumentIds() {
        AdmissionDocument document = document("rules", "a");
        var request = new AdmissionRequest(UUID.randomUUID(), UUID.randomUUID(), List.of(document, document));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionSession(request));
    }

    @Test void completionCallbacksRunWithoutHoldingTheSessionLock() {
        for (var decision : GateSession.Decision.values()) {
            var session = session();
            var heldLock = new java.util.concurrent.atomic.AtomicBoolean(true);
            var callback = session.result().thenAccept(ignored -> heldLock.set(Thread.holdsLock(session)));
            if (decision == GateSession.Decision.ACCEPTED) session.accept(session.token(), Map.of("rules", true, "privacy", true));
            else if (decision == GateSession.Decision.DECLINED) session.decline(session.token());
            else session.end(decision);
            callback.toCompletableFuture().join();
            assertFalse(heldLock.get(), decision.toString());
        }
    }

    @Test void slowCompletionCallbackCannotAllowAnotherDecisionOrSelectionChange() throws Exception {
        var session = session();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var callback = session.result().thenAccept(ignored -> {
            entered.countDown();
            try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
        });
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var response = pool.submit(() -> session.accept(session.token(), Map.of("rules", true, "privacy", true)));
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(session.pending());
                assertTrue(session.accepted());
                assertFalse(session.end(GateSession.Decision.TIMED_OUT));
                assertFalse(session.decline(session.token()));
                assertFalse(session.updateSelections(session.token(), Map.of("rules", false, "privacy", false)));
                assertEquals(Map.of("rules", true, "privacy", true), session.selections());
            } finally { release.countDown(); }
            assertTrue(response.get(5, java.util.concurrent.TimeUnit.SECONDS));
            callback.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static AdmissionSession session() {
        return new AdmissionSession(new AdmissionRequest(UUID.randomUUID(), UUID.randomUUID(),
                List.of(document("rules", "a"), document("privacy", "b"))));
    }

    private static AdmissionDocument document(String id, String hashCharacter) {
        var shown = new ShownDocument(id, "v1", "en-US", hashCharacter.repeat(64), "Title", "Snapshot");
        return new AdmissionDocument(id, "v1", "en-US", "Title", "Summary", "I agree", "Read",
                List.of(new DocumentPage("Page", "Text")), shown);
    }
}
