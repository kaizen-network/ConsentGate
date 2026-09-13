package io.github.consentgate.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.consentgate.core.storage.StorageFixtures.variant;

class AcceptanceReadTest {
    @TempDir Path directory;

    @Test void oneQueryMatchesMaximumCatalogAndReportsMissingWithdrawnOrChangedDocuments() throws Exception {
        var database = directory.resolve("batch.db");
        var player = UUID.randomUUID();
        var variants = new ArrayList<ShownDocument>();
        var selected = new ArrayList<ShownDocument>();
        for (int document = 0; document < 32; document++) {
            for (int locale = 0; locale < 32; locale++) {
                var shown = variant("rules" + document, "v1", "en-x" + locale);
                variants.add(shown);
                if (locale == 31) selected.add(shown);
            }
        }
        var requirements = new AcceptanceRequirements(variants);
        var queries = new AtomicInteger();
        try (var repository = new SqliteAcceptanceRepository(database)) {
            repository.grant(player, "main", selected, UUID.randomUUID(), Instant.EPOCH, "test");
            try (var actual = DriverManager.getConnection("jdbc:sqlite:" + database)) {
                var counted = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("prepareStatement")) queries.incrementAndGet();
                            try { return method.invoke(actual, args); }
                            catch (InvocationTargetException ex) { throw ex.getCause(); }
                        });
                assertEquals(requirements.documentIds(), AcceptanceRead.accepted(counted, player, "main", requirements));
                assertEquals(1, queries.get());
            }
            assertTrue(repository.acceptedDocuments(UUID.randomUUID(), "main", requirements).isEmpty());
            assertTrue(repository.acceptedDocuments(player, "other", requirements).isEmpty());
            repository.withdraw(player, "main", List.of("rules0"), UUID.randomUUID(), Instant.EPOCH.plusSeconds(1), "test");
            var remaining = new java.util.HashSet<>(requirements.documentIds());
            remaining.remove("rules0");
            assertEquals(remaining, repository.acceptedDocuments(player, "main", requirements));
            assertTrue(repository.acceptedDocuments(player, "main", new AcceptanceRequirements(List.of(
                    variant("rules1", "v2", "en-x31"), variant("rules2", "v1", "fr")))).isEmpty());
        }
    }

    @Test void rejectsOversizedOrConflictingRequirementsBeforeAnyDatabaseWork() {
        var variant = variant("rules", "v1", "en-US");
        assertThrows(IllegalArgumentException.class, () -> new AcceptanceRequirements(java.util.Collections.nCopies(1025, variant)));
        var documents = new ArrayList<ShownDocument>();
        var translations = new ArrayList<ShownDocument>();
        for (int index = 0; index < 33; index++) {
            documents.add(variant("rules" + index, "v1", "en-US"));
            translations.add(variant("rules", "v1", "en-x" + index));
        }
        assertThrows(IllegalArgumentException.class, () -> new AcceptanceRequirements(documents));
        assertThrows(IllegalArgumentException.class, () -> new AcceptanceRequirements(translations));
        var conflicting = new ShownDocument(variant.documentId(), variant.version(), variant.locale(), "0".repeat(64), variant.title(), "Changed");
        assertThrows(IllegalArgumentException.class, () -> new AcceptanceRequirements(List.of(variant, conflicting)));
        assertThrows(IllegalArgumentException.class, () -> AcceptanceRequirements.exact(List.of(variant, variant("rules", "v1", "fr"))));
        assertEquals(Set.of("rules"), AcceptanceRequirements.exact(List.of(variant, variant)).documentIds());
    }

}
