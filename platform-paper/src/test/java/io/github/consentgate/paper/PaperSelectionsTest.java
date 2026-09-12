package io.github.consentgate.paper;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PaperSelectionsTest {
    private static final List<String> DOCUMENTS = List.of("rules", "privacy");
    @Test void onlyByteCheckboxesBecomeSelections() throws Exception {
        assertEquals(Map.of("rules", true, "privacy", false), PaperSelections.decode("{document_0:1b,document_1:0b}", DOCUMENTS));
    }
    @Test void missingExtraWrongTypeAndOutOfRangeFieldsAreRejected() throws Exception {
        for (String payload : List.of("{}", "{document_0:1b}", "{document_0:1b,other:1b}",
                "{document_0:1b,document_1:1b,other:1b}", "{document_0:1,document_1:1b}",
                "{document_0:2b,document_1:1b}", "{document_0:-1b,document_1:1b}")) {
            assertNull(PaperSelections.decode(payload, DOCUMENTS), payload);
        }
        assertNull(PaperSelections.decode(null, DOCUMENTS));
        assertNull(PaperSelections.decode("x".repeat(65537), DOCUMENTS));
    }
    @Test void malformedNbtDoesNotProduceSelections() {
        assertThrows(java.io.IOException.class, () -> PaperSelections.decode("{", DOCUMENTS));
    }
}
