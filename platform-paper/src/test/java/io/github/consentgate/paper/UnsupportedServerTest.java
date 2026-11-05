package io.github.consentgate.paper;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UnsupportedServerTest {
    @Test void reportsEveryPaperApiAsMissingWithoutPaper() {
        assertEquals(UnsupportedServer.PAPER_APIS, UnsupportedServer.missing(ClassLoader.getPlatformClassLoader()));
    }

    // Spigot links the main class when it loads the plugin. One Paper or Adventure type in these classes
    // makes that fail, and the server then runs without ConsentGate instead of refusing logins.
    @Test void classesUsedOnSpigotReferenceOnlyBukkitTypes() throws Exception {
        for (String type : List.of("ConsentGatePaper", "UnsupportedServer", "UnsupportedServer$Refusal")) {
            try (var input = getClass().getClassLoader().getResourceAsStream("io/github/consentgate/paper/" + type + ".class")) {
                assertNotNull(input, type);
                String bytecode = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (String paper : List.of("io/papermc/", "com/destroystokyo/", "net/kyori/")) {
                    assertFalse(bytecode.contains(paper), type + " references " + paper);
                }
            }
        }
    }
}
