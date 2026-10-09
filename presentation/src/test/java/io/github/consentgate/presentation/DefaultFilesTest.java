package io.github.consentgate.presentation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultFilesTest {
    @Test void addsMissingConfigKeysAndKeepsAdminChoices() throws Exception {
        String bundled;
        try (var input = getClass().getResourceAsStream("/config.yml")) { bundled = new String(input.readAllBytes()).replace("\r\n", "\n"); }
        var added = new ArrayList<String>();
        assertEquals(bundled, DefaultFiles.mergeYaml(bundled, bundled, added));
        assertTrue(added.isEmpty());

        String current = bundled
                .replace("gate:\n  # 30-1800 seconds to accept. Timeout disconnects the player.\n  timeout-seconds: 300\n"
                        + "  # 1-10000 players waiting at once. Extra connections are rejected. Restart after changes.\n  max-pending: 128\n",
                        "gate:\n    # my own note\n    timeout-seconds: 120\n")
                .replace("      id-ID: \"Bahasa Indonesia\"\n", "")
                .replace("# Enable native forms when Geyser is installed on this server or proxy.\nbedrock:\n  native-forms: false\n  button-color: dark_gray\n\n", "");
        assertNotEquals(bundled, current);
        String merged = DefaultFiles.mergeYaml(bundled, current, added);
        assertEquals(List.of("gate.max-pending", "bedrock"), added);
        assertTrue(merged.contains("    # my own note\n    timeout-seconds: 120\n"
                + "    # 1-10000 players waiting at once. Extra connections are rejected. Restart after changes.\n    max-pending: 128\n"));
        Map<?, ?> config = new Yaml().load(merged);
        assertEquals(Map.of("timeout-seconds", 120, "max-pending", 128), config.get("gate"));
        assertEquals(Map.of("native-forms", false, "button-color", "dark_gray"), config.get("bedrock"));
        assertEquals(Map.of("en-US", "English"), ((Map<?, ?>) ((Map<?, ?>) config.get("language")).get("selector")).get("options"));
    }

    @Test void updatesFilesInPlace(@TempDir Path directory) throws Exception {
        Path messages = directory.resolve("en-US.properties");
        Files.writeString(messages, "continue=Go\n");
        Files.writeString(directory.resolve("en-US.properties.tmp"), "stale");
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class);
        if (posix) Files.setPosixFilePermissions(messages, PosixFilePermissions.fromString("rw-------"));
        var added = DefaultFiles.merge("messages/en-US.properties", messages);
        assertTrue(added.contains("error-busy"));
        assertFalse(added.contains("continue"));
        assertTrue(Files.readString(messages).startsWith("continue=Go\n"));
        assertFalse(Files.exists(directory.resolve("en-US.properties.tmp")));
        if (posix) assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(messages));

        String merged = Files.readString(messages);
        assertEquals(List.of(), DefaultFiles.merge("messages/en-US.properties", messages));
        assertEquals(merged, Files.readString(messages));

        Path example = directory.resolve("terms.yml.example");
        Files.writeString(example, "custom: true\n");
        assertEquals(List.of(), DefaultFiles.merge("example-terms.yml", example));
        assertEquals("custom: true\n", Files.readString(example));
    }

    @Test void refusesLayoutsItCannotExtend(@TempDir Path directory) throws Exception {
        String template = "gate:\n  timeout-seconds: 300\n  max-pending: 128\n";
        assertThrows(java.io.IOException.class, () -> DefaultFiles.mergeYaml(template, "gate: {timeout-seconds: 300}\n", new ArrayList<>()));
        assertThrows(java.io.IOException.class, () -> DefaultFiles.mergeYaml("enabled: false\n" + template,
                "gate:\n  timeout-seconds: 300\n  max-pending: 128\n...\n", new ArrayList<>()));
        var added = new ArrayList<String>();
        assertEquals("gate:\n  timeout-seconds: 300\n  max-pending: 128\n...\n",
                DefaultFiles.mergeYaml(template, "gate:\n  timeout-seconds: 300\n...\n", added));

        Path config = directory.resolve("config.yml");
        Files.writeString(config, "enabled: false\ngate: {timeout-seconds: 300}\n");
        assertThrows(java.io.IOException.class, () -> DefaultFiles.merge("config.yml", config));
        assertEquals("enabled: false\ngate: {timeout-seconds: 300}\n", Files.readString(config));
        assertFalse(Files.exists(directory.resolve("config.yml.tmp")));
    }

    @Test void refusesMergesThatChangeOrSkipSettings() {
        String template = "gate:\n  timeout-seconds: 300\n  max-pending: 128\n";
        // Inserting before the kept blank lines would shorten the admin's value.
        assertThrows(java.io.IOException.class, () -> DefaultFiles.mergeYaml(template,
                "gate:\n  timeout-seconds: |+\n    300\n\n\nother: 1\n", new ArrayList<>()));
        // The alias would receive the new setting too.
        assertThrows(java.io.IOException.class, () -> DefaultFiles.mergeYaml(template,
                "gate: &shared\n  timeout-seconds: 300\nother: *shared\n", new ArrayList<>()));
        // The scanner cannot find a quoted section, which must not be skipped silently.
        assertThrows(java.io.IOException.class, () -> DefaultFiles.mergeYaml(template,
                "\"gate\":\n  timeout-seconds: 300\n", new ArrayList<>()));
        // A last line ending with a backslash continues into the first added key.
        assertThrows(java.io.IOException.class, () -> DefaultFiles.mergeProperties("error-busy=Busy\n", "title=Mine \\", new ArrayList<>()));
    }

    @Test void leavesReadOnlyAndOversizedFilesUnchanged(@TempDir Path directory) throws Exception {
        Path locked = directory.resolve("en-US.properties");
        Files.writeString(locked, "continue=Go\n");
        assertTrue(locked.toFile().setWritable(false));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(locked), "Running with permission to write read-only files");
            assertThrows(java.io.IOException.class, () -> DefaultFiles.merge("messages/en-US.properties", locked));
            assertEquals("continue=Go\n", Files.readString(locked));
        } finally {
            locked.toFile().setWritable(true);
        }

        Path large = directory.resolve("id-ID.properties");
        String content = "filler=" + "x".repeat(65_000) + "\n";
        Files.writeString(large, content);
        assertThrows(java.io.IOException.class, () -> DefaultFiles.merge("messages/id-ID.properties", large));
        assertEquals(content, Files.readString(large));
    }

    @Test void keepsWindowsLineEndings() throws Exception {
        var added = new ArrayList<String>();
        String merged = DefaultFiles.mergeYaml("gate:\n  timeout-seconds: 300\n  max-pending: 128\n",
                "gate:\r\n  timeout-seconds: 120\r\n", added);
        assertEquals("gate:\r\n  timeout-seconds: 120\r\n  max-pending: 128\r\n", merged);
        assertEquals(List.of("gate.max-pending"), added);
    }

    @Test void bundledMessageFilesHaveTheSameKeys() throws Exception {
        var english = new java.util.Properties();
        var indonesian = new java.util.Properties();
        try (var input = getClass().getResourceAsStream("/messages/en-US.properties")) { english.load(input); }
        try (var input = getClass().getResourceAsStream("/messages/id-ID.properties")) { indonesian.load(input); }
        assertEquals(english.stringPropertyNames(), indonesian.stringPropertyNames());
    }

    @Test void leavesInvalidConfigForTheLoaderToReport() throws Exception {
        String broken = "gate: [unclosed\n";
        var added = new ArrayList<String>();
        assertEquals(broken, DefaultFiles.mergeYaml("gate:\n  max-pending: 128\n", broken, added));
        assertTrue(added.isEmpty());
    }

    @Test void appendsMissingMessages() throws Exception {
        var added = new ArrayList<String>();
        String merged = DefaultFiles.mergeProperties("title=Title\ncontinue=Continue\nleave=Leave\n", "continue=Go", added);
        assertEquals("continue=Go\ntitle=Title\nleave=Leave\n", merged);
        assertEquals(List.of("title", "leave"), added);
    }
}
