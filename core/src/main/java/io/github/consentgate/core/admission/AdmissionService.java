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
    public DocumentCatalog catalog() { return catalog; }

    public AdmissionService reconfigured(ConsentGateConfig candidate, DocumentCatalog documents) throws SQLException {
        if (config.enabled() != candidate.enabled() || !config.scope().equals(candidate.scope())
                || !config.storage().equals(candidate.storage()) || config.maxPending() != candidate.maxPending()) {
            throw new IllegalArgumentException("Changes to enabled, scope, storage, or max-pending require a restart");
        }
        var revisions = new java.util.ArrayList<ShownDocument>();
        for (var revision : documents.documents()) {
            for (String locale : revision.translations().keySet()) {
                var shown = ShownDocument.from(revision, locale, candidate.defaultLocale());
                for (var active : catalog.documents()) {
                    if (active.id().equals(revision.id()) && active.version().equals(revision.version())
                            && active.translations().containsKey(locale)
                            && !ShownDocument.from(active, locale, config.defaultLocale()).equals(shown)) {
                        throw new IllegalArgumentException("Document content changed without a version bump: " + revision.id());
                    }
                }
                revisions.add(shown);
            }
        }
        repository.validateRevisions(config.scope(), revisions);
        return new AdmissionService(candidate, documents, repository);
    }

    public record DocumentStatus(String id, String version, boolean accepted) { }

    public List<DocumentStatus> status(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        var result = new java.util.ArrayList<DocumentStatus>();
        for (var revision : catalog.required()) {
            boolean accepted = false;
            for (String locale : revision.translations().keySet()) {
                if (repository.isAcceptedAuthoritatively(playerId, config.scope(),
                        List.of(ShownDocument.from(revision, locale, config.defaultLocale())))) {
                    accepted = true;
                    break;
                }
            }
            result.add(new DocumentStatus(revision.id(), revision.version(), accepted));
        }
        return List.copyOf(result);
    }

    public void reset(UUID playerId, Instant decidedAt) throws SQLException {
        if (!config.enabled()) throw new IllegalStateException("ConsentGate is disabled");
        repository.withdraw(playerId, config.scope(), catalog.required().stream().map(revision -> revision.id()).toList(),
                UUID.randomUUID(), decidedAt, "admin-reset");
    }

    public Optional<AdmissionRequest> check(UUID playerId, String clientLocale) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        if (!config.enabled()) return Optional.empty();
        String requestedLocale = config.useClientLocale() && clientLocale != null && !clientLocale.isBlank()
                ? clientLocale : config.defaultLocale();
        var request = checkSelectedLocale(playerId, requestedLocale);
        if (request.isEmpty()) return request;
        // A previously accepted translation remains valid regardless of the client language.
        for (var revision : catalog.required()) {
            boolean accepted = false;
            for (String locale : revision.translations().keySet()) {
                if (repository.isAccepted(playerId, config.scope(),
                        List.of(ShownDocument.from(revision, locale, config.defaultLocale())))) {
                    accepted = true;
                    break;
                }
            }
            if (!accepted) return request;
        }
        return Optional.empty();
    }

    public Optional<AdmissionRequest> checkExactLocale(UUID playerId, String locale) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        return checkSelectedLocale(playerId, io.github.consentgate.core.document.LocaleTag.normalize(locale));
    }

    private Optional<AdmissionRequest> checkSelectedLocale(UUID playerId, String requestedLocale) throws SQLException {
        List<AdmissionDocument> documents = documents(requestedLocale);
        List<ShownDocument> shown = documents.stream().map(AdmissionDocument::shown).toList();
        if (repository.isAccepted(playerId, config.scope(), shown)) return Optional.empty();
        return Optional.of(new AdmissionRequest(playerId, UUID.randomUUID(), documents));
    }

    /** Builds the real presentation without reading or writing acceptance records. */
    public AdmissionRequest preview(UUID playerId, String locale) {
        return new AdmissionRequest(playerId, UUID.randomUUID(), documents(locale), true);
    }

    private List<AdmissionDocument> documents(String requestedLocale) {
        return catalog.required().stream().map(revision -> {
            var selected = revision.selectTranslation(requestedLocale, config.defaultLocale());
            var translation = selected.translation();
            ShownDocument shown = ShownDocument.from(revision, selected.locale(), config.defaultLocale());
            return new AdmissionDocument(revision.id(), revision.version(), selected.locale(), translation.title(),
                    translation.summary(), translation.checkbox(), translation.readButton(), translation.pages(), shown);
        }).toList();
    }

    public void grant(UUID playerId, AdmissionSession session, Instant decidedAt, String method) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(session, "session");
        AdmissionRequest request = session.request();
        if (request.preview()) throw new IllegalArgumentException("A preview cannot save acceptance");
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
