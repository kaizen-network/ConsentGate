package io.github.consentgate.core.config;

import io.github.consentgate.core.document.LocaleTag;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

public record ConsentGateConfig(
        boolean enabled,
        String scope,
        int timeoutSeconds,
        int maxPending,
        String defaultLocale,
        boolean useClientLocale,
        LanguageSelectorConfig languageSelector,
        DialogAppearance appearance,
        boolean nativeBedrockForms,
        String bedrockButtonColor,
        Path documentsDirectory,
        StorageConfig storage
) {
    private static final Pattern SCOPE = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");

    public ConsentGateConfig {
        scope = Objects.requireNonNull(scope, "scope");
        defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
        languageSelector = Objects.requireNonNull(languageSelector, "languageSelector");
        appearance = Objects.requireNonNull(appearance, "appearance");
        bedrockButtonColor = DialogAppearance.validate(bedrockButtonColor, "bedrock.button-color");
        documentsDirectory = Objects.requireNonNull(documentsDirectory, "documentsDirectory").toAbsolutePath().normalize();
        storage = Objects.requireNonNull(storage, "storage");
        if (!SCOPE.matcher(scope).matches()) throw new IllegalArgumentException("Invalid scope: " + scope);
        if (timeoutSeconds < 30 || timeoutSeconds > 1_800) throw new IllegalArgumentException("Invalid timeout");
        if (maxPending < 1 || maxPending > 10_000) throw new IllegalArgumentException("Invalid pending limit");
        defaultLocale = LocaleTag.normalize(defaultLocale);
    }

    public ConsentGateConfig(boolean enabled, String scope, int timeoutSeconds, int maxPending, String defaultLocale,
                             boolean useClientLocale, LanguageSelectorConfig languageSelector, DialogAppearance appearance,
                             boolean nativeBedrockForms, String bedrockButtonColor, Path documentsDirectory, Path sqliteFile) {
        this(enabled, scope, timeoutSeconds, maxPending, defaultLocale, useClientLocale, languageSelector, appearance,
                nativeBedrockForms, bedrockButtonColor, documentsDirectory, StorageConfig.sqlite(sqliteFile));
    }

    public Path sqliteFile() { return storage.sqliteFile(); }

    public ConsentGateConfig(boolean enabled, String scope, int timeoutSeconds, int maxPending, String defaultLocale,
                             boolean useClientLocale, Path documentsDirectory, Path sqliteFile) {
        this(enabled, scope, timeoutSeconds, maxPending, defaultLocale, useClientLocale, LanguageSelectorConfig.disabled(),
                DialogAppearance.defaults(), false, "dark_gray", documentsDirectory, sqliteFile);
    }
}
