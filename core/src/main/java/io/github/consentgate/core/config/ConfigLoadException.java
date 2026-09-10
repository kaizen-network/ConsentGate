package io.github.consentgate.core.config;

public final class ConfigLoadException extends Exception {
    public ConfigLoadException(String message) { super(message); }
    public ConfigLoadException(String message, Throwable cause) { super(message, cause); }
}
