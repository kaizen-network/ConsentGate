package io.github.consentgate.core.storage;

import io.github.consentgate.core.config.StorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CachedAcceptanceRepositoryTest {
    @TempDir Path directory;
    final UUID player = UUID.randomUUID();
    final ShownDocument document = RemoteAcceptanceRepositoryTest.shown("rules", "v1", "example");
    final MutableClock clock = new MutableClock();
    final Primary primary = new Primary();
    StorageConfig.Cache config(int ttl, int capacity) { return new StorageConfig.Cache(true, directory.resolve("cache.db"), ttl, capacity); }
    CachedAcceptanceRepository cache(int ttl, int capacity) { return new CachedAcceptanceRepository(primary, config(ttl, capacity), "source", clock, ignored -> { }); }

    @Test void hitsNeverExtendFreshnessAndExpiryFailsClosed() throws Exception {
        try (var cache = cache(60, 10)) {
            assertTrue(cache.isAccepted(player, "main", List.of(document)));
            primary.offline = true;
            clock.advance(59); assertTrue(cache.isAccepted(player, "main", List.of(document)));
            clock.advance(1); assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
            assertEquals(2, primary.reads);
        }
    }

    @Test void persistentCacheKeepsOriginalVerificationTime() throws Exception {
        try (var cache = cache(60, 10)) { assertTrue(cache.isAccepted(player, "main", List.of(document))); }
        clock.advance(40); primary.offline = true;
        try (var cache = cache(60, 10)) {
            assertTrue(cache.isAccepted(player, "main", List.of(document)));
            clock.advance(20); assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
        }
    }

    @Test void changedRequirementsScopeAndSourceCannotUseOldCache() throws Exception {
        try (var cache = cache(60, 10)) {
            assertTrue(cache.isAccepted(player, "main", List.of(document)));
            primary.offline = true;
            assertThrows(SQLException.class, () -> cache.isAccepted(player, "other", List.of(document)));
            assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(RemoteAcceptanceRepositoryTest.shown("rules", "v2", "example"))));
        }
        try (var cache = new CachedAcceptanceRepository(primary, config(60, 10), "different-primary", clock, ignored -> { })) {
            assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
        }
    }

    @Test void restoredPrimaryUsesANewCacheInsteadOfAcceptanceFromAfterTheBackup() throws Exception {
        var live = directory.resolve("primary.db");
        var backup = directory.resolve("backup.db");
        try (var repository = new SqliteAcceptanceRepository(live)) {
            assertFalse(repository.isAccepted(player, "main", List.of(document)));
        }
        Files.copy(live, backup);
        try (var cache = new CachedAcceptanceRepository(new SqliteAcceptanceRepository(live), config(60, 10),
                "same-primary-address", clock, ignored -> { })) {
            cache.grant(player, "main", List.of(document), UUID.randomUUID(), clock.instant(), "in-game");
            assertTrue(cache.isAccepted(player, "main", List.of(document)));
        }
        var restored = directory.resolve("restored.db");
        Files.copy(backup, restored);
        clock.advance(10);
        // An unchanged source address cannot reveal a restored backup to a still-fresh cache.
        try (var oldCache = new CachedAcceptanceRepository(new SqliteAcceptanceRepository(restored), config(60, 10),
                "same-primary-address", clock, ignored -> { })) {
            assertTrue(oldCache.isAccepted(player, "main", List.of(document)));
        }
        var replacement = new StorageConfig.Cache(true, directory.resolve("replacement-cache.db"), 60, 10);
        try (var newCache = new CachedAcceptanceRepository(new SqliteAcceptanceRepository(restored), replacement,
                "same-primary-address", clock, ignored -> { })) {
            assertFalse(newCache.isAccepted(player, "main", List.of(document)));
            assertFalse(newCache.isAcceptedAuthoritatively(player, "main", List.of(document)));
        }
    }

    @Test void successfulOrFailedResetInvalidatesLocalAcceptance() throws Exception {
        try (var cache = cache(60, 10)) {
            cache.isAccepted(player, "main", List.of(document));
            primary.offline = true;
            assertThrows(SQLException.class, () -> cache.withdraw(player, "main", List.of("rules"), UUID.randomUUID(), clock.instant(), "reset"));
            assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
            primary.offline = false; primary.accepted = true;
            cache.isAccepted(player, "main", List.of(document));
            cache.withdraw(player, "main", List.of("rules"), UUID.randomUUID(), clock.instant(), "reset");
            assertFalse(cache.isAccepted(player, "main", List.of(document)));
        }
    }

    @Test void grantDoesNotPopulateCacheAndStatusAlwaysReadsPrimary() throws Exception {
        try (var cache = cache(60, 10)) {
            cache.grant(player, "main", List.of(document), UUID.randomUUID(), clock.instant(), "in-game");
            primary.offline = true;
            assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
            primary.offline = false; cache.isAccepted(player, "main", List.of(document)); primary.accepted = false;
            assertFalse(cache.isAcceptedAuthoritatively(player, "main", List.of(document)));
            primary.offline = true; assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
        }
    }

    @Test void backwardClockAndZeroFreshnessRecheckPrimary() throws Exception {
        try (var cache = cache(60, 10)) {
            cache.isAccepted(player, "main", List.of(document)); primary.offline = true; clock.advance(-1);
            assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
        }
        primary.offline = false;
        try (var cache = cache(0, 10)) {
            cache.isAccepted(player, "main", List.of(document)); primary.offline = true;
            assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
        }
    }

    @Test void corruptCacheUsesPrimaryAndNeverBlocksAConfirmedGrant() throws Exception {
        Files.writeString(directory.resolve("cache.db"), "not a sqlite database");
        var warnings = new ArrayList<String>();
        try (var cache = new CachedAcceptanceRepository(primary, config(60, 10), "source", clock, warnings::add)) {
            cache.grant(player, "main", List.of(document), UUID.randomUUID(), clock.instant(), "in-game");
            assertTrue(cache.isAccepted(player, "main", List.of(document)));
            assertEquals(1, warnings.size()); primary.offline = true;
            assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
        }
    }

    @Test void capacityEvictsOldestAndSecondOwnerCannotReadCache() throws Exception {
        try (var first = cache(60, 1)) {
            first.isAccepted(player, "main", List.of(document)); clock.advance(1);
            var newer = UUID.randomUUID(); first.isAccepted(newer, "main", List.of(document)); primary.offline = true;
            assertThrows(SQLException.class, () -> first.isAccepted(player, "main", List.of(document)));
            assertTrue(first.isAccepted(newer, "main", List.of(document)));
            try (var second = cache(60, 1)) { assertThrows(SQLException.class, () -> second.isAccepted(newer, "main", List.of(document))); }
        }
    }

    @Test void slowPrimaryReplyDoesNotCreateAnAlreadyStaleEntry() throws Exception {
        primary.onRead = () -> clock.advance(61);
        try (var cache = cache(60, 10)) {
            assertTrue(cache.isAccepted(player, "main", List.of(document))); primary.offline = true;
            assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
        }
    }

    @Test void failedCacheInvalidationDisablesCacheWithoutUndoingPrimaryCommit() throws Exception {
        try (var cache = cache(60, 10)) {
            cache.isAccepted(player, "main", List.of(document));
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cache.db")); var statement = connection.createStatement()) {
                statement.execute("DROP TABLE cg_cached_checks");
            }
            assertDoesNotThrow(() -> cache.grant(player, "main", List.of(document), UUID.randomUUID(), clock.instant(), "in-game"));
            primary.offline = true;
            assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
        }
    }

    @Test void futureCacheTimestampIsNotTrusted() throws Exception {
        try (var cache = cache(60, 10)) {
            cache.isAccepted(player, "main", List.of(document));
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cache.db")); var statement = connection.prepareStatement("UPDATE cg_cached_checks SET verified_at=?")) {
                statement.setLong(1, clock.millis() + 5000); statement.executeUpdate();
            }
            primary.offline = true;
            assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
        }
    }

    @Test void resetCannotRaceAnInFlightPositiveCacheFill() throws Exception {
        var reading = new java.util.concurrent.CountDownLatch(1);
        var releaseRead = new java.util.concurrent.CountDownLatch(1);
        primary.onRead = () -> {
            reading.countDown();
            try { assertTrue(releaseRead.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        };
        try (var cache = cache(60, 10); var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var read = executor.submit(() -> cache.isAccepted(player, "main", List.of(document)));
            assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var reset = executor.submit(() -> { cache.withdraw(player, "main", List.of("rules"), UUID.randomUUID(), clock.instant(), "reset"); return true; });
            releaseRead.countDown();
            assertTrue(read.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(reset.get(5, java.util.concurrent.TimeUnit.SECONDS));
            primary.offline = true;
            assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
        } finally { releaseRead.countDown(); }
    }

    @Test void uncleanShutdownMarkerDiscardsPersistedAcceptance() throws Exception {
        try (var cache = cache(60, 10)) { cache.isAccepted(player, "main", List.of(document)); }
        Files.write(directory.resolve("cache.db.lock"), new byte[]{1});
        primary.offline = true;
        try (var cache = cache(60, 10)) {
            assertThrows(SQLException.class, () -> cache.isAccepted(player, "main", List.of(document)));
        }
    }

    static final class MutableClock extends Clock {
        Instant value = Instant.parse("2026-09-12T00:00:00Z");
        void advance(long seconds) { value = value.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
    static final class Primary implements AcceptanceRepository {
        boolean accepted = true, offline;
        int reads;
        Runnable onRead = () -> { };
        void available() throws SQLException { if (offline) throw new SQLException("Simulated unavailable primary"); }
        @Override public boolean isAccepted(UUID player, String scope, Collection<ShownDocument> documents) throws SQLException { reads++; available(); onRead.run(); return accepted; }
        @Override public void validateRevisions(String scope, Collection<ShownDocument> documents) throws SQLException { available(); }
        @Override public void grant(UUID player, String scope, List<ShownDocument> documents, UUID request, Instant at, String method) throws SQLException { available(); accepted = true; }
        @Override public void withdraw(UUID player, String scope, Collection<String> documents, UUID request, Instant at, String method) throws SQLException { available(); accepted = false; }
    }
}
