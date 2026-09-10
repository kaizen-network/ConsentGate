package io.github.consentgate.core.document;

public final class DocumentLoadException extends Exception {
    public DocumentLoadException(String message) { super(message); }
    public DocumentLoadException(String message, Throwable cause) { super(message, cause); }
}
