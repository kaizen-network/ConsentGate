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
        DialogAppearance appearance,
        Path documentsDirectory,
        Path sqliteFile
) {
    private static final Pattern SCOPE = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");

    public ConsentGateConfig {
        scope = Objects.requireNonNull(scope, "scope");
        defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
        appearance = Objects.requireNonNull(appearance, "appearance");
        documentsDirectory = Objects.requireNonNull(documentsDirectory, "documentsDirectory").toAbsolutePath().normalize();
        sqliteFile = Objects.requireNonNull(sqliteFile, "sqliteFile").toAbsolutePath().normalize();
        if (!SCOPE.matcher(scope).matches()) throw new IllegalArgumentException("Invalid scope: " + scope);
        if (timeoutSeconds < 30 || timeoutSeconds > 1_800) throw new IllegalArgumentException("Invalid timeout");
        if (maxPending < 1 || maxPending > 10_000) throw new IllegalArgumentException("Invalid pending limit");
        defaultLocale = LocaleTag.normalize(defaultLocale);
    }

    public ConsentGateConfig(boolean enabled, String scope, int timeoutSeconds, int maxPending, String defaultLocale,
                             boolean useClientLocale, Path documentsDirectory, Path sqliteFile) {
        this(enabled, scope, timeoutSeconds, maxPending, defaultLocale, useClientLocale, DialogAppearance.defaults(),
                documentsDirectory, sqliteFile);
    }
}
