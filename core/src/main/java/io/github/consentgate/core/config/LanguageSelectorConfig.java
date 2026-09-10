package io.github.consentgate.core.config;

import io.github.consentgate.core.document.LocaleTag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record LanguageSelectorConfig(boolean enabled, String title, String prompt, int columns,
                                     Map<String, String> options) {
    public LanguageSelectorConfig {
        title = Objects.requireNonNull(title, "title");
        prompt = Objects.requireNonNull(prompt, "prompt");
        if (columns < 1 || columns > 2) throw new IllegalArgumentException("Language selector columns must be 1 or 2");
        var normalized = new LinkedHashMap<String, String>();
        Objects.requireNonNull(options, "options").forEach((locale, label) -> {
            String key = LocaleTag.normalize(locale);
            if (label == null || label.isBlank() || label.length() > 64) {
                throw new IllegalArgumentException("Invalid language selector label for " + key);
            }
            if (normalized.putIfAbsent(key, label) != null) {
                throw new IllegalArgumentException("Duplicate language selector locale: " + key);
            }
        });
        if (enabled && (normalized.size() < 2 || normalized.size() > 8)) {
            throw new IllegalArgumentException("An enabled language selector needs 2 to 8 options");
        }
        options = Collections.unmodifiableMap(normalized);
    }

    public static LanguageSelectorConfig disabled() {
        return new LanguageSelectorConfig(false, "Choose language", "Choose the language used for these documents.",
                2, Map.of());
    }
}
