package io.github.consentgate.core.config;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record DialogAppearance(
        String titleColor,
        String accentColor,
        String textColor,
        String mutedColor,
        String errorColor,
        String buttonColor
) {
    private static final Pattern HEX = Pattern.compile("#[0-9a-f]{6}");
    private static final Set<String> NAMED = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");

    public DialogAppearance {
        titleColor = validate(titleColor, "title-color");
        accentColor = validate(accentColor, "accent-color");
        textColor = validate(textColor, "text-color");
        mutedColor = validate(mutedColor, "muted-color");
        errorColor = validate(errorColor, "error-color");
        buttonColor = validate(buttonColor, "button-color");
    }

    public static DialogAppearance defaults() {
        return new DialogAppearance("gold", "yellow", "white", "gray", "red", "aqua");
    }

    private static String validate(String value, String key) {
        String normalized = Objects.requireNonNull(value, key).toLowerCase(Locale.ROOT);
        if (!NAMED.contains(normalized) && !HEX.matcher(normalized).matches()) {
            throw new IllegalArgumentException(key + " must be a named Minecraft color or #rrggbb");
        }
        return normalized;
    }
}
