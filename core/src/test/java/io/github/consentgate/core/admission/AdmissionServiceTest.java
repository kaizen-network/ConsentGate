package io.github.consentgate.core.admission;

import io.github.consentgate.core.config.ConsentGateConfig;
import io.github.consentgate.core.document.DocumentCatalog;
import io.github.consentgate.core.document.DocumentPage;
import io.github.consentgate.core.document.DocumentRevision;
import io.github.consentgate.core.document.DocumentTranslation;
import io.github.consentgate.core.storage.AcceptanceRepository;
import io.github.consentgate.core.storage.AcceptanceRequirements;
import io.github.consentgate.core.storage.ShownDocument;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdmissionServiceTest {
    @org.junit.jupiter.api.io.TempDir Path databaseDirectory;

    @Test void remoteConnectionAndCacheChangesRequireRestart() throws Exception {
        var original = config(true, true);
        try (var service = new AdmissionService(original, catalog(), new io.github.consentgate.core.storage.SqliteAcceptanceRepository(databaseDirectory.resolve("restart.db")))) {
            var storage = new io.github.consentgate.core.config.StorageConfig("mariadb", original.sqliteFile(),
                    new io.github.consentgate.core.config.RemoteStorageConfig("localhost", 3306, "example", "example", "test-value", "verify-full", null, 3000, 5000),
                    original.storage().cache());
            var candidate = new ConsentGateConfig(original.enabled(), original.scope(), original.timeoutSeconds(), original.maxPending(),
                    original.defaultLocale(), original.useClientLocale(), original.languageSelector(), original.appearance(), original.nativeBedrockForms(),
                    original.bedrockButtonColor(), original.documentsDirectory(), storage);
            assertThrows(IllegalArgumentException.class, () -> service.reconfigured(candidate, catalog()));
        }
    }

    @Test void adminResetPreservesHistoryAndRequiresFreshAcceptance() throws Exception {
        UUID player = UUID.randomUUID();
        Path database = databaseDirectory.resolve("admin.db");
        var repository = new io.github.consentgate.core.storage.SqliteAcceptanceRepository(database);
        try (var service = new AdmissionService(config(true, true), catalog(), repository)) {
            assertFalse(service.status(player).getFirst().accepted());
            var session = new AdmissionSession(service.checkExactLocale(player, "id-ID").orElseThrow());
            assertTrue(session.accept(session.token(), Map.of("rules", true)));
            service.grant(player, session, Instant.parse("2026-09-10T00:00:00Z"), "in-game");
            assertEquals(new AdmissionService.DocumentStatus("rules", "v1", true), service.status(player).getFirst());
            service.reset(player, Instant.parse("2026-09-10T01:00:00Z"));
            assertFalse(service.status(player).getFirst().accepted());
            assertTrue(service.check(player, "en-US").isPresent());
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT decision, method FROM cg_acceptance_events ORDER BY decided_at")) {
                assertTrue(rows.next());
                assertEquals("granted", rows.getString(1));
                assertTrue(rows.next());
                assertEquals("withdrawn", rows.getString(1));
                assertEquals("admin-reset", rows.getString(2));
                assertFalse(rows.next());
            }
            var fresh = new AdmissionSession(service.checkExactLocale(player, "en-US").orElseThrow());
            assertTrue(fresh.accept(fresh.token(), Map.of("rules", true)));
            service.grant(player, fresh, Instant.parse("2026-09-10T02:00:00Z"), "in-game");
            assertTrue(service.status(player).getFirst().accepted());
        }
    }

    @Test void acceptedTranslationSurvivesClientLanguageChangeButNotWithdrawalOrContentChange() throws Exception {
        UUID player = UUID.randomUUID();
        var repository = new io.github.consentgate.core.storage.SqliteAcceptanceRepository(databaseDirectory.resolve("acceptance.db"));
        try (var service = new AdmissionService(config(true, true), catalog(), repository)) {
            var session = new AdmissionSession(service.checkExactLocale(player, "id-ID").orElseThrow());
            assertTrue(session.accept(session.token(), Map.of("rules", true)));
            service.grant(player, session, Instant.now(), "in-game");
            assertTrue(service.check(player, "en-US").isEmpty());
            assertTrue(service.check(player, null).isEmpty());
            var changed = new DocumentCatalog(List.of(new DocumentRevision("rules", "v2", true, 10,
                    Map.of("en-US", translation("Rules", "c"), "id-ID", translation("Peraturan", "d")))));
            var changedService = new AdmissionService(config(true, true), changed, repository);
            assertTrue(changedService.check(player, "en-US").isPresent());
            var edited = new DocumentCatalog(List.of(new DocumentRevision("rules", "v1", true, 10,
                    Map.of("en-US", translation("Rules", "c"), "id-ID", translation("Peraturan", "d")))));
            assertTrue(new AdmissionService(config(true, true), edited, repository).check(player, "en-US").isPresent());
            repository.withdraw(player, "main", List.of("rules"), UUID.randomUUID(), Instant.now(), "test");
            assertTrue(service.check(player, "en-US").isPresent());
        }
    }
    @Test void disabledGateAdmitsWithoutStorageAccess() throws Exception {
        var repository = new RecordingRepository();
        try (var service = new AdmissionService(config(false, true), catalog(), repository)) {
            assertTrue(service.check(UUID.randomUUID(), "id-ID").isEmpty());
            assertEquals(0, repository.checks);
        }
    }

    @Test void previewDoesNotCheckStorageAndCannotGrantEvenWithEveryBoxSelected() throws Exception {
        var repository = new RecordingRepository();
        repository.accepted = true;
        var player = UUID.randomUUID();
        try (var service = new AdmissionService(config(true, true), catalog(), repository)) {
            var preview = service.preview(player, "id-ID");
            assertTrue(preview.preview());
            assertEquals("id-ID", preview.documents().getFirst().locale());
            var session = new AdmissionSession(preview);
            assertTrue(session.accept(session.token(), Map.of("rules", true)));
            assertThrows(IllegalArgumentException.class, () -> service.grant(player, session, Instant.now(), "in-game"));
            assertEquals(0, repository.checks);
            assertNull(repository.granted);
        }
    }

    @Test void currentAcceptanceNeedsNoRequest() throws Exception {
        var repository = new RecordingRepository();
        repository.accepted = true;
        try (var service = new AdmissionService(config(true, true), catalog(), repository)) {
            assertTrue(service.check(UUID.randomUUID(), "id-ID").isEmpty());
            assertEquals(Set.of("en-US", "id-ID"), repository.required.stream().map(ShownDocument::locale)
                    .collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test void maximumCatalogUsesOneBatchForAdmissionAndOneAuthoritativeBatchForStatus() throws Exception {
        var translations = new java.util.LinkedHashMap<String, DocumentTranslation>();
        for (int locale = 0; locale < 32; locale++) {
            translations.put("en-x" + locale, translation("Rules", "a"));
        }
        var documents = new java.util.ArrayList<DocumentRevision>();
        for (int index = 0; index < 32; index++) {
            documents.add(new DocumentRevision("rules" + index, "v1", true, index, translations));
        }
        var repository = new RecordingRepository();
        repository.accepted = true;
        try (var service = new AdmissionService(config(true, true), new DocumentCatalog(documents), repository)) {
            var player = UUID.randomUUID();
            assertTrue(service.check(player, "en-x31").isEmpty());
            assertEquals(1, repository.checks);
            assertEquals(1024, repository.required.size());
            assertEquals(0, repository.authoritativeChecks);
            assertEquals(32, service.status(player).stream().filter(AdmissionService.DocumentStatus::accepted).count());
            assertEquals(2, repository.checks);
            assertEquals(1, repository.authoritativeChecks);
        }
    }

    @Test void changedClientLanguageUsesFreshCompleteCacheWhileStatusStillNeedsPrimary() throws Exception {
        var primary = new RecordingRepository();
        primary.accepted = true;
        var cacheConfig = new io.github.consentgate.core.config.StorageConfig.Cache(true, databaseDirectory.resolve("locale-cache.db"), 60, 10);
        var cache = new io.github.consentgate.core.storage.CachedAcceptanceRepository(primary, cacheConfig, "source",
                java.time.Clock.systemUTC(), ignored -> { });
        try (var service = new AdmissionService(config(true, true), catalog(), cache)) {
            var player = UUID.randomUUID();
            assertTrue(service.check(player, "en-US").isEmpty());
            assertEquals(1, primary.checks);
            primary.offline = true;
            assertTrue(service.check(player, "id-ID").isEmpty());
            assertEquals(1, primary.checks);
            assertThrows(SQLException.class, () -> service.status(player));
            assertThrows(SQLException.class, () -> service.check(UUID.randomUUID(), "id-ID"));
        }
    }

    @Test void requestRecordsTheSelectedVariantOnGrant() throws Exception {
        var repository = new RecordingRepository();
        UUID player = UUID.randomUUID();
        try (var service = new AdmissionService(config(true, true), catalog(), repository)) {
            AdmissionRequest request = service.check(player, "ID_id").orElseThrow();
            assertEquals("Peraturan", request.documents().getFirst().title());
            assertEquals("id-ID", request.documents().getFirst().locale());
            var session = new AdmissionSession(request);
            assertTrue(session.accept(session.token(), Map.of("rules", true)));
            service.grant(player, session, Instant.parse("2026-09-10T00:00:00Z"), "in-game");
            assertEquals(request.requestId(), repository.requestId);
            assertEquals("id-ID", repository.granted.getFirst().locale());
        }
    }

    @Test void refusesUnacceptedAndCrossPlayerSessions() throws Exception {
        var repository = new RecordingRepository();
        UUID player = UUID.randomUUID();
        try (var service = new AdmissionService(config(true, true), catalog(), repository)) {
            var session = new AdmissionSession(service.check(player, "en-US").orElseThrow());
            assertThrows(IllegalStateException.class, () -> service.grant(player, session, Instant.now(), "in-game"));
            assertTrue(session.accept(session.token(), Map.of("rules", true)));
            assertThrows(IllegalArgumentException.class,
                    () -> service.grant(UUID.randomUUID(), session, Instant.now(), "in-game"));
            assertNull(repository.granted);
        }
    }

    @Test void configuredDefaultIgnoresClientLocale() throws Exception {
        var repository = new RecordingRepository();
        try (var service = new AdmissionService(config(true, false), catalog(), repository)) {
            AdmissionRequest request = service.check(UUID.randomUUID(), "id-ID").orElseThrow();
            assertEquals("en-US", request.documents().getFirst().locale());
        }
    }

    @Test void exactLocaleCheckIgnoresClientLocaleSetting() throws Exception {
        var repository = new RecordingRepository();
        try (var service = new AdmissionService(config(true, false), catalog(), repository)) {
            AdmissionRequest request = service.checkExactLocale(UUID.randomUUID(), "id-ID").orElseThrow();
            assertEquals("id-ID", request.documents().getFirst().locale());
        }
    }

    private static ConsentGateConfig config(boolean enabled, boolean useClientLocale) {
        return new ConsentGateConfig(enabled, "main", 300, 128, "en-US", useClientLocale,
                Path.of("documents"), Path.of("data/consent.db"));
    }

    private static DocumentCatalog catalog() {
        var english = translation("Rules", "a");
        var indonesian = translation("Peraturan", "b");
        return new DocumentCatalog(List.of(new DocumentRevision("rules", "v1", true, 10,
                Map.of("en-US", english, "id-ID", indonesian))));
    }

    private static DocumentTranslation translation(String title, String hashCharacter) {
        return new DocumentTranslation(title, "Summary", "I agree", "Read",
                List.of(new DocumentPage("Page", "Text")), hashCharacter.repeat(64));
    }

    private static final class RecordingRepository implements AcceptanceRepository {
        @Override public void validateRevisions(String scope, Collection<ShownDocument> documents) { }
        boolean accepted;
        boolean offline;
        int checks;
        int authoritativeChecks;
        List<ShownDocument> required;
        List<ShownDocument> granted;
        UUID requestId;

        @Override public Set<String> acceptedDocuments(UUID playerId, String scope, AcceptanceRequirements required) throws SQLException {
            checks++;
            if (offline) throw new SQLException("Simulated unavailable primary");
            this.required = required.documents();
            return accepted ? required.documentIds() : Set.of();
        }

        @Override public Set<String> acceptedDocumentsAuthoritatively(UUID playerId, String scope, AcceptanceRequirements required) throws SQLException {
            authoritativeChecks++;
            return acceptedDocuments(playerId, scope, required);
        }

        @Override public void grant(UUID playerId, String scope, List<ShownDocument> shown, UUID requestId,
                                    Instant decidedAt, String method) {
            granted = List.copyOf(shown);
            this.requestId = requestId;
        }

        @Override public void withdraw(UUID playerId, String scope, Collection<String> documentIds,
                                       UUID requestId, Instant decidedAt, String method) throws SQLException { }
    }
}
