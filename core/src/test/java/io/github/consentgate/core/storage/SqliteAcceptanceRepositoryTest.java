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
            repository.grant(player, "main", List.of(shown), UUID.randomUUID(), Instant.parse("2026-09-10T00:30:00Z"), "import");
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
            repository.withdraw(player, "main", List.of("rules"), withdrawalRequest, withdrawalTime, "command");
            assertTrue(repository.isAccepted(player, "main", List.of(shown)));
        }
        assertEquals(1, count(database, "cg_acceptance_events"));
        assertEquals(2, count(database, "cg_audit_events"));
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
