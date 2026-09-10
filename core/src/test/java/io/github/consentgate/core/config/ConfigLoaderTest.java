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
        assertEquals(DialogAppearance.defaults(), config.appearance());
        assertEquals(directory.resolve("documents").toAbsolutePath(), config.documentsDirectory());
        assertEquals(directory.resolve("data/consent.db").toAbsolutePath(), config.sqliteFile());
    }

    @Test void rejectsPathsOutsidePluginDirectory() throws Exception {
        Path configFile = write(config("../documents", "data/consent.db"));
        assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(directory, configFile));
    }

    @Test void rejectsAbsolutePaths() throws Exception {
        String absolute = directory.resolve("elsewhere.db").toString().replace('\\', '/');
        Path configFile = write(config("documents", absolute));
        assertThrows(ConfigLoadException.class, () -> new ConfigLoader().load(directory, configFile));
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
