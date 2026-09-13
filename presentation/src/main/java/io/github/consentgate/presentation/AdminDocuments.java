package io.github.consentgate.presentation;

import io.github.consentgate.core.document.DocumentCatalog;
import io.github.consentgate.core.document.LocaleTag;
import java.util.function.Consumer;

/** Inspects the active in-memory catalog without touching storage or edited files. */
public final class AdminDocuments {
    private AdminDocuments() { }

    public static void show(DocumentCatalog catalog, String defaultLocale, String[] args, Consumer<String> reply) {
        if (args.length == 0) {
            reply.accept("Active documents:");
            for (var document : catalog.documents()) reply.accept(document.id() + " (" + document.version() + "): "
                    + (document.required() ? "required" : "informational") + "; locales: " + String.join(", ", document.translations().keySet()));
            return;
        }
        try {
            var document = catalog.documents().stream().filter(item -> item.id().equals(args[0])).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown document. Use /consentgate document to list IDs."));
            String locale = args.length >= 2 ? LocaleTag.normalize(args[1]) : defaultLocale;
            if (args.length >= 2 && !document.translations().containsKey(locale)) {
                throw new IllegalArgumentException("Unknown translation. Available locales: " + String.join(", ", document.translations().keySet()));
            }
            var selected = document.selectTranslation(locale, defaultLocale);
            int page;
            try { page = args.length >= 3 ? Integer.parseInt(args[2]) : 1; }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Page must be a whole number."); }
            var translation = selected.translation();
            if (page < 1 || page > translation.pages().size()) throw new IllegalArgumentException("Page must be between 1 and " + translation.pages().size() + ".");
            var content = translation.pages().get(page - 1);
            reply.accept(document.id() + " (" + document.version() + "), " + selected.locale() + ", page " + page + "/" + translation.pages().size());
            lines(translation.title(), reply);
            lines(translation.summary(), reply);
            lines(content.title(), reply);
            lines(content.body(), reply);
            reply.accept("Read another page: /consentgate document " + document.id() + " " + selected.locale() + " <page>");
        } catch (IllegalArgumentException ex) { reply.accept(ex.getMessage()); }
    }

    private static void lines(String formatted, Consumer<String> reply) {
        for (String line : SafeTextFormatter.plain(formatted).split("\\R", -1)) {
            // Keep chat packets small, including documents containing supplementary characters.
            while (line.codePointCount(0, line.length()) > 240) {
                int end = line.offsetByCodePoints(0, 240);
                reply.accept(line.substring(0, end));
                line = line.substring(end);
            }
            reply.accept(line);
        }
    }
}
