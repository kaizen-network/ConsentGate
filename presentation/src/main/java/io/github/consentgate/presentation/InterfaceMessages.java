package io.github.consentgate.presentation;

import io.github.consentgate.core.document.LocaleTag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public final class InterfaceMessages {
    private final Map<String, Properties> translations = new HashMap<>();

    public InterfaceMessages(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) throw new IOException("Message directory cannot be a symbolic link");
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".properties")).toList()) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > 65536) {
                    throw new IOException("Invalid message file: " + file.getFileName());
                }
                String name = file.getFileName().toString();
                String locale = LocaleTag.normalize(name.substring(0, name.length() - 11));
                var values = new Properties();
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
                values.values().forEach(value -> SafeTextFormatter.validate(value.toString()));
                if (translations.putIfAbsent(locale, values) != null) throw new IOException("Duplicate message locale: " + locale);
            }
        }
    }

    public void validateFor(java.util.Collection<String> locales, String fallback) {
        for (String locale : locales) {
            for (String key : java.util.List.of("title", "prompt", "required", "continue", "leave", "back",
                    "previous", "next", "version", "page", "denied")) {
                if (SafeTextFormatter.plain(text(locale, fallback, key)).isBlank()) {
                    throw new IllegalArgumentException("Empty interface message: " + key);
                }
            }
        }
    }

    public String text(String locale, String fallback, String key) {
        String value = lookup(locale, key);
        if (value == null) value = lookup(fallback, key);
        if (value == null) value = lookup("en-US", key);
        if (value == null) throw new IllegalArgumentException("Missing interface message: " + key);
        return value;
    }

    private String lookup(String locale, String key) {
        if (locale == null) return null;
        var values = translations.get(locale);
        String value = values == null ? null : values.getProperty(key);
        if (value != null) return value;
        values = translations.get(locale.split("-", 2)[0]);
        return values == null ? null : values.getProperty(key);
    }
}
