package io.github.consentgate.spigot;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Spigot turns the checkbox NBT bytes into JSON numbers, so only 0 and 1 count; booleans, strings, and extra keys do not. */
final class SpigotSelections {
    private SpigotSelections() { }

    static Map<String, Boolean> decode(JsonElement payload, List<String> documents) {
        if (payload == null || !payload.isJsonObject()) return null;
        var fields = payload.getAsJsonObject();
        if (fields.size() != documents.size()) return null;
        var result = new LinkedHashMap<String, Boolean>();
        for (int index = 0; index < documents.size(); index++) {
            if (!(fields.get("document_" + index) instanceof JsonPrimitive value) || !value.isNumber()) return null;
            BigDecimal number;
            try { number = value.getAsBigDecimal(); }
            catch (NumberFormatException ex) { return null; }
            boolean checked = number.compareTo(BigDecimal.ONE) == 0;
            if (!checked && number.compareTo(BigDecimal.ZERO) != 0) return null;
            result.put(documents.get(index), checked);
        }
        return result;
    }
}
