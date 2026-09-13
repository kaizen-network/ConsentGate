package io.github.consentgate.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SqliteAcceptanceRepositoryTest {
    @TempDir Path directory;

    @Test void revisionValidationIsReadOnlyAndRejectsHistoricalContentChanges() throws Exception {
        Path database = directory.resolve("validate.db");
        var original = shown("rules", "v1", "hash-one", "snapshot-one");
        try (var repository = new SqliteAcceptanceRepository(database)) {
            repository.validateRevisions("main", List.of(original));
            assertEquals(0, count(database, "cg_document_revisions"));
            repository.grant(UUID.randomUUID(), "main", List.of(original), UUID.randomUUID(), Instant.now(), "in-game");
            repository.validateRevisions("main", List.of(original));
            assertThrows(SQLException.class, () -> repository.validateRevisions("main",
                    List.of(shown("rules", "v1", "hash-two", "snapshot-two"))));
            repository.validateRevisions("other", List.of(shown("rules", "v1", "hash-two", "snapshot-two")));
            repository.validateRevisions("main", List.of(shown("rules", "v2", "hash-two", "snapshot-two")));
            assertEquals(1, count(database, "cg_document_revisions"));
            assertEquals(1, count(database, "cg_acceptance_events"));
        }
    }

    @Test void grantIsAtomicCurrentAndIdempotent() throws Exception {
        Path database = directory.resolve("consent.db");
        var player = UUID.randomUUID();
        var request = UUID.randomUUID();
        var first = shown("rules", "v1", "hash-one", "snapshot-one");
        var second = shown("privacy", "v3", "hash-two", "snapshot-two");
        try (var repository = new SqliteAcceptanceRepository(database)) {
            assertFalse(repository.isAccepted(player, "main", List.of(first, second)));
            repository.grant(player, "main", List.of(first, second), request, Instant.parse("2026-09-10T00:00:00Z"), "in-game");
            repository.grant(player, "main", List.of(first, second), request, Instant.parse("2026-09-10T00:00:00Z"), "in-game");
            assertTrue(repository.isAccepted(player, "main", List.of(first, second)));
            assertFalse(repository.isAccepted(player, "main", List.of(shown("rules", "v2", "hash-new", "new"), second)));
        }
        assertEquals(2, count(database, "cg_acceptance_events"));
        assertEquals(1, count(database, "cg_audit_events"));
    }

    @Test void withdrawalWinsOverAnOlderGrant() throws Exception {
        Path database = directory.resolve("consent.db");
        var player = UUID.randomUUID();
        var shown = shown("rules", "v1", "hash", "snapshot");
        try (var repository = new SqliteAcceptanceRepository(database)) {
            repository.grant(player, "main", List.of(shown), UUID.randomUUID(), Instant.parse("2026-09-10T00:00:00Z"), "in-game");
            repository.withdraw(player, "main", List.of("rules"), UUID.randomUUID(), Instant.parse("2026-09-10T01:00:00Z"), "command");
            assertThrows(SQLException.class, () -> repository.grant(player, "main", List.of(shown), UUID.randomUUID(), Instant.parse("2026-09-10T00:30:00Z"), "import"));
            assertFalse(repository.isAccepted(player, "main", List.of(shown)));
        }
    }

    @Test void laterFractionalDecisionWinsWithinTheSameSecond() throws Exception {
        Path database = directory.resolve("consent.db");
        var player = UUID.randomUUID();
        var shown = shown("rules", "v1", "hash", "snapshot");
        try (var repository = new SqliteAcceptanceRepository(database)) {
            repository.grant(player, "main", List.of(shown), UUID.randomUUID(),
                    Instant.parse("2026-09-10T00:00:00Z"), "in-game");
            repository.withdraw(player, "main", List.of("rules"), UUID.randomUUID(),
                    Instant.parse("2026-09-10T00:00:00.100Z"), "command");
            assertFalse(repository.isAccepted(player, "main", List.of(shown)));
        }
    }

    @Test void changedContentUnderSameVersionRollsBackEverything() throws Exception {
        Path database = directory.resolve("consent.db");
        var player = UUID.randomUUID();
        try (var repository = new SqliteAcceptanceRepository(database)) {
            repository.grant(player, "main", List.of(shown("rules", "v1", "hash-one", "one")),
                    UUID.randomUUID(), Instant.parse("2026-09-10T00:00:00Z"), "in-game");
            assertThrows(SQLException.class, () -> repository.grant(player, "main",
                    List.of(shown("privacy", "v1", "hash-ok", "ok"), shown("rules", "v1", "hash-two", "two")),
                    UUID.randomUUID(), Instant.parse("2026-09-10T01:00:00Z"), "in-game"));
            assertFalse(repository.isAccepted(player, "main", List.of(shown("privacy", "v1", "hash-ok", "ok"))));
        }
        assertEquals(1, count(database, "cg_acceptance_events"));
    }

    @Test void reusedRequestWithDifferentDataFails() throws Exception {
        Path database = directory.resolve("consent.db");
        var request = UUID.randomUUID();
        try (var repository = new SqliteAcceptanceRepository(database)) {
            repository.grant(UUID.randomUUID(), "main", List.of(shown("rules", "v1", "hash", "snapshot")),
                    request, Instant.parse("2026-09-10T00:00:00Z"), "in-game");
            assertThrows(SQLException.class, () -> repository.grant(UUID.randomUUID(), "main",
                    List.of(shown("rules", "v1", "hash", "snapshot")), request,
                    Instant.parse("2026-09-10T00:00:00Z"), "in-game"));
        }
    }

    @Test void reusedRequestWithChangedDocumentSetRollsBack() throws Exception {
        Path database = directory.resolve("consent.db");
        var player = UUID.randomUUID();
        var request = UUID.randomUUID();
        var rules = shown("rules", "v1", "rules", "rules");
        var privacy = shown("privacy", "v1", "privacy", "privacy");
        try (var repository = new SqliteAcceptanceRepository(database)) {
            repository.grant(player, "main", List.of(rules), request,
                    Instant.parse("2026-09-10T00:00:00Z"), "in-game");
            assertThrows(SQLException.class, () -> repository.grant(player, "main", List.of(rules, privacy),
                    request, Instant.parse("2026-09-10T00:00:00Z"), "in-game"));
            assertFalse(repository.isAccepted(player, "main", List.of(privacy)));
        }
        assertEquals(1, count(database, "cg_acceptance_events"));
    }

    @Test void replayedEmptyWithdrawalDoesNotRevokeALaterGrant() throws Exception {
        Path database = directory.resolve("consent.db");
        var player = UUID.randomUUID();
        var withdrawalRequest = UUID.randomUUID();
        var shown = shown("rules", "v1", "rules", "rules");
        Instant withdrawalTime = Instant.parse("2026-09-10T00:00:00Z");
        try (var repository = new SqliteAcceptanceRepository(database)) {
            repository.withdraw(player, "main", List.of("rules"), withdrawalRequest, withdrawalTime, "command");
            repository.grant(player, "main", List.of(shown), UUID.randomUUID(),
                    Instant.parse("2026-09-10T01:00:00Z"), "in-game");
            assertThrows(SQLException.class, () -> repository.withdraw(player, "main", List.of("rules"), withdrawalRequest, withdrawalTime, "command"));
            assertTrue(repository.isAccepted(player, "main", List.of(shown)));
        }
        assertEquals(1, count(database, "cg_acceptance_events"));
        assertEquals(2, count(database, "cg_audit_events"));
    }

    @Test void withdrawalWithoutExistingStateBlocksOlderAndEqualTimeGrants() throws Exception {
        Path database = directory.resolve("empty-reset.db");
        var player = UUID.randomUUID();
        var document = shown("rules", "v1", "hash", "text");
        Instant at = Instant.parse("2026-09-10T01:00:00Z");
        try (var repository = new SqliteAcceptanceRepository(database)) {
            repository.withdraw(player, "main", List.of("rules"), UUID.randomUUID(), at, "admin-reset");
            for (var attempt : List.of(at.minusSeconds(1), at)) {
                assertThrows(SQLException.class, () -> repository.grant(player, "main", List.of(document), UUID.randomUUID(), attempt, "in-game"));
            }
            assertEquals(0, count(database, "cg_acceptance_events"));
            assertEquals(0, count(database, "cg_document_revisions"));
            repository.grant(player, "main", List.of(document), UUID.randomUUID(), at.plusNanos(1), "in-game");
            assertTrue(repository.isAccepted(player, "main", List.of(document)));
        }
    }

    @Test void equalTimestampAndReplayedGrantsCannotReportSuccessAfterReset() throws Exception {
        Path database = directory.resolve("replay-reset.db");
        var player = UUID.randomUUID();
        var request = UUID.randomUUID();
        var document = shown("rules", "v1", "hash", "text");
        Instant at = Instant.parse("2026-09-10T01:00:00Z");
        try (var repository = new SqliteAcceptanceRepository(database)) {
            repository.grant(player, "main", List.of(document), request, at, "in-game");
            repository.withdraw(player, "main", List.of("rules"), UUID.randomUUID(), at.plusSeconds(1), "admin-reset");
            assertThrows(SQLException.class, () -> repository.grant(player, "main", List.of(document), request, at, "in-game"));
            assertThrows(SQLException.class, () -> repository.grant(player, "main", List.of(document), UUID.randomUUID(), at.plusSeconds(1), "in-game"));
            assertFalse(repository.isAccepted(player, "main", List.of(document)));
            assertEquals(2, count(database, "cg_acceptance_events"));
        }
    }

    @Test void clockSkewCannotMakeAnUnappliedResetReportSuccess() throws Exception {
        Path database = directory.resolve("clock-reset.db");
        var player = UUID.randomUUID();
        var document = shown("rules", "v1", "hash", "text");
        Instant at = Instant.parse("2026-09-10T01:00:00Z");
        try (var repository = new SqliteAcceptanceRepository(database)) {
            repository.grant(player, "main", List.of(document), UUID.randomUUID(), at, "in-game");
            assertThrows(SQLException.class, () -> repository.withdraw(player, "main", List.of("rules"), UUID.randomUUID(), at.minusSeconds(1), "admin-reset"));
            assertTrue(repository.isAccepted(player, "main", List.of(document)));
            assertEquals(1, count(database, "cg_audit_events"));
            assertEquals(1, count(database, "cg_acceptance_events"));
            repository.withdraw(player, "main", List.of("rules"), UUID.randomUUID(), at, "admin-reset");
            assertFalse(repository.isAccepted(player, "main", List.of(document)));
        }
    }

    @Test void existingVersionOneFilesKeepHistoryAndClosedBackupsRestoreAcceptance() throws Exception {
        Path database = directory.resolve("existing-v1.db");
        Path backup = directory.resolve("backup.db");
        var player = UUID.randomUUID();
        var missing = UUID.randomUUID();
        var document = shown("rules", "v1", "hash", "text");
        Instant at = Instant.parse("2026-09-10T01:00:00Z");
        try (var repository = new SqliteAcceptanceRepository(database)) {
            repository.grant(player, "main", List.of(document), UUID.randomUUID(), at, "in-game");
            repository.withdraw(missing, "main", List.of("rules"), UUID.randomUUID(), at, "admin-reset");
        }
        // Earlier builds used the same version 1 tables without this optional lookup index.
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.execute("DROP INDEX cg_audit_events_player");
        }
        java.nio.file.Files.copy(database, backup);
        try (var repository = new SqliteAcceptanceRepository(database)) {
            assertTrue(repository.isAccepted(player, "main", List.of(document)));
            assertEquals(1, count(database, "cg_document_revisions"));
            assertEquals(1, count(database, "cg_acceptance_events"));
            assertEquals(2, count(database, "cg_audit_events"));
            repository.withdraw(player, "main", List.of("rules"), UUID.randomUUID(), at.plusSeconds(1), "admin-reset");
            assertFalse(repository.isAccepted(player, "main", List.of(document)));
        }
        try (var restored = new SqliteAcceptanceRepository(backup)) {
            assertTrue(restored.isAccepted(player, "main", List.of(document)));
            assertThrows(SQLException.class, () -> restored.grant(missing, "main", List.of(document), UUID.randomUUID(), at, "in-game"));
            assertEquals(2, count(backup, "cg_audit_events"));
            assertEquals(1, count(backup, "cg_schema_history"));
        }
    }

    @Test void anEmptyResetAffectsOnlyItsExactPlayerScopeAndDocumentIds() throws Exception {
        Path database = directory.resolve("reset-scope.db");
        var player = UUID.randomUUID();
        var rules = shown("rules", "v1", "hash", "text");
        var other = shown("rules-other", "v1", "other", "other");
        Instant at = Instant.parse("2026-09-10T01:00:00Z");
        try (var repository = new SqliteAcceptanceRepository(database)) {
            repository.withdraw(player, "main", List.of("rules"), UUID.randomUUID(), at, "admin-reset");
            repository.grant(player, "main", List.of(other), UUID.randomUUID(), at, "in-game");
            repository.grant(player, "other", List.of(rules), UUID.randomUUID(), at, "in-game");
            repository.grant(UUID.randomUUID(), "main", List.of(rules), UUID.randomUUID(), at, "in-game");
            assertTrue(repository.isAccepted(player, "main", List.of(other)));
            assertTrue(repository.isAccepted(player, "other", List.of(rules)));
            assertThrows(SQLException.class, () -> repository.grant(player, "main", List.of(rules), UUID.randomUUID(), at, "in-game"));
        }
    }

    private static ShownDocument shown(String id, String version, String hash, String snapshot) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(hash.getBytes(StandardCharsets.UTF_8)));
            return new ShownDocument(id, version, "en-US", digest, "Title", snapshot);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    private static int count(Path database, String table) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }
}
