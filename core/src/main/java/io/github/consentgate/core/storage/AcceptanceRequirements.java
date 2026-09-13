package io.github.consentgate.core.storage;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Each required document may match any of its current translations. */
public final class AcceptanceRequirements {
    private final Map<String, List<ShownDocument>> alternatives;
    private final List<ShownDocument> documents;

    public AcceptanceRequirements(Collection<ShownDocument> documents) {
        if (documents.size() > 32 * 32) throw new IllegalArgumentException("At most 1024 document variants are allowed");
        var grouped = new TreeMap<String, Map<Revision, ShownDocument>>();
        for (var document : documents) {
            Objects.requireNonNull(document, "document");
            var variants = grouped.computeIfAbsent(document.documentId(), ignored -> new TreeMap<>(
                    Comparator.comparing(Revision::version).thenComparing(Revision::locale)));
            var previous = variants.putIfAbsent(new Revision(document.version(), document.locale()), document);
            if (previous != null && !previous.equals(document)) throw new IllegalArgumentException("Conflicting document revision");
            if (grouped.size() > 32 || variants.size() > 32) {
                throw new IllegalArgumentException("At most 32 documents with 32 variants each are allowed");
            }
        }
        var result = new TreeMap<String, List<ShownDocument>>();
        grouped.forEach((id, variants) -> result.put(id, List.copyOf(variants.values())));
        alternatives = Collections.unmodifiableMap(result);
        this.documents = alternatives.values().stream().flatMap(List::stream).toList();
    }

    public static AcceptanceRequirements exact(Collection<ShownDocument> documents) {
        var result = new AcceptanceRequirements(documents);
        if (result.documents.size() != result.alternatives.size()) {
            throw new IllegalArgumentException("Conflicting document requirement");
        }
        return result;
    }

    public Set<String> documentIds() { return alternatives.keySet(); }
    public List<ShownDocument> documents() { return documents; }

    boolean matches(String id, String version, String hash) {
        return alternatives.getOrDefault(id, List.of()).stream()
                .anyMatch(document -> document.version().equals(version) && document.contentHash().equals(hash));
    }

    private record Revision(String version, String locale) { }
}
