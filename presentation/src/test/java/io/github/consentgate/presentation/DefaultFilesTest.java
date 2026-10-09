package io.github.consentgate.presentation;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

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

    @Test void appendsMissingMessages() throws Exception {
        var added = new ArrayList<String>();
        String merged = DefaultFiles.mergeProperties("title=Title\ncontinue=Continue\nleave=Leave\n", "continue=Go", added);
        assertEquals("continue=Go\ntitle=Title\nleave=Leave\n", merged);
        assertEquals(List.of("title", "leave"), added);
    }
}
