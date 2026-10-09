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

    @Test void olderMessageFilesUseBundledErrorText() throws Exception {
        String english;
        try (var input = getClass().getResourceAsStream("/messages/en-US.properties")) { english = new String(input.readAllBytes()); }
        String withoutErrors = english.lines().filter(line -> !line.startsWith("error-")).collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(directory.resolve("en-US.properties"), withoutErrors);
        Files.writeString(directory.resolve("id-ID.properties"), "continue=Lanjutkan\n");
        var messages = new InterfaceMessages(directory);
        assertDoesNotThrow(() -> messages.validateFor(List.of("en-US", "id-ID"), "en-US"));
        assertEquals("ConsentGate is busy. Please try again shortly.", messages.text("en-US", "en-US", "error-busy"));
        assertEquals("ConsentGate sedang sibuk. Silakan coba lagi sebentar lagi.", messages.text("id-ID", "en-US", "error-busy"));
        assertEquals("ConsentGate is busy. Please try again shortly.", InterfaceMessages.defaultText("error-busy"));
        assertThrows(IllegalArgumentException.class, () -> messages.text("en-US", "en-US", "no-such-key"));

        Files.writeString(directory.resolve("en-US.properties"), withoutErrors + "\nerror-busy=Full, come back soon.\n");
        assertEquals("Full, come back soon.", new InterfaceMessages(directory).text("en-US", "en-US", "error-busy"));
        Files.writeString(directory.resolve("en-US.properties"), withoutErrors + "\nerror-busy=\n");
        assertThrows(IllegalArgumentException.class,
                () -> new InterfaceMessages(directory).validateFor(List.of("en-US"), "en-US"));
    }

    @Test void incompleteOrBlankMessagesFailValidation() throws Exception {
        Files.writeString(directory.resolve("en-US.properties"), "title=Title\n");
        var missing = assertThrows(IllegalArgumentException.class,
                () -> new InterfaceMessages(directory).validateFor(List.of("en-US"), "en-US"));
        assertTrue(missing.getMessage().startsWith("Missing interface message: "));
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
