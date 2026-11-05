package io.github.consentgate.spigot;

import io.github.consentgate.core.admission.AdmissionSession;
import io.github.consentgate.core.runtime.ConsentGateRuntime;
import io.github.consentgate.core.runtime.RuntimeLoader;
import io.github.consentgate.presentation.InterfaceMessages;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.chat.VersionedComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SpigotDialogsTest {
    @TempDir Path directory;
    private ConsentGateRuntime runtime;
    private SpigotDialogs dialogs;

    @BeforeEach void setup() throws Exception {
        Files.createDirectories(directory.resolve("documents"));
        Files.createDirectories(directory.resolve("messages"));
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            Files.writeString(directory.resolve("config.yml"), new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("enabled: false", "enabled: true"));
        }
        try (var input = getClass().getResourceAsStream("/example-terms.yml")) {
            Files.copy(input, directory.resolve("documents/terms.yml"));
        }
        runtime = new RuntimeLoader().load(directory);
        dialogs = new SpigotDialogs(runtime.config(), new InterfaceMessages(directory.resolve("messages")));
    }

    @AfterEach void close() throws Exception { runtime.close(); }

    // This is the JSON Spigot hands to the game's dialog codec.
    @Test void summaryKeepsThePaperShapeAndWaitsForTheServerAfterEachButton() throws Exception {
        var session = new AdmissionSession(runtime.admissionService().orElseThrow().check(UUID.randomUUID(), "en-US").orElseThrow());
        var dialog = json(dialogs.summary(session, "en-US", false));
        assertEquals("minecraft:multi_action", dialog.get("type").getAsString());
        assertEquals("wait_for_response", dialog.get("after_action").getAsString());
        assertTrue(dialog.get("can_close_with_escape").getAsBoolean());
        var input = dialog.getAsJsonArray("inputs").get(0).getAsJsonObject();
        assertEquals("document_0", input.get("key").getAsString());
        assertEquals("minecraft:boolean", input.get("type").getAsString());
        String text = dialog.toString();
        assertTrue(text.contains("\"id\":\"consentgate:accept/" + session.token() + "\""), text);
        assertTrue(text.contains("\"id\":\"consentgate:read/0/" + session.token() + "\""), text);
        assertTrue(text.contains("\"id\":\"consentgate:leave/" + session.token() + "\""), text);
        assertEquals(1, dialog.getAsJsonArray("inputs").size());
    }

    @Test void selectionsAreKeptWhenTheSummaryIsShownAgain() throws Exception {
        var request = runtime.admissionService().orElseThrow().check(UUID.randomUUID(), "en-US").orElseThrow();
        var session = new AdmissionSession(request);
        session.updateSelections(session.token(), Map.of(request.documents().getFirst().id(), true));
        var dialog = json(dialogs.summary(session, "en-US", true));
        assertTrue(dialog.getAsJsonArray("inputs").get(0).getAsJsonObject().get("initial").getAsBoolean());
    }

    private static com.google.gson.JsonObject json(net.md_5.bungee.api.dialog.Dialog dialog) {
        return VersionedComponentSerializer.getDefault().getDialogSerializer().toJson(dialog).getAsJsonObject();
    }

    @Test void formattedTextSurvivesTheTripThroughJson() {
        var component = SpigotDialogs.chat(Component.text("Rules").append(Component.text(" apply", net.kyori.adventure.text.format.NamedTextColor.GOLD)));
        assertEquals("Rules apply", component.toPlainText());
        assertTrue(ComponentSerializer.toString(component).contains("\"color\":\"gold\""));
    }
}
