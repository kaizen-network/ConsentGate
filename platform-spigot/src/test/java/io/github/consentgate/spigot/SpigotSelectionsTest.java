package io.github.consentgate.spigot;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SpigotSelectionsTest {
    private static final List<String> DOCUMENTS = List.of("rules", "privacy");

    @Test void checkboxBytesArriveAsNumbersAndBecomeSelections() {
        // CraftBukkit turns the NBT bytes 1b and 0b into JSON numbers.
        var payload = new JsonObject();
        payload.add("document_0", new JsonPrimitive((byte) 1));
        payload.add("document_1", new JsonPrimitive((byte) 0));
        assertEquals(Map.of("rules", true, "privacy", false), SpigotSelections.decode(payload, DOCUMENTS));
        assertEquals(Map.of("rules", true, "privacy", true), SpigotSelections.decode(JsonParser.parseString("{\"document_0\":1,\"document_1\":1.0}"), DOCUMENTS));
    }

    @Test void booleansStringsOtherNumbersAndWrongKeysAreRejected() {
        for (String payload : List.of("{}", "{\"document_0\":1}", "{\"document_0\":1,\"other\":1}",
                "{\"document_0\":1,\"document_1\":1,\"other\":1}", "{\"document_0\":true,\"document_1\":1}",
                "{\"document_0\":\"1\",\"document_1\":1}", "{\"document_0\":2,\"document_1\":1}",
                "{\"document_0\":-1,\"document_1\":1}", "{\"document_0\":0.5,\"document_1\":1}",
                "{\"document_0\":{},\"document_1\":1}", "{\"document_0\":null,\"document_1\":1}",
                "[1,1]", "1", "\"document_0\"")) {
            assertNull(SpigotSelections.decode(JsonParser.parseString(payload), DOCUMENTS), payload);
        }
        assertNull(SpigotSelections.decode(null, DOCUMENTS));
        var nan = new JsonObject();
        nan.add("document_0", new JsonPrimitive(Double.NaN));
        nan.add("document_1", new JsonPrimitive(1));
        assertNull(SpigotSelections.decode(nan, DOCUMENTS));
    }
}
