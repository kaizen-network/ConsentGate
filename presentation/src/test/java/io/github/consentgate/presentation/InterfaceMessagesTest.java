package io.github.consentgate.presentation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterfaceMessagesTest {
    @TempDir Path directory;

    @Test void usesIndonesianLabelsAndEnglishFallback() throws Exception {
        var bundled = Path.of(getClass().getResource("/messages/en-US.properties").toURI()).getParent();
        var messages = new InterfaceMessages(bundled);
        assertEquals("Lanjutkan", messages.text("id-ID", "en-US", "continue"));
        assertEquals("Semua kotak wajib dicentang.", messages.text("id-ID", "en-US", "required"));
        assertEquals("Continue", messages.text("fr-FR", "en-US", "continue"));
        assertEquals("Keluar", messages.text("fr-FR", "id-ID", "leave"));
    }

    @Test void incompleteOrBlankMessagesFailValidation() throws Exception {
        Files.writeString(directory.resolve("en-US.properties"), "title=Title\n");
        assertThrows(IllegalArgumentException.class,
                () -> new InterfaceMessages(directory).validateFor(List.of("en-US"), "en-US"));
        try (var input = getClass().getResourceAsStream("/messages/en-US.properties")) {
            assertNotNull(input);
            Files.write(directory.resolve("en-US.properties"), input.readAllBytes());
        }
        assertDoesNotThrow(() -> new InterfaceMessages(directory).validateFor(List.of("id-ID"), "en-US"));
        Files.writeString(directory.resolve("id-ID.properties"), "continue=\n");
        assertThrows(IllegalArgumentException.class,
                () -> new InterfaceMessages(directory).validateFor(List.of("id-ID"), "en-US"));
    }
}
