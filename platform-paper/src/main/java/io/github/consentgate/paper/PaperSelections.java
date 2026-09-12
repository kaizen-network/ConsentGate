package io.github.consentgate.paper;

import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.TagStringIO;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PaperSelections {
    static Map<String, Boolean> decode(String payload, List<String> documents) throws IOException {
        if (payload == null || payload.length() > 65536) return null;
        var tag = TagStringIO.get().asCompound(payload);
        if (tag.size() != documents.size()) return null;
        var result = new LinkedHashMap<String, Boolean>();
        for (int index = 0; index < documents.size(); index++) {
            if (!(tag.get("document_" + index) instanceof ByteBinaryTag value) || (value.value() != 0 && value.value() != 1)) return null;
            result.put(documents.get(index), value.value() == 1);
        }
        return result;
    }
}
