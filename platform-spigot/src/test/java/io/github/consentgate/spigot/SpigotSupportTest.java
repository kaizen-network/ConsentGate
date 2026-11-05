package io.github.consentgate.spigot;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SpigotSupportTest {
    private static final Set<String> SPIGOT = Set.of(SpigotSupport.CLICK, SpigotSupport.DIALOG);

    @Test void refusalMatrix() {
        // Paper 1.21.7 and newer: no Spigot dialog types, but Paper's own.
        assertEquals(SpigotSupport.ON_PAPER, SpigotSupport.refusal(SpigotSupport.PAPER::contains, () -> false));
        // Paper 1.21 to 1.21.6, or a Paper fork that also carries the Spigot types.
        assertEquals(SpigotSupport.ON_PAPER, SpigotSupport.refusal(name -> true, () -> true));
        // Spigot 1.21.6 and newer.
        assertNull(SpigotSupport.refusal(SPIGOT::contains, () -> true));
        // Spigot 1.21 to 1.21.5, and CraftBukkit.
        assertEquals(SpigotSupport.NO_DIALOGS, SpigotSupport.refusal(name -> false, () -> false));
        assertEquals(SpigotSupport.NO_DIALOGS, SpigotSupport.refusal(Set.of(SpigotSupport.DIALOG)::contains, () -> true));
        assertEquals(SpigotSupport.NO_DIALOGS, SpigotSupport.refusal(SPIGOT::contains, () -> false));
    }

    @Test void detectsTheRealSpigotApiAndItsAbsence() {
        assertNull(SpigotSupport.refusal(getClass().getClassLoader()), "The Spigot 1.21.8 API on the test classpath has dialogs");
        assertEquals(SpigotSupport.NO_DIALOGS, SpigotSupport.refusal(ClassLoader.getPlatformClassLoader()));
    }

    @Test void messagesMatchTheDocumentedWording() {
        assertEquals("ConsentGate is running in Spigot hold mode. Players enter the world first and stay frozen and hidden until"
                + " they accept. This is weaker than Paper, where players accept before entering the world. Other plugins still"
                + " see each join. Use Paper for the full guarantee.", SpigotSupport.HOLD_MODE);
        assertEquals("ConsentGate is not set up for this server. Ask the server owner to install the right ConsentGate file.", SpigotSupport.KICK);
    }

    // The server links the main class when it loads the plugin. A dialog or Adventure type in these classes makes that
    // fail where the API is missing, and the server then runs without ConsentGate instead of refusing logins.
    @Test void classesUsedWithoutDialogsReferenceOnlyBukkitTypes() throws Exception {
        for (String type : List.of("ConsentGateSpigot", "SpigotSupport", "SpigotSupport$Refusal")) {
            try (var input = getClass().getClassLoader().getResourceAsStream("io/github/consentgate/spigot/" + type + ".class")) {
                assertNotNull(input, type);
                String bytecode = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (String missing : List.of("net/md_5/bungee/api/dialog", "org/bukkit/event/player/PlayerCustomClickEvent",
                        "net/kyori/", "io/github/consentgate/internal/", "com/google/gson/")) {
                    assertFalse(bytecode.contains(missing), type + " references " + missing);
                }
            }
        }
    }
}
