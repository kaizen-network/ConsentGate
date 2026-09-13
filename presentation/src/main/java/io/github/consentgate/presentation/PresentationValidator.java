package io.github.consentgate.presentation;

import io.github.consentgate.core.config.ConsentGateConfig;
import io.github.consentgate.core.document.DocumentCatalog;

/** The same presentation checks apply at startup, validation, and reload. */
public final class PresentationValidator {
    private PresentationValidator() { }

    public static void validate(ConsentGateConfig config, DocumentCatalog catalog, InterfaceMessages messages) {
        SafeTextFormatter.validateCatalog(catalog);
        var selector = config.languageSelector();
        SafeTextFormatter.validate(selector.title());
        SafeTextFormatter.validate(selector.prompt());
        selector.options().values().forEach(SafeTextFormatter::validate);
        var locales = new java.util.HashSet<>(selector.options().keySet());
        locales.add(config.defaultLocale());
        catalog.documents().forEach(document -> locales.addAll(document.translations().keySet()));
        messages.validateFor(locales, config.defaultLocale());
    }
}
