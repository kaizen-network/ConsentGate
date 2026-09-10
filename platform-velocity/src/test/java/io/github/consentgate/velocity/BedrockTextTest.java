package io.github.consentgate.velocity;

import io.github.consentgate.core.config.DialogAppearance;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BedrockTextTest {
    private final SafeTextFormatter formatter = new SafeTextFormatter(DialogAppearance.defaults());

    @Test void clearsBoldBeforeSummaryAndBodyText() {
        String formatted = BedrockText.serialize(formatter.text("<gold><bold>Title</bold></gold><gray>Summary</gray> Body"));
        assertEquals("\u00a7r\u00a76\u00a7lTitle\u00a7r\u00a77Summary\u00a7r\u00a7f Body\u00a7r", formatted);
    }

    @Test void preservesIntentionalBoldAcrossNestedColorsThenResetsIt() {
        String formatted = BedrockText.serialize(formatter.text("<bold>One<red>Two</red></bold>Three"));
        assertEquals("\u00a7r\u00a7f\u00a7lOne\u00a7r\u00a7c\u00a7lTwo\u00a7r\u00a7fThree\u00a7r", formatted);
    }

    @Test void isolatesFieldsAndAvoidsUnsupportedJavaDecorationCodes() {
        String formatted = BedrockText.serialize(formatter.text("<underlined>A</underlined><strikethrough>B</strikethrough>"));
        assertFalse(formatted.contains("\u00a7n"));
        assertFalse(formatted.contains("\u00a7m"));
        assertTrue(formatted.startsWith("\u00a7r"));
        assertTrue(formatted.endsWith("\u00a7r"));
    }
}
