package io.github.consentgate.core.document;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

public record DocumentCatalog(List<DocumentRevision> documents) {
    public DocumentCatalog {
        var byId = new LinkedHashMap<String, DocumentRevision>();
        for (var document : documents) {
            if (byId.putIfAbsent(document.id(), document) != null) {
                throw new IllegalArgumentException("Duplicate document id: " + document.id());
            }
        }
        documents = byId.values().stream()
                .sorted(Comparator.comparingInt(DocumentRevision::order).thenComparing(DocumentRevision::id))
                .toList();
    }

    public List<DocumentRevision> required() {
        return documents.stream().filter(DocumentRevision::required).toList();
    }
}
