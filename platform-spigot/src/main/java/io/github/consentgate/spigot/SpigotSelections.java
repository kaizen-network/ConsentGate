package io.github.consentgate.spigot;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spigot turns the click NBT into JSON and keeps each number's type, so a checkbox arrives as a {@link Byte} of 0 or 1.
 * Like the Paper decoder, anything else is rejected: other number types, booleans, strings, and missing or extra keys.
 */
final class SpigotSelections {
    private SpigotSelections() { }

    static Map<String, Boolean> decode(JsonElement payload, List<String> documents) {
        if (payload == null || !payload.isJsonObject()) return null;
        var fields = payload.getAsJsonObject();
        if (fields.size() != documents.size()) return null;
        var result = new LinkedHashMap<String, Boolean>();
        for (int index = 0; index < documents.size(); index++) {
            if (!(fields.get("document_" + index) instanceof JsonPrimitive value) || !value.isNumber()
                    || !(value.getAsNumber() instanceof Byte checked) || (checked != 0 && checked != 1)) return null;
            result.put(documents.get(index), checked == 1);
        }
        return result;
    }
}
