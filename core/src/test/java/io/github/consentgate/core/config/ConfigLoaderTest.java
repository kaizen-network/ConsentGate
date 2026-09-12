package io.github.consentgate.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @TempDir Path directory;

    @Test void loadsValidatedRelativePaths() throws Exception {
        Path configFile = write(config("documents", "data/consent.db"));
        ConsentGateConfig config = new ConfigLoader().load(directory, configFile);
        assertFalse(config.enabled());
        assertEquals("main", config.scope());
        assertEquals(300, config.timeoutSeconds());
        assertEquals(128, config.maxPending());
        assertFalse(config.nativeBedrockForms());
        assertEquals("dark_gray", config.bedrockButtonColor());
        assertEquals(LanguageSelectorConfig.disabled(), config.languageSelector());
        assertEquals(DialogAppearance.defaults(), config.appearance());
        assertEquals(directory.resolve("documents").toAbsolutePath(), config.documentsDirectory());
        assertEquals(directory.resolve("data/consent.db").toAbsolutePath(), config.sqliteFile());
    }

    @Test void rejectsPathsOutsidePluginDirectory() throws Exception {
        Path configFile = write(config("../documents", "data/consent.db"));
        assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(directory, configFile));
    }

    @Test void nativeBedrockFormsAreOptionalAndStrictlyTyped() throws Exception {
        Path enabled = write(config("documents", "data/consent.db") + "bedrock:\n  native-forms: true\n");
        assertTrue(new ConfigLoader().load(directory, enabled).nativeBedrockForms());
        Path invalid = write(config("documents", "data/consent.db") + "bedrock:\n  native-forms: native\n");
        assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(directory, invalid));
    }

    @Test void rejectsAbsolutePaths() throws Exception {
        String absolute = directory.resolve("elsewhere.db").toString().replace('\\', '/');
        Path configFile = write(config("documents", absolute));
        assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(directory, configFile));
    }

    @Test void validatesBedrockButtonColor() throws Exception {
        Path valid = write(config("documents", "data/consent.db") + "bedrock:\n  native-forms: true\n  button-color: black\n");
        assertEquals("black", new ConfigLoader().load(directory, valid).bedrockButtonColor());
        Path invalid = write(config("documents", "data/consent.db") + "bedrock:\n  native-forms: true\n  button-color: orange\n");
        assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(directory, invalid));
    }

    @Test void rejectsUnknownStorageAndUnknownKeys() throws Exception {
        Path unsupported = write(config("documents", "data/consent.db").replace("type: sqlite", "type: mysql"));
        assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(directory, unsupported));

        Path unknown = write(config("documents", "data/consent.db") + "extra: true\n");
        assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(directory, unknown));
    }

    @Test void rejectsDuplicateKeys() throws Exception {
        Path configFile = write(config("documents", "data/consent.db") + "enabled: true\n");
        assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(directory, configFile));
    }

    @Test void loadsAppearanceAndRejectsInvalidColors() throws Exception {
        String appearance = """
                appearance:
                  title-color: "#12abef"
                  accent-color: yellow
                  text-color: white
                  muted-color: gray
                  error-color: red
                  button-color: aqua
                """;
        Path configured = write(config("documents", "data/consent.db").replace("documents:\n", appearance + "documents:\n"));
        assertEquals("#12abef", new ConfigLoader().load(directory, configured).appearance().titleColor());

        Path invalid = write(config("documents", "data/consent.db").replace("documents:\n",
                appearance.replace("title-color: \"#12abef\"", "title-color: orange") + "documents:\n"));
        assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(directory, invalid));
    }

    @Test void rejectsSymlinkedConfiguredDirectory() throws Exception {
        Path pluginDirectory = Files.createDirectory(directory.resolve("plugin"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path link = pluginDirectory.resolve("documents");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception ex) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }
        Path configFile = pluginDirectory.resolve("config.yml");
        Files.writeString(configFile, config("documents", "data/consent.db"));
        assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(pluginDirectory, configFile));
    }

    @Test void loadsLanguageSelectorAndRejectsInvalidColumns() throws Exception {
        String selector = """
                  selector:
                    enabled: true
                    title: "Choose language"
                    prompt: "Select a language."
                    columns: 2
                    options:
                      en-US: "English"
                      id-ID: "Bahasa Indonesia"
                """;
        Path configured = write(config("documents", "data/consent.db")
                .replace("  use-client-locale: true\n", "  use-client-locale: true\n" + selector));
        LanguageSelectorConfig loaded = new ConfigLoader().load(directory, configured).languageSelector();
        assertTrue(loaded.enabled());
        assertEquals(java.util.List.of("en-US", "id-ID"), java.util.List.copyOf(loaded.options().keySet()));

        Path invalid = write(config("documents", "data/consent.db")
                .replace("  use-client-locale: true\n", "  use-client-locale: true\n" + selector.replace("columns: 2", "columns: 3")));
        assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(directory, invalid));
    }

    @Test void remoteSettingsDefaultToVerifiedTlsAndFreshCache() throws Exception {
        var loaded = new ConfigLoader().load(directory, write(remoteConfig()));
        assertEquals("mariadb", loaded.storage().type());
        assertEquals("verify-full", loaded.storage().remote().sslMode());
        assertEquals(3000, loaded.storage().remote().connectTimeoutMillis());
        assertEquals(5000, loaded.storage().remote().socketTimeoutMillis());
        assertTrue(loaded.storage().cache().enabled());
        assertEquals(60, loaded.storage().cache().freshnessSeconds());
        assertFalse(loaded.toString().contains("test-secret-value"));
        assertEquals("mysql", new ConfigLoader().load(directory, write(remoteConfig().replace("type: mariadb", "type: mysql"))).storage().type());
    }

    @Test void remoteConnectionRejectsUrlInjectionUnsafeTlsAndUnboundedWaits() throws Exception {
        for (String invalid : java.util.List.of(remoteConfig().replace("localhost", "localhost?allowLocalInfile=true"),
                remoteConfig() + "    ssl-mode: trust\n", remoteConfig() + "    socket-timeout-millis: 0\n",
                remoteConfig().replace("database: consentgate", "database: consentgate/other"),
                remoteConfig() + "    server-certificate: ../outside.pem\n")) {
            assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(directory, write(invalid)));
        }
    }

    @Test void cachePathsAndBoundsAreStrict() throws Exception {
        String cache = "  cache:\n    enabled: true\n    file: data/cache.db\n    freshness-seconds: 0\n    max-entries: 5\n";
        assertEquals(0, new ConfigLoader().load(directory, write(remoteConfig() + cache)).storage().cache().freshnessSeconds());
        for (String invalid : java.util.List.of(cache.replace("data/cache.db", "data/consent.db"), cache.replace("data/cache.db", "../cache.db"),
                cache.replace("max-entries: 5", "max-entries: 0"), cache.replace("freshness-seconds: 0", "freshness-seconds: 301"))) {
            assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(directory, write(remoteConfig() + invalid)));
        }
    }

    private static String remoteConfig() {
        return config("documents", "data/consent.db").replace("type: sqlite", "type: mariadb")
                + "  remote:\n    host: localhost\n    port: 3306\n    database: consentgate\n    username: consentgate\n    password: test-secret-value\n";
    }

    private Path write(String value) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, value);
        return file;
    }

    private static String config(String documents, String database) {
        return """
                config-version: 1
                enabled: false
                scope: main
                gate:
                  timeout-seconds: 300
                  max-pending: 128
                language:
                  default: en-US
                  use-client-locale: true
                documents:
                  directory: "%s"
                storage:
                  type: sqlite
                  sqlite:
                    file: "%s"
                """.formatted(documents, database);
    }
}
