package io.github.consentgate.paper;

import io.github.consentgate.core.runtime.RuntimeLoader;
import io.github.consentgate.presentation.InterfaceMessages;
import io.github.consentgate.presentation.SafeTextFormatter;

/** The same presentation checks apply at startup, validation, and reload. */
final class PaperConfiguration {
    private PaperConfiguration() { }

    static void validate(RuntimeLoader.Prepared prepared, InterfaceMessages messages) {
        if (prepared.config().nativeBedrockForms()) throw new IllegalArgumentException("Native Bedrock forms are not implemented on Paper yet; set bedrock.native-forms to false");
        SafeTextFormatter.validateCatalog(prepared.catalog());
        var selector = prepared.config().languageSelector();
        SafeTextFormatter.validate(selector.title());
        SafeTextFormatter.validate(selector.prompt());
        selector.options().values().forEach(SafeTextFormatter::validate);
        var locales = new java.util.HashSet<>(selector.options().keySet());
        locales.add(prepared.config().defaultLocale());
        prepared.catalog().documents().forEach(document -> locales.addAll(document.translations().keySet()));
        messages.validateFor(locales, prepared.config().defaultLocale());
        new SafeTextFormatter(prepared.config().appearance());
    }
}
