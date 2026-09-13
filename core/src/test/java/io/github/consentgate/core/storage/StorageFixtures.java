package io.github.consentgate.core.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class StorageFixtures {
    private StorageFixtures() { }

    static ShownDocument shown(String id, String version, String text) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            return new ShownDocument(id, version, "en-US", hash, "Example document", text);
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    static ShownDocument variant(String id, String version, String locale) {
        var document = shown(id, version, "Text in " + locale);
        return new ShownDocument(id, version, locale, document.contentHash(), document.title(), document.contentSnapshot());
    }
}
