package io.github.consentgate.core.admission;

import io.github.consentgate.core.document.DocumentPage;
import io.github.consentgate.core.storage.ShownDocument;

import java.util.List;
import java.util.Objects;

public record AdmissionDocument(
        String id,
        String version,
        String locale,
        String title,
        String summary,
        String checkbox,
        String readButton,
        List<DocumentPage> pages,
        ShownDocument shown
) {
    public AdmissionDocument {
        id = Objects.requireNonNull(id, "id");
        version = Objects.requireNonNull(version, "version");
        locale = Objects.requireNonNull(locale, "locale");
        title = Objects.requireNonNull(title, "title");
        summary = Objects.requireNonNull(summary, "summary");
        checkbox = Objects.requireNonNull(checkbox, "checkbox");
        readButton = Objects.requireNonNull(readButton, "readButton");
        pages = List.copyOf(pages);
        shown = Objects.requireNonNull(shown, "shown");
        if (!id.equals(shown.documentId()) || !version.equals(shown.version()) || !locale.equals(shown.locale())) {
            throw new IllegalArgumentException("Shown document identity does not match presentation");
        }
    }
}
