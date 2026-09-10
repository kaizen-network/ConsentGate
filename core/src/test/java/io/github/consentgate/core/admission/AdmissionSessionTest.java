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
