package io.github.consentgate.core.document;

import java.util.List;
import java.util.Objects;

public record DocumentTranslation(
        String title,
        String summary,
        String checkbox,
        String readButton,
        List<DocumentPage> pages,
        String contentHash
) {
    public DocumentTranslation {
        title = Objects.requireNonNull(title, "title");
        summary = Objects.requireNonNull(summary, "summary");
        checkbox = Objects.requireNonNull(checkbox, "checkbox");
        readButton = Objects.requireNonNull(readButton, "readButton");
        pages = List.copyOf(pages);
        contentHash = Objects.requireNonNull(contentHash, "contentHash");
    }
}
