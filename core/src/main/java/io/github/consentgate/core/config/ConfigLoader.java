package io.github.consentgate.core.config;

import io.github.consentgate.core.document.LocaleTag;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ConfigLoader {
    private static final Pattern SCOPE = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    private static final int MAX_CONFIG_BYTES = 64 * 1024;

    public ConsentGateConfig load(Path dataDirectory, Path configFile) throws ConfigLoadException {
        Path root = dataDirectory.toAbsolutePath().normalize();
        Path file = configFile.toAbsolutePath().normalize();
        if (!file.startsWith(root) || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigLoadException("Config must be a regular file inside the plugin directory");
        }
        try {
            if (Files.size(file) > MAX_CONFIG_BYTES) throw new ConfigLoadException("Config exceeds 64 KiB");
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(10);
            options.setCodePointLimit(MAX_CONFIG_BYTES);
            Object raw;
            try (InputStream input = Files.newInputStream(file)) {
                raw = new Yaml(new SafeConstructor(options)).load(input);
            }
            return parse(root, map(raw, "root"));
        } catch (ConfigLoadException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new ConfigLoadException("Cannot load config: " + ex.getMessage(), ex);
        }
    }

    private ConsentGateConfig parse(Path root, Map<?, ?> config) throws ConfigLoadException {
        rejectUnknown(config, "root", "config-version", "enabled", "scope", "gate", "language", "appearance", "bedrock", "documents", "storage");
        integer(config, "config-version", 1, 1);
        boolean enabled = bool(config, "enabled");
        String scope = text(config, "scope", 1, 64);
        if (!SCOPE.matcher(scope).matches()) throw new ConfigLoadException("Invalid scope: " + scope);

        Map<?, ?> gate = map(required(config, "gate"), "gate");
        rejectUnknown(gate, "gate", "timeout-seconds", "max-pending");
        int timeoutSeconds = integer(gate, "timeout-seconds", 30, 1_800);
        int maxPending = integer(gate, "max-pending", 1, 10_000);

        Map<?, ?> language = map(required(config, "language"), "language");
        rejectUnknown(language, "language", "default", "use-client-locale", "selector");
        String defaultLocale;
        try { defaultLocale = LocaleTag.normalize(text(language, "default", 2, 64)); }
        catch (IllegalArgumentException ex) { throw new ConfigLoadException(ex.getMessage(), ex); }
        boolean useClientLocale = bool(language, "use-client-locale");
        LanguageSelectorConfig selector = LanguageSelectorConfig.disabled();
        if (language.containsKey("selector")) {
            Map<?, ?> selectorMap = map(language.get("selector"), "language.selector");
            rejectUnknown(selectorMap, "language.selector", "enabled", "title", "prompt", "columns", "options");
            boolean selectorEnabled = bool(selectorMap, "enabled");
            String title = text(selectorMap, "title", 1, 128);
            String prompt = text(selectorMap, "prompt", 1, 512);
            int columns = integer(selectorMap, "columns", 1, 2);
            Map<?, ?> rawOptions = map(required(selectorMap, "options"), "language.selector.options");
            var options = new java.util.LinkedHashMap<String, String>();
            for (var entry : rawOptions.entrySet()) {
                if (!(entry.getKey() instanceof String locale)) throw new ConfigLoadException("Invalid language selector locale");
                if (!(entry.getValue() instanceof String label)) {
                    throw new ConfigLoadException("Language selector labels must be text");
                }
                options.put(locale, label);
            }
            try { selector = new LanguageSelectorConfig(selectorEnabled, title, prompt, columns, options); }
            catch (IllegalArgumentException ex) { throw new ConfigLoadException(ex.getMessage(), ex); }
        }

        DialogAppearance appearance = DialogAppearance.defaults();
        if (config.containsKey("appearance")) {
            Map<?, ?> appearanceMap = map(config.get("appearance"), "appearance");
            rejectUnknown(appearanceMap, "appearance", "title-color", "accent-color", "text-color", "muted-color",
                    "error-color", "button-color");
            try {
                appearance = new DialogAppearance(
                        text(appearanceMap, "title-color", 1, 32),
                        text(appearanceMap, "accent-color", 1, 32),
                        text(appearanceMap, "text-color", 1, 32),
                        text(appearanceMap, "muted-color", 1, 32),
                        text(appearanceMap, "error-color", 1, 32),
                        text(appearanceMap, "button-color", 1, 32));
            } catch (IllegalArgumentException ex) {
                throw new ConfigLoadException(ex.getMessage(), ex);
            }
        }

        boolean nativeBedrockForms = false;
        String bedrockButtonColor = "dark_gray";
        if (config.containsKey("bedrock")) {
            Map<?, ?> bedrock = map(config.get("bedrock"), "bedrock");
            rejectUnknown(bedrock, "bedrock", "native-forms", "button-color");
            nativeBedrockForms = bool(bedrock, "native-forms");
            if (bedrock.containsKey("button-color")) bedrockButtonColor = text(bedrock, "button-color", 1, 32);
        }

        Map<?, ?> documents = map(required(config, "documents"), "documents");
        rejectUnknown(documents, "documents", "directory");
        Path documentsDirectory = resolveInside(root, text(documents, "directory", 1, 256), "documents.directory");

        Map<?, ?> storage = map(required(config, "storage"), "storage");
        rejectUnknown(storage, "storage", "type", "sqlite");
        String type = text(storage, "type", 1, 32);
        if (!type.equals("sqlite")) throw new ConfigLoadException("Unsupported storage type in this build: " + type);
        Map<?, ?> sqlite = map(required(storage, "sqlite"), "storage.sqlite");
        rejectUnknown(sqlite, "storage.sqlite", "file");
        Path sqliteFile = resolveInside(root, text(sqlite, "file", 1, 256), "storage.sqlite.file");
        if (sqliteFile.equals(root)) throw new ConfigLoadException("storage.sqlite.file must name a file");

        return new ConsentGateConfig(enabled, scope, timeoutSeconds, maxPending, defaultLocale,
                useClientLocale, selector, appearance, nativeBedrockForms, bedrockButtonColor, documentsDirectory, sqliteFile);
    }

    private static Path resolveInside(Path root, String value, String key) throws ConfigLoadException {
        Path configured;
        try {
            configured = Path.of(value);
        } catch (RuntimeException ex) {
            throw new ConfigLoadException(key + " is not a valid path", ex);
        }
        if (configured.isAbsolute()) throw new ConfigLoadException(key + " must be relative to the plugin directory");
        Path resolved = root.resolve(configured).normalize();
        if (!resolved.startsWith(root)) throw new ConfigLoadException(key + " escapes the plugin directory");
        for (Path current = resolved; current != null && !current.equals(root); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new ConfigLoadException(key + " contains a symbolic link");
        }
        return resolved;
    }

    private static void rejectUnknown(Map<?, ?> map, String location, String... allowed) throws ConfigLoadException {
        Set<String> keys = Set.of(allowed);
        for (Object key : map.keySet()) {
            if (!(key instanceof String text) || !keys.contains(text)) {
                throw new ConfigLoadException("Unknown key at " + location + ": " + key);
            }
        }
    }

    private static Object required(Map<?, ?> map, String key) throws ConfigLoadException {
        if (!map.containsKey(key)) throw new ConfigLoadException("Missing required key: " + key);
        return map.get(key);
    }

    private static String text(Map<?, ?> map, String key, int min, int max) throws ConfigLoadException {
        Object value = required(map, key);
        if (!(value instanceof String text) || text.length() < min || text.length() > max) {
            throw new ConfigLoadException(key + " must be text with length " + min + " to " + max);
        }
        return text;
    }

    private static boolean bool(Map<?, ?> map, String key) throws ConfigLoadException {
        Object value = required(map, key);
        if (!(value instanceof Boolean result)) throw new ConfigLoadException(key + " must be true or false");
        return result;
    }

    private static int integer(Map<?, ?> map, String key, int min, int max) throws ConfigLoadException {
        Object value = required(map, key);
        if (!(value instanceof Integer result) || result < min || result > max) {
            throw new ConfigLoadException(key + " must be an integer from " + min + " to " + max);
        }
        return result;
    }

    private static Map<?, ?> map(Object value, String location) throws ConfigLoadException {
        if (!(value instanceof Map<?, ?> result)) throw new ConfigLoadException(location + " must be a map");
        return result;
    }
}
