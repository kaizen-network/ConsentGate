package io.github.consentgate.presentation;

import io.github.consentgate.core.runtime.RuntimeLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PresentationValidatorTest {
    @TempDir Path directory;

    @BeforeEach void fixtures() throws Exception {
        Files.createDirectories(directory.resolve("messages"));
        Files.createDirectories(directory.resolve("documents"));
        copy("config.yml", "config.yml");
        copy("example-terms.yml", "documents/terms.yml");
        copy("messages/en-US.properties", "messages/en-US.properties");
        copy("messages/id-ID.properties", "messages/id-ID.properties");
        replace("config.yml", "enabled: false", "enabled: true");
    }

    @Test void bundledPresentationPassesWithoutOpeningStorage() throws Exception {
        validate();
        assertFalse(Files.exists(directory.resolve("data")));
    }

    @Test void nativeFormsCanBeConfiguredWithoutLoadingGeyserClasses() throws Exception {
        replace("config.yml", "native-forms: false", "native-forms: true");
        validate();
        assertFalse(Files.exists(directory.resolve("data")));
    }

    @Test void unsafeSelectorMarkupIsRejected() throws Exception {
        replace("config.yml", "English", "<click:run_command:'/stop'>English</click>");
        assertThrows(IllegalArgumentException.class, this::validate);
    }

    @Test void emptyRequiredInterfaceMessageIsRejected() throws Exception {
        Files.writeString(directory.resolve("messages/en-US.properties"), "title=\n");
        assertThrows(IllegalArgumentException.class, this::validate);
    }

    @Test void missingRequiredInterfaceMessageIsRejected() throws Exception {
        replace("messages/en-US.properties", "continue=Continue", "");
        assertThrows(IllegalArgumentException.class, this::validate);
    }

    @Test void markupOnlyInterfaceMessageIsRejected() throws Exception {
        replace("messages/en-US.properties", "continue=Continue", "continue=<white></white>");
        assertThrows(IllegalArgumentException.class, this::validate);
    }

    @Test void failedPresentationValidationLeavesRunningRuntimeUsable() throws Exception {
        try (var runtime = new RuntimeLoader().load(directory)) {
            String original = runtime.admissionService().orElseThrow().catalog().required().getFirst().version();
            replace("config.yml", "English", "<click:run_command:'/stop'>English</click>");
            assertThrows(IllegalArgumentException.class, this::validate);
            assertFalse(runtime.config().nativeBedrockForms());
            assertEquals(original, runtime.admissionService().orElseThrow().catalog().required().getFirst().version());
            assertTrue(runtime.admissionService().orElseThrow().check(java.util.UUID.randomUUID(), "en-US").isPresent());
        }
    }

    private void validate() throws Exception {
        var prepared = new RuntimeLoader().prepare(directory);
        PresentationValidator.validate(prepared.config(), prepared.catalog(), new InterfaceMessages(directory.resolve("messages")));
    }

    private void copy(String resource, String target) throws Exception {
        try (var input = java.util.Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(resource))) {
            Files.copy(input, directory.resolve(target));
        }
    }

    private void replace(String file, String from, String to) throws Exception {
        Path path = directory.resolve(file);
        String original = Files.readString(path);
        assertTrue(original.contains(from), "Fixture text must exist");
        Files.writeString(path, original.replace(from, to));
    }
}
