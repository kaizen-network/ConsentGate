package io.github.consentgate.velocity;

import io.github.consentgate.core.config.DialogAppearance;
import io.github.consentgate.core.document.DocumentCatalog;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SafeTextFormatter {
    private static final Pattern TAG = Pattern.compile("(?<!\\\\)<(/?)([^<>]+)>");
    private static final Pattern HEX_TAG = Pattern.compile("#[0-9a-f]{6}");
    private static final Set<String> SAFE_TAGS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "italic", "underlined", "strikethrough", "obfuscated");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder()
            .tags(TagResolver.builder()
                    .resolver(StandardTags.color())
                    .resolver(StandardTags.decorations())
                    .build())
            .strict(true)
            .build();

    private final DialogAppearance appearance;

    SafeTextFormatter(DialogAppearance appearance) {
        this.appearance = appearance;
    }

    Component title(String value) { return format(value, appearance.titleColor()); }
    Component accent(String value) { return format(value, appearance.accentColor()); }
    Component text(String value) { return format(value, appearance.textColor()); }
    Component muted(String value) { return format(value, appearance.mutedColor()); }
    Component mutedPlain(String value) { return Component.text(value, color(appearance.mutedColor())); }
    Component error(String value) { return format(value, appearance.errorColor()); }
    Component button(String value) { return format(value, appearance.buttonColor()); }

    Component format(String value, String defaultColor) {
        validate(value);
        return MINI_MESSAGE.deserialize(value).colorIfAbsent(color(defaultColor));
    }

    static void validateCatalog(DocumentCatalog catalog) {
        for (var document : catalog.documents()) {
            for (var entry : document.translations().entrySet()) {
                var translation = entry.getValue();
                validateField(document.id(), entry.getKey(), "title", translation.title());
                validateField(document.id(), entry.getKey(), "summary", translation.summary());
                validateField(document.id(), entry.getKey(), "checkbox", translation.checkbox());
                validateField(document.id(), entry.getKey(), "read-button", translation.readButton());
                for (int index = 0; index < translation.pages().size(); index++) {
                    var page = translation.pages().get(index);
                    validateField(document.id(), entry.getKey(), "page " + (index + 1) + " title", page.title());
                    validateField(document.id(), entry.getKey(), "page " + (index + 1) + " body", page.body());
                }
            }
        }
    }

    static void validate(String value) {
        Matcher matcher = TAG.matcher(value);
        while (matcher.find()) {
            String definition = matcher.group(2).toLowerCase(Locale.ROOT);
            if (!SAFE_TAGS.contains(definition) && !HEX_TAG.matcher(definition).matches()) {
                throw new IllegalArgumentException("Unsupported MiniMessage tag: <" + matcher.group(2) + ">");
            }
        }
        try {
            MINI_MESSAGE.deserialize(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid MiniMessage formatting: " + ex.getMessage(), ex);
        }
    }

    private static void validateField(String document, String locale, String field, String value) {
        try {
            validate(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(document + "/" + locale + " " + field + ": " + ex.getMessage(), ex);
        }
    }

    private static TextColor color(String value) {
        if (value.startsWith("#")) return TextColor.color(Integer.parseInt(value.substring(1), 16));
        TextColor color = NamedTextColor.NAMES.value(value);
        if (color == null) throw new IllegalArgumentException("Unknown text color: " + value);
        return color;
    }
}
