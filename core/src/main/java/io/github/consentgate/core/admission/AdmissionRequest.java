package io.github.consentgate.core.admission;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AdmissionRequest(UUID playerId, UUID requestId, List<AdmissionDocument> documents) {
    public AdmissionRequest {
        playerId = Objects.requireNonNull(playerId, "playerId");
        requestId = Objects.requireNonNull(requestId, "requestId");
        documents = List.copyOf(documents);
        if (documents.isEmpty()) throw new IllegalArgumentException("Admission request needs documents");
    }
}
