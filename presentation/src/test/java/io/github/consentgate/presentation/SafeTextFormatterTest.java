package io.github.consentgate.presentation;

import io.github.consentgate.core.config.DialogAppearance;
import io.github.consentgate.core.document.DocumentCatalog;
import io.github.consentgate.core.document.DocumentLoader;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafeTextFormatterTest {
    private final SafeTextFormatter formatter = new SafeTextFormatter(DialogAppearance.defaults());

    @Test void appliesConfiguredDefaultColor() {
        assertEquals(NamedTextColor.WHITE, formatter.text("Plain text").color());
        assertEquals(NamedTextColor.GOLD, formatter.title("Title").color());
    }

    @Test void acceptsColorsDecorationsAndBundledExamples() throws Exception {
        SafeTextFormatter.validate("<yellow>Notice</yellow> <bold>Important</bold> <#12abef>Custom</#12abef>");
        Path terms = resource("/example-terms.yml");
        Path privacy = resource("/example-privacy.yml");
        SafeTextFormatter.validateCatalog(new DocumentCatalog(List.of(
                new DocumentLoader().loadFile(terms.getParent(), terms),
                new DocumentLoader().loadFile(privacy.getParent(), privacy))));
    }

    @Test void rejectsInteractiveAndExpandingTags() {
        assertThrows(IllegalArgumentException.class,
                () -> SafeTextFormatter.validate("<click:run_command:'/stop'>Unsafe</click>"));
        assertThrows(IllegalArgumentException.class,
                () -> SafeTextFormatter.validate("<hover:show_text:'Hidden'>Unsafe</hover>"));
        assertThrows(IllegalArgumentException.class,
                () -> SafeTextFormatter.validate("<gradient:red:blue>Large</gradient>"));
        assertThrows(IllegalArgumentException.class,
                () -> SafeTextFormatter.validate("<bold>Unclosed"));
    }

    private Path resource(String name) throws Exception {
        return Path.of(Objects.requireNonNull(getClass().getResource(name)).toURI());
    }
}
