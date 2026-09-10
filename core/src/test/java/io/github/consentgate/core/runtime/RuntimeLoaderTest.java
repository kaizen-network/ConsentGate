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
