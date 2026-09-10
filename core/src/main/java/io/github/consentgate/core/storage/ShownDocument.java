package io.github.consentgate.core.storage;

import io.github.consentgate.core.document.DocumentPage;
import io.github.consentgate.core.document.DocumentRevision;
import io.github.consentgate.core.document.DocumentTranslation;

import java.util.Objects;
import java.util.regex.Pattern;

public record ShownDocument(
        String documentId,
        String version,
        String locale,
        String contentHash,
        String title,
        String contentSnapshot
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2,8}(?:[-_][A-Za-z0-9]{1,8})*");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public ShownDocument {
        documentId = Objects.requireNonNull(documentId, "documentId");
        version = Objects.requireNonNull(version, "version");
        locale = Objects.requireNonNull(locale, "locale");
        contentHash = Objects.requireNonNull(contentHash, "contentHash");
        title = Objects.requireNonNull(title, "title");
        contentSnapshot = Objects.requireNonNull(contentSnapshot, "contentSnapshot");
        if (!ID.matcher(documentId).matches()) throw new IllegalArgumentException("Invalid document id: " + documentId);
        if (version.isEmpty() || version.length() > 64) throw new IllegalArgumentException("Version must contain 1 to 64 characters");
        if (!LOCALE.matcher(locale).matches()) throw new IllegalArgumentException("Invalid locale: " + locale);
        if (!HASH.matcher(contentHash).matches()) throw new IllegalArgumentException("Invalid SHA-256 content hash");
        if (title.isEmpty() || title.length() > 128) throw new IllegalArgumentException("Title must contain 1 to 128 characters");
        if (contentSnapshot.length() > 256 * 1024) throw new IllegalArgumentException("Content snapshot exceeds 256 KiB");
    }

    public static ShownDocument from(DocumentRevision document, String locale, String fallbackLocale) {
        DocumentRevision.SelectedTranslation selected = document.selectTranslation(locale, fallbackLocale);
        DocumentTranslation translation = selected.translation();
        return new ShownDocument(document.id(), document.version(), selected.locale(),
                translation.contentHash(), translation.title(), snapshot(translation));
    }

    private static String snapshot(DocumentTranslation translation) {
        var result = new StringBuilder();
        field(result, "title", translation.title());
        field(result, "summary", translation.summary());
        field(result, "checkbox", translation.checkbox());
        field(result, "read-button", translation.readButton());
        for (DocumentPage page : translation.pages()) {
            result.append("page\n");
            field(result, "title", page.title());
            field(result, "body", page.body());
        }
        return result.toString();
    }

    private static void field(StringBuilder result, String name, String value) {
        result.append(name).append(' ').append(value.length()).append('\n').append(value).append('\n');
    }
}
