package io.github.consentgate.velocity;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class InterfaceMessagesTest {
    @Test void usesIndonesianLabelsAndEnglishFallback() throws Exception {
        var directory = Path.of(getClass().getResource("/messages/en-US.properties").toURI()).getParent();
        var messages = new InterfaceMessages(directory);
        assertEquals("Lanjutkan", messages.text("id-ID", "en-US", "continue"));
        assertEquals("Semua kotak wajib dicentang.", messages.text("id-ID", "en-US", "required"));
        assertEquals("Continue", messages.text("fr-FR", "en-US", "continue"));
        assertEquals("Keluar", messages.text("fr-FR", "id-ID", "leave"));
    }
}
