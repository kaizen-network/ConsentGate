package io.github.consentgate.velocity;

import io.github.consentgate.core.document.LocaleTag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

final class InterfaceMessages {
    private final Map<String, Properties> translations = new HashMap<>();

    InterfaceMessages(Path directory) throws IOException {
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

    String text(String locale, String fallback, String key) {
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
