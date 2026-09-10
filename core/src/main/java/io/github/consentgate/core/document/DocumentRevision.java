package io.github.consentgate.core.document;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record DocumentRevision(
        String id,
        String version,
        boolean required,
        int order,
        Map<String, DocumentTranslation> translations
) {
    public DocumentRevision {
        id = Objects.requireNonNull(id, "id");
        version = Objects.requireNonNull(version, "version");
        var normalized = new LinkedHashMap<String, DocumentTranslation>();
        translations.forEach((locale, translation) -> {
            String key = LocaleTag.normalize(locale);
            if (normalized.putIfAbsent(key, Objects.requireNonNull(translation, "translation")) != null) {
                throw new IllegalArgumentException("Duplicate normalized locale: " + key);
            }
        });
        if (normalized.isEmpty()) throw new IllegalArgumentException("At least one translation is required");
        translations = java.util.Collections.unmodifiableMap(normalized);
    }

    public DocumentTranslation translation(String locale, String fallbackLocale) {
        return selectTranslation(locale, fallbackLocale).translation();
    }

    public SelectedTranslation selectTranslation(String locale, String fallbackLocale) {
        String normalizedLocale;
        try { normalizedLocale = LocaleTag.normalize(locale); }
        catch (IllegalArgumentException ex) { normalizedLocale = LocaleTag.normalize(fallbackLocale); }
        var direct = translations.get(normalizedLocale);
        if (direct != null) return new SelectedTranslation(normalizedLocale, direct);
        String languageKey = normalizedLocale.split("-", 2)[0];
        var language = translations.get(languageKey);
        if (language != null) return new SelectedTranslation(languageKey, language);
        String normalizedFallback = LocaleTag.normalize(fallbackLocale);
        var fallback = translations.get(normalizedFallback);
        if (fallback != null) return new SelectedTranslation(normalizedFallback, fallback);
        var first = translations.entrySet().iterator().next();
        return new SelectedTranslation(first.getKey(), first.getValue());
    }

    public record SelectedTranslation(String locale, DocumentTranslation translation) { }
}
