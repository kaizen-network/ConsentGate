package io.github.consentgate.core.document;

import java.util.Objects;

public record DocumentPage(String title, String body) {
    public DocumentPage {
        title = Objects.requireNonNull(title, "title");
        body = Objects.requireNonNull(body, "body");
    }
}
