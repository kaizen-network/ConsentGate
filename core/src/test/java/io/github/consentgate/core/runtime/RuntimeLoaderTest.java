package io.github.consentgate.core.runtime;

import io.github.consentgate.core.document.DocumentLoadException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeLoaderTest {
    @TempDir Path directory;

    @Test void disabledRuntimeNeedsNoDocumentsOrDatabase() throws Exception {
        Files.writeString(directory.resolve("config.yml"), config(false));
        try (var runtime = new RuntimeLoader().load(directory)) {
            assertFalse(runtime.enabled());
            assertTrue(runtime.admissionService().isEmpty());
            assertFalse(Files.exists(directory.resolve("data/consent.db")));
        }
    }

    @Test void disabledRuntimeCanEnableWithoutRestart() throws Exception {
        Files.writeString(directory.resolve("config.yml"), config(false));
        var loader = new RuntimeLoader();
        try (var runtime = loader.load(directory)) {
            Files.createDirectory(directory.resolve("documents"));
            Files.writeString(directory.resolve("documents/rules.yml"), document());
            Files.writeString(directory.resolve("config.yml"), config(true));
            var prepared = loader.prepare(directory);
            assertFalse(Files.exists(directory.resolve("data")));
            try (var enabled = runtime.reconfigured(prepared)) {
                assertTrue(enabled.enabled());
                assertEquals(1, enabled.admissionService().orElseThrow().catalog().required().size());
                assertTrue(Files.isRegularFile(directory.resolve("data/consent.db")));
            }
            assertFalse(runtime.enabled());
        }
    }

    @Test void failedEnableKeepsDisabledRuntimeUsableForRetry() throws Exception {
        Files.writeString(directory.resolve("config.yml"), config(false));
        var loader = new RuntimeLoader();
        try (var runtime = loader.load(directory)) {
            Files.createDirectory(directory.resolve("documents"));
            Files.writeString(directory.resolve("documents/rules.yml"), document());
            Files.writeString(directory.resolve("config.yml"), config(true));
            Files.writeString(directory.resolve("data"), "Not a directory");
            assertThrows(Exception.class, () -> runtime.reconfigured(loader.prepare(directory)));
            assertFalse(runtime.enabled());
            assertTrue(runtime.admissionService().isEmpty());
            Files.delete(directory.resolve("data"));
            try (var enabled = runtime.reconfigured(loader.prepare(directory))) { assertTrue(enabled.enabled()); }
        }
    }

    @Test void enabledRuntimeLoadsDocumentsAndInitializesSQLite() throws Exception {
        Files.createDirectory(directory.resolve("documents"));
        Files.writeString(directory.resolve("documents/rules.yml"), document());
        Files.writeString(directory.resolve("config.yml"), config(true));
        try (var runtime = new RuntimeLoader().load(directory)) {
            assertTrue(runtime.enabled());
            assertTrue(runtime.admissionService().isPresent());
            assertTrue(Files.isRegularFile(directory.resolve("data/consent.db")));
        }
    }

    @Test void enabledRuntimeRejectsAnEmptyDocumentDirectory() throws Exception {
        Files.createDirectory(directory.resolve("documents"));
        Files.writeString(directory.resolve("config.yml"), config(true));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeLoader().load(directory));
        assertFalse(Files.exists(directory.resolve("data/consent.db")));
    }

    @Test void enabledRuntimeRejectsMissingDocumentDirectory() throws Exception {
        Files.writeString(directory.resolve("config.yml"), config(true));
        assertThrows(DocumentLoadException.class, () -> new RuntimeLoader().load(directory));
    }

    @Test void preparationDoesNotCreateStorage() throws Exception {
        Files.createDirectory(directory.resolve("documents"));
        Files.writeString(directory.resolve("documents/rules.yml"), document());
        Files.writeString(directory.resolve("config.yml"), config(true));
        assertEquals(1, new RuntimeLoader().prepare(directory).catalog().required().size());
        assertFalse(Files.exists(directory.resolve("data")));
    }

    @Test void reloadRejectsUnversionedEditsAndKeepsOldRuntime() throws Exception {
        Files.createDirectory(directory.resolve("documents"));
        var documentFile = directory.resolve("documents/rules.yml");
        Files.writeString(documentFile, document());
        Files.writeString(directory.resolve("config.yml"), config(true));
        var loader = new RuntimeLoader();
        try (var runtime = loader.load(directory)) {
            Files.writeString(documentFile, document().replace("body: \"Text\"", "body: \"Edited\""));
            assertThrows(IllegalArgumentException.class, () -> runtime.reconfigured(loader.prepare(directory)));
            assertEquals("v1", runtime.admissionService().orElseThrow().catalog().required().getFirst().version());
            Files.writeString(documentFile, document().replace("version: \"v1\"", "version: \"v2\""));
            var next = runtime.reconfigured(loader.prepare(directory));
            assertEquals("v2", next.admissionService().orElseThrow().catalog().required().getFirst().version());
            assertEquals("v1", runtime.admissionService().orElseThrow().catalog().required().getFirst().version());
        }
    }

    @Test void reloadRejectsRestartOnlyChangesWithoutCreatingAnotherDatabase() throws Exception {
        Files.createDirectory(directory.resolve("documents"));
        Files.writeString(directory.resolve("documents/rules.yml"), document());
        Files.writeString(directory.resolve("config.yml"), config(true));
        var loader = new RuntimeLoader();
        try (var runtime = loader.load(directory)) {
            for (String edited : java.util.List.of(config(false), config(true).replace("scope: main", "scope: other"),
                    config(true).replace("max-pending: 128", "max-pending: 64"),
                    config(true).replace("data/consent.db", "other/consent.db"))) {
                Files.writeString(directory.resolve("config.yml"), edited);
                assertThrows(IllegalArgumentException.class, () -> runtime.reconfigured(loader.prepare(directory)));
            }
            assertFalse(Files.exists(directory.resolve("other")));
        }
    }

    private static String config(boolean enabled) {
        return """
                config-version: 1
                enabled: %s
                scope: main
                gate:
                  timeout-seconds: 300
                  max-pending: 128
                language:
                  default: en-US
                  use-client-locale: true
                documents:
                  directory: documents
                storage:
                  type: sqlite
                  sqlite:
                    file: data/consent.db
                """.formatted(enabled);
    }

    private static String document() {
        return """
                id: rules
                version: "v1"
                required: true
                order: 10
                translations:
                  en-US:
                    title: "Rules"
                    summary: "Please read."
                    checkbox: "I agree"
                    read-button: "Read"
                    pages:
                      - title: "Page"
                        body: "Text"
                """;
    }
}
