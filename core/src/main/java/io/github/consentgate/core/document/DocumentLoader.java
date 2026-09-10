package io.github.consentgate.core.document;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class DocumentLoader {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_DOCUMENTS = 32;
    private static final int MAX_PAGES = 32;
    private static final int MAX_BODY_LENGTH = 16 * 1024;
    private static final int MAX_TOTAL_BODY_LENGTH = 128 * 1024;

    public DocumentCatalog loadDirectory(Path directory) throws DocumentLoadException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new DocumentLoadException("Document directory does not exist: " + root);
        }
        List<Path> files;
        try (Stream<Path> entries = Files.list(root)) {
            files = entries.filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            throw new DocumentLoadException("Cannot list document directory: " + root, ex);
        }
        if (files.size() > MAX_DOCUMENTS) throw new DocumentLoadException("Document directory contains more than 32 YAML files");
        var documents = new ArrayList<DocumentRevision>();
        for (Path file : files) documents.add(loadFile(root, file));
        try {
            return new DocumentCatalog(documents);
        } catch (IllegalArgumentException ex) {
            throw new DocumentLoadException(ex.getMessage(), ex);
        }
    }

    public DocumentRevision loadFile(Path allowedDirectory, Path file) throws DocumentLoadException {
        Path root = allowedDirectory.toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (!java.util.Objects.equals(normalized.getParent(), root) || Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new DocumentLoadException("Document must be a regular file directly inside " + root);
        }
        try {
            if (Files.size(normalized) > MAX_FILE_BYTES) {
                throw new DocumentLoadException("Document file exceeds 256 KiB: " + normalized.getFileName());
            }
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(20);
            options.setCodePointLimit(MAX_FILE_BYTES);
            Object raw;
            try (InputStream input = Files.newInputStream(normalized)) {
                raw = new Yaml(new SafeConstructor(options)).load(input);
            }
            return parseMap(map(raw, "root"), normalized.getFileName().toString());
        } catch (DocumentLoadException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new DocumentLoadException("Cannot load " + normalized.getFileName() + ": " + ex.getMessage(), ex);
        }
    }

    private DocumentRevision parseMap(Map<?, ?> root, String source) throws DocumentLoadException {
        rejectUnknown(root, source, "id", "version", "required", "order", "translations");
        String id = text(root, "id", 1, 64);
        if (!ID.matcher(id).matches()) throw problem(source, "id must match " + ID.pattern());
        String version = text(root, "version", 1, 64);
        boolean required = bool(root, "required");
        int order = integer(root, "order", -1_000_000, 1_000_000);
        Map<?, ?> translationsRaw = map(required(root, "translations"), "translations");
        if (translationsRaw.isEmpty() || translationsRaw.size() > 32) {
            throw problem(source, "translations must contain 1 to 32 locales");
        }
        var translations = new LinkedHashMap<String, DocumentTranslation>();
        for (var entry : translationsRaw.entrySet()) {
            String locale;
            try { locale = LocaleTag.normalize(String.valueOf(entry.getKey())); }
            catch (IllegalArgumentException ex) { throw problem(source, ex.getMessage()); }
            if (translations.put(locale, parseTranslation(id, version, locale, map(entry.getValue(), locale), source)) != null) {
                throw problem(source, "duplicate locale: " + locale);
            }
        }
        return new DocumentRevision(id, version, required, order, translations);
    }

    private DocumentTranslation parseTranslation(String id, String version, String locale, Map<?, ?> value, String source)
            throws DocumentLoadException {
        rejectUnknown(value, source + "/" + locale, "title", "summary", "checkbox", "read-button", "pages");
        String title = text(value, "title", 1, 128);
        String summary = text(value, "summary", 0, 2_000);
        String checkbox = text(value, "checkbox", 1, 256);
        String readButton = text(value, "read-button", 1, 128);
        List<?> pagesRaw = list(required(value, "pages"), "pages");
        if (pagesRaw.isEmpty() || pagesRaw.size() > MAX_PAGES) throw problem(source, "pages must contain 1 to 32 pages");
        var pages = new ArrayList<DocumentPage>();
        int totalBody = 0;
        for (int index = 0; index < pagesRaw.size(); index++) {
            Map<?, ?> page = map(pagesRaw.get(index), "page " + (index + 1));
            rejectUnknown(page, source + "/page " + (index + 1), "title", "body");
            String pageTitle = text(page, "title", 1, 128);
            String body = text(page, "body", 1, MAX_BODY_LENGTH);
            totalBody += body.length();
            if (totalBody > MAX_TOTAL_BODY_LENGTH) throw problem(source, "combined page text exceeds 128 KiB");
            pages.add(new DocumentPage(pageTitle, body));
        }
        String hash = hash(id, version, locale, title, summary, checkbox, readButton, pages);
        return new DocumentTranslation(title, summary, checkbox, readButton, pages, hash);
    }

    private static String hash(String id, String version, String locale, String title, String summary,
                               String checkbox, String readButton, List<DocumentPage> pages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, id); update(digest, version); update(digest, locale);
            update(digest, title); update(digest, summary); update(digest, checkbox); update(digest, readButton);
            for (var page : pages) { update(digest, page.title()); update(digest, page.body()); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void rejectUnknown(Map<?, ?> map, String location, String... allowed) throws DocumentLoadException {
        var keys = java.util.Set.of(allowed);
        for (Object key : map.keySet()) {
            if (!(key instanceof String text) || !keys.contains(text)) {
                throw new DocumentLoadException("Unknown key at " + location + ": " + key);
            }
        }
    }

    private static Object required(Map<?, ?> map, String key) throws DocumentLoadException {
        if (!map.containsKey(key)) throw new DocumentLoadException("Missing required key: " + key);
        return map.get(key);
    }

    private static String text(Map<?, ?> map, String key, int min, int max) throws DocumentLoadException {
        Object value = required(map, key);
        if (!(value instanceof String text) || text.length() < min || text.length() > max) {
            throw new DocumentLoadException(key + " must be text with length " + min + " to " + max);
        }
        return text;
    }

    private static boolean bool(Map<?, ?> map, String key) throws DocumentLoadException {
        Object value = required(map, key);
        if (!(value instanceof Boolean result)) throw new DocumentLoadException(key + " must be true or false");
        return result;
    }

    private static int integer(Map<?, ?> map, String key, int min, int max) throws DocumentLoadException {
        Object value = required(map, key);
        if (!(value instanceof Integer result) || result < min || result > max) {
            throw new DocumentLoadException(key + " must be an integer from " + min + " to " + max);
        }
        return result;
    }

    private static Map<?, ?> map(Object value, String location) throws DocumentLoadException {
        if (!(value instanceof Map<?, ?> result)) throw new DocumentLoadException(location + " must be a map");
        return result;
    }

    private static List<?> list(Object value, String location) throws DocumentLoadException {
        if (!(value instanceof List<?> result)) throw new DocumentLoadException(location + " must be a list");
        return result;
    }

    private static DocumentLoadException problem(String source, String message) {
        return new DocumentLoadException(source + ": " + message);
    }
}
