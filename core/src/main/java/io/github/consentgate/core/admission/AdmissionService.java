package io.github.consentgate.core.admission;

import io.github.consentgate.core.config.ConsentGateConfig;
import io.github.consentgate.core.document.DocumentCatalog;
import io.github.consentgate.core.storage.AcceptanceRepository;
import io.github.consentgate.core.storage.ShownDocument;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AdmissionService implements AutoCloseable {
    private final ConsentGateConfig config;
    private final DocumentCatalog catalog;
    private final AcceptanceRepository repository;

    public AdmissionService(ConsentGateConfig config, DocumentCatalog catalog, AcceptanceRepository repository) {
        this.config = Objects.requireNonNull(config, "config");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.repository = Objects.requireNonNull(repository, "repository");
        if (config.enabled() && catalog.required().isEmpty()) {
            throw new IllegalArgumentException("An enabled gate needs at least one required document");
        }
    }

    public ConsentGateConfig config() { return config; }

    public Optional<AdmissionRequest> check(UUID playerId, String clientLocale) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        if (!config.enabled()) return Optional.empty();
        String requestedLocale = config.useClientLocale() && clientLocale != null && !clientLocale.isBlank()
                ? clientLocale : config.defaultLocale();
        List<AdmissionDocument> documents = catalog.required().stream().map(revision -> {
            var selected = revision.selectTranslation(requestedLocale, config.defaultLocale());
            var translation = selected.translation();
            ShownDocument shown = ShownDocument.from(revision, selected.locale(), config.defaultLocale());
            return new AdmissionDocument(revision.id(), revision.version(), selected.locale(), translation.title(),
                    translation.summary(), translation.checkbox(), translation.readButton(), translation.pages(), shown);
        }).toList();
        List<ShownDocument> shown = documents.stream().map(AdmissionDocument::shown).toList();
        if (repository.isAccepted(playerId, config.scope(), shown)) return Optional.empty();
        return Optional.of(new AdmissionRequest(playerId, UUID.randomUUID(), documents));
    }

    public void grant(UUID playerId, AdmissionSession session, Instant decidedAt, String method) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(session, "session");
        AdmissionRequest request = session.request();
        if (!playerId.equals(request.playerId())) throw new IllegalArgumentException("Admission request belongs to another player");
        if (!session.accepted()) throw new IllegalStateException("Admission session is not accepted");
        validateCurrentRequest(request);
        List<ShownDocument> shown = request.documents().stream().map(AdmissionDocument::shown).toList();
        repository.grant(playerId, config.scope(), shown, request.requestId(), decidedAt, method);
    }

    private void validateCurrentRequest(AdmissionRequest request) {
        var required = catalog.required();
        if (request.documents().size() != required.size()) throw new IllegalArgumentException("Admission request is incomplete");
        for (var revision : required) {
            AdmissionDocument presented = request.documents().stream()
                    .filter(document -> document.id().equals(revision.id())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Admission request is missing " + revision.id()));
            ShownDocument expected = ShownDocument.from(revision, presented.locale(), config.defaultLocale());
            if (!expected.equals(presented.shown())) {
                throw new IllegalArgumentException("Admission request contains a stale or unknown revision: " + revision.id());
            }
        }
    }

    @Override public void close() throws SQLException { repository.close(); }
}
