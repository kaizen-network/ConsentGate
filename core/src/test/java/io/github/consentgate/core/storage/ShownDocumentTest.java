package io.github.consentgate.core.storage;

import io.github.consentgate.core.document.DocumentPage;
import io.github.consentgate.core.document.DocumentRevision;
import io.github.consentgate.core.document.DocumentTranslation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShownDocumentTest {
    @Test void localeMustFitTheSharedStorageLimit() {
        String locale = "en" + "-12345678".repeat(6) + "-1234567";
        assertDoesNotThrow(() -> new ShownDocument("rules", "v1", locale, "a".repeat(64), "Title", "Snapshot"));
        assertThrows(IllegalArgumentException.class,
                () -> new ShownDocument("rules", "v1", locale + "8", "a".repeat(64), "Title", "Snapshot"));
    }

    @Test void recordsTheLocaleActuallyShown() {
        var english = new DocumentTranslation("Rules", "Summary", "Agree", "Read",
                List.of(new DocumentPage("One", "Text")), "a".repeat(64));
        var indonesian = new DocumentTranslation("Peraturan", "Ringkasan", "Setuju", "Baca",
                List.of(new DocumentPage("Satu", "Teks")), "b".repeat(64));
        var document = new DocumentRevision("rules", "v1", true, 1,
                Map.of("en-US", english, "id-ID", indonesian));
        var shown = ShownDocument.from(document, "id-ID", "en-US");
        assertEquals("id-ID", shown.locale());
        assertEquals("b".repeat(64), shown.contentHash());
        assertTrue(shown.contentSnapshot().contains("Peraturan"));
    }
}
