package io.github.consentgate.presentation;

import io.github.consentgate.core.document.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AdminDocumentsTest {
    private final DocumentCatalog catalog = new DocumentCatalog(List.of(new DocumentRevision("rules", "v1", true, 1,
            Map.of("en-US", new DocumentTranslation("<gold>Rules</gold>", "Summary", "Agree", "Read",
                    List.of(new DocumentPage("First", "<bold>Text</bold>"), new DocumentPage("Second", "\uD83D\uDE00".repeat(300))), "a".repeat(64))))));

    private List<String> show(String... args) {
        var replies = new ArrayList<String>();
        AdminDocuments.show(catalog, "en-US", args, replies::add);
        return replies;
    }

    @Test void listsActiveVersionsAndRendersTheRequestedPageWithoutMarkup() {
        assertTrue(show().getLast().contains("rules (v1): required; locales: en-US"));
        var first = show("rules");
        assertTrue(first.contains("Rules"));
        assertTrue(first.contains("Text"));
        assertFalse(first.stream().anyMatch(line -> line.contains("<gold>")));
        var second = show("rules", "EN_us", "2");
        assertTrue(second.contains("Second"));
        assertTrue(second.contains("\uD83D\uDE00".repeat(240)));
        assertTrue(second.contains("\uD83D\uDE00".repeat(60)));
        assertFalse(second.contains("Text"));
    }

    @Test void rejectsUnknownDocumentLocaleAndInvalidPagesWithoutShowingAnotherText() {
        assertTrue(show("missing").getFirst().contains("Unknown document"));
        assertTrue(show("rules", "id-ID").getFirst().contains("Unknown translation"));
        for (String page : List.of("0", "3", "-1", "not-a-page", "9999999999999999")) {
            var result = show("rules", "en-US", page);
            assertEquals(1, result.size());
            assertTrue(result.getFirst().startsWith("Page must"));
        }
    }
}
