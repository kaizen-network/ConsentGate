package io.github.consentgate.core.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DocumentLoaderTest {
    @TempDir Path directory;

    @Test void loadsOrdersAndHashesDocuments() throws Exception {
        write("later.yml", document("rules", "v1", 20, "Read these rules."));
        write("first.yml", document("privacy", "2026-09", 10, "Read this notice."));

        var catalog = new DocumentLoader().loadDirectory(directory);

        assertEquals(java.util.List.of("privacy", "rules"), catalog.documents().stream().map(DocumentRevision::id).toList());
        var rules = catalog.documents().get(1);
        assertEquals(64, rules.translations().get("en-US").contentHash().length());
        assertEquals("Rules", rules.translation("en-GB", "en-US").title());
    }

    @Test void sameContentHasStableHashAndChangedContentDoesNot() throws Exception {
        Path file = write("rules.yml", document("rules", "v1", 10, "Read these rules."));
        var loader = new DocumentLoader();
        String first = loader.loadFile(directory, file).translations().get("en-US").contentHash();
        String second = loader.loadFile(directory, file).translations().get("en-US").contentHash();
        write("rules.yml", document("rules", "v1", 10, "Changed text."));
        String changed = loader.loadFile(directory, file).translations().get("en-US").contentHash();
        assertEquals(first, second);
        assertNotEquals(first, changed);
    }

    @Test void rejectsDuplicateIdsAcrossFiles() throws Exception {
        write("a.yml", document("rules", "v1", 10, "One"));
        write("b.yml", document("rules", "v2", 20, "Two"));
        var error = assertThrows(DocumentLoadException.class, () -> new DocumentLoader().loadDirectory(directory));
        assertTrue(error.getMessage().contains("Duplicate document id"));
    }

    @Test void rejectsUnknownAndDuplicateYamlKeys() throws IOException {
        Path unknown = write("unknown.yml", document("rules", "v1", 10, "Text") + "unknown: true\n");
        assertThrows(DocumentLoadException.class, () -> new DocumentLoader().loadFile(directory, unknown));

        Path duplicate = write("duplicate.yml", document("rules", "v1", 10, "Text") + "id: other\n");
        assertThrows(DocumentLoadException.class, () -> new DocumentLoader().loadFile(directory, duplicate));
    }

    @Test void normalizesLocalesAndRejectsEquivalentKeys() throws Exception {
        Path file = write("rules.yml", document("rules", "v1", 10, "Text").replace("en-US:", "EN_us:"));
        var revision = new DocumentLoader().loadFile(directory, file);
        assertEquals("Rules", revision.translation("en_us", "en-US").title());
        assertTrue(revision.translations().containsKey("en-US"));

        write("rules.yml", document("rules", "v1", 10, "Text").replace("  en-US:", "  en-US:\n" +
                "    title: Duplicate\n    summary: Duplicate\n    checkbox: Duplicate\n    read-button: Duplicate\n    pages:\n      - title: Duplicate\n        body: Duplicate\n  EN_us:"));
        assertThrows(DocumentLoadException.class, () -> new DocumentLoader().loadFile(directory, file));
    }

    private Path write(String name, String text) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    private static String document(String id, String version, int order, String body) {
        return """
                id: %s
                version: "%s"
                required: true
                order: %d
                translations:
                  en-US:
                    title: "Rules"
                    summary: "Please read."
                    checkbox: "I agree"
                    read-button: "Read"
                    pages:
                      - title: "Page one"
                        body: "%s"
                """.formatted(id, version, order, body);
    }
}
