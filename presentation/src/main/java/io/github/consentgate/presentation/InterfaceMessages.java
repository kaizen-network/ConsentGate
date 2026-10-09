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
    private static final java.util.List<String> REQUIRED = java.util.List.of("title", "prompt", "required", "continue", "leave",
            "back", "previous", "next", "version", "page", "denied");
    // Message files are only copied when missing, so keys added after a server's first start resolve from the jar.
    private static final Map<String, Properties> BUNDLED = bundled("en-US", "id-ID");
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
            for (String key : BUNDLED.get("en-US").stringPropertyNames()) {
                String value = REQUIRED.contains(key) ? find(translations, locale, fallback, key) : text(locale, fallback, key);
                if (value == null) throw new IllegalArgumentException("Missing interface message: " + key);
                if (SafeTextFormatter.plain(value).isBlank()) {
                    throw new IllegalArgumentException("Empty interface message: " + key);
                }
            }
        }
    }

    public String text(String locale, String fallback, String key) {
        String value = find(translations, locale, fallback, key);
        if (value == null) value = find(BUNDLED, locale, fallback, key);
        if (value == null) throw new IllegalArgumentException("Missing interface message: " + key);
        return value;
    }

    /** Bundled English text, for kicks issued before message files have loaded. */
    public static String defaultText(String key) {
        String value = BUNDLED.get("en-US").getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing interface message: " + key);
        return value;
    }

    private static String find(Map<String, Properties> source, String locale, String fallback, String key) {
        String value = lookup(source, locale, key);
        if (value == null) value = lookup(source, fallback, key);
        if (value == null) value = lookup(source, "en-US", key);
        return value;
    }

    private static String lookup(Map<String, Properties> source, String locale, String key) {
        if (locale == null) return null;
        var values = source.get(locale);
        String value = values == null ? null : values.getProperty(key);
        if (value != null) return value;
        values = source.get(locale.split("-", 2)[0]);
        return values == null ? null : values.getProperty(key);
    }

    private static Map<String, Properties> bundled(String... locales) {
        var result = new HashMap<String, Properties>();
        for (String locale : locales) {
            try (var input = InterfaceMessages.class.getResourceAsStream("/messages/" + locale + ".properties")) {
                if (input == null) throw new IllegalStateException("Missing bundled messages: " + locale);
                var values = new Properties();
                values.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
                result.put(locale, values);
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }
        return result;
    }
}
