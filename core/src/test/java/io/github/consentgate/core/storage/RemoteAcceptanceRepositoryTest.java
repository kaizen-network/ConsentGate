package io.github.consentgate.core.storage;

import io.github.consentgate.core.config.RemoteStorageConfig;
import io.github.consentgate.core.config.StorageConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Tag("remote-database")
class RemoteAcceptanceRepositoryTest {
    static RemoteStorageConfig settings;
    @TempDir Path directory;
    final String scope = "it_" + UUID.randomUUID().toString().replace("-", "");
    final UUID player = UUID.randomUUID();
    final Instant at = Instant.now();
    final ShownDocument document = shown("rules", "v1", "example");

    @BeforeAll static void setup() throws Exception {
        assertEquals("true", System.getenv("CG_TEST_DB_ALLOW_WRITES"));
        String database = System.getenv("CG_TEST_DB_DATABASE");
        assertTrue(database != null && database.startsWith("consentgate_test_"));
        String certificate = System.getenv("CG_TEST_DB_SERVER_CERTIFICATE");
        settings = new RemoteStorageConfig(System.getenv("CG_TEST_DB_HOST"), Integer.parseInt(System.getenv("CG_TEST_DB_PORT")), database,
                System.getenv("CG_TEST_DB_USERNAME"), System.getenv("CG_TEST_DB_PASSWORD"),
                Objects.requireNonNullElse(System.getenv("CG_TEST_DB_SSL_MODE"), "verify-full"),
                certificate == null || certificate.isBlank() ? null : Path.of(certificate), 3000, 5000);
        try (var connection = RemoteAcceptanceRepository.openConnection(settings); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()")) {
                rows.next();
                if (rows.getInt(1) == 0) {
                    String sql;
                    try (var input = RemoteAcceptanceRepositoryTest.class.getResourceAsStream("/db/mysql-v1.sql")) {
                        sql = new String(Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                    for (String command : sql.replaceAll("(?m)^--[^\\r\\n]*", "").split(";")) {
                        if (!command.isBlank()) statement.execute(command);
                    }
                }
            }
            try (var rows = statement.executeQuery("SELECT VERSION()")) { rows.next(); System.out.println("Remote test engine: " + rows.getString(1)); }
        }
    }

    static ShownDocument shown(String id, String version, String text) {
        try {
            String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return new ShownDocument(id, version, "en-US", hash, "Example document", text);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    RemoteAcceptanceRepository repository() throws SQLException { return new RemoteAcceptanceRepository(settings); }

    @Test void grantIsAtomicIdempotentAndShared() throws Exception {
        var request = UUID.randomUUID();
        var other = shown("privacy", "V1", "Data information: \uD83C\uDF0D");
        try (var first = repository(); var second = repository()) {
            assertFalse(first.isAccepted(player, scope, List.of(document, other)));
            first.grant(player, scope, List.of(document, other), request, at, "in-game");
            second.grant(player, scope, List.of(other, document), request, at, "in-game");
            assertTrue(second.isAccepted(player, scope, List.of(document, other)));
            assertFalse(second.isAccepted(player, scope, List.of(shown("privacy", "v1", other.contentSnapshot()))));
            assertEquals(1, count("cg_audit_events"));
            assertEquals(2, count("cg_acceptance_events"));
            assertThrows(SQLException.class, () -> second.grant(player, scope, List.of(document), request, at, "in-game"));
        }
    }

    @Test void conflictingRevisionRollsBackAllDocuments() throws Exception {
        try (var repository = repository()) {
            repository.grant(player, scope, List.of(document), UUID.randomUUID(), at, "in-game");
            var conflict = shown("rules", "v1", "changed");
            var extra = shown("privacy", "v1", "data");
            assertThrows(SQLException.class, () -> repository.grant(player, scope, List.of(extra, conflict), UUID.randomUUID(), at.plusSeconds(1), "in-game"));
            assertFalse(repository.isAccepted(player, scope, List.of(extra)));
            assertEquals(1, count("cg_audit_events"));
            assertEquals(1, count("cg_document_revisions"));
            assertThrows(SQLException.class, () -> repository.validateRevisions(scope, List.of(conflict)));
        }
    }

    @Test void withdrawalBlocksReplayedAndOlderGrantsIncludingInitiallyMissingState() throws Exception {
        try (var first = repository(); var second = repository()) {
            var request = UUID.randomUUID();
            first.grant(player, scope, List.of(document), request, at, "in-game");
            second.withdraw(player, scope, List.of("rules", "privacy"), UUID.randomUUID(), at.plusSeconds(2), "admin-reset");
            assertThrows(SQLException.class, () -> first.grant(player, scope, List.of(document), request, at, "in-game"));
            assertThrows(SQLException.class, () -> first.grant(player, scope, List.of(shown("privacy", "v1", "data")), UUID.randomUUID(), at.plusSeconds(1), "in-game"));
            assertThrows(SQLException.class, () -> first.grant(player, scope, List.of(document), UUID.randomUUID(), at.plusSeconds(2), "in-game"));
            assertFalse(first.isAccepted(player, scope, List.of(document)));
            first.grant(player, scope, List.of(document), UUID.randomUUID(), at.plusSeconds(3), "in-game");
            assertTrue(second.isAccepted(player, scope, List.of(document)));
        }
    }

    @Test void concurrentSameRequestCommitsOnceAcrossInstances() throws Exception {
        var request = UUID.randomUUID();
        try (var first = repository(); var second = repository(); var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); first.grant(player, scope, List.of(document), request, at, "in-game"); return true; });
            var b = executor.submit(() -> { start.await(); second.grant(player, scope, List.of(document), request, at, "in-game"); return true; });
            start.countDown();
            assertTrue(a.get(20, TimeUnit.SECONDS)); assertTrue(b.get(20, TimeUnit.SECONDS));
            assertEquals(1, count("cg_acceptance_events")); assertEquals(1, count("cg_audit_events"));
        }
    }

    @Test void concurrentNewerWithdrawalWinsRegardlessOfLockOrder() throws Exception {
        try (var first = repository(); var second = repository(); var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); try { first.grant(player, scope, List.of(document), UUID.randomUUID(), at, "in-game"); } catch (SQLException stale) { assertTrue(stale.getMessage().contains("older")); } return true; });
            var b = executor.submit(() -> { start.await(); second.withdraw(player, scope, List.of("rules"), UUID.randomUUID(), at.plusSeconds(1), "admin-reset"); return true; });
            start.countDown(); a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
            assertFalse(first.isAccepted(player, scope, List.of(document)));
        }
    }

    @Test void outageAndRecoveryUseOnlyFreshCacheAndConfirmedCommits() throws Exception {
        var offline = new AtomicBoolean(false);
        var clock = new CachedAcceptanceRepositoryTest.MutableClock();
        var primary = new RemoteAcceptanceRepository(() -> { if (offline.get()) throw new SQLException("Simulated unavailable connection"); return RemoteAcceptanceRepository.openConnection(settings); });
        try (var cached = new CachedAcceptanceRepository(primary, new StorageConfig.Cache(true, directory.resolve("cache.db"), 60, 100), "integration", clock, ignored -> { }); var other = repository()) {
            cached.grant(player, scope, List.of(document), UUID.randomUUID(), at, "in-game");
            assertTrue(cached.isAccepted(player, scope, List.of(document)));
            other.withdraw(player, scope, List.of("rules"), UUID.randomUUID(), at.plusSeconds(1), "admin-reset");
            offline.set(true);
            assertTrue(cached.isAccepted(player, scope, List.of(document)));
            assertThrows(SQLException.class, () -> cached.isAccepted(UUID.randomUUID(), scope, List.of(document)));
            assertThrows(SQLException.class, () -> cached.isAcceptedAuthoritatively(player, scope, List.of(document)));
            clock.advance(60);
            assertThrows(SQLException.class, () -> cached.isAccepted(player, scope, List.of(document)));
            assertThrows(SQLException.class, () -> cached.grant(player, scope, List.of(document), UUID.randomUUID(), at.plusSeconds(2), "in-game"));
            offline.set(false);
            assertFalse(cached.isAccepted(player, scope, List.of(document)));
        }
    }

    @Test void lostCommitAcknowledgementFailsClosedAndReplayDoesNotDuplicate() throws Exception {
        var loseReply = new AtomicBoolean(false);
        var primary = new RemoteAcceptanceRepository(() -> {
            Connection actual = RemoteAcceptanceRepository.openConnection(settings);
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(actual, args);
                    if (method.getName().equals("commit") && loseReply.compareAndSet(true, false)) throw new SQLException("Simulated lost commit reply");
                    return result;
                } catch (java.lang.reflect.InvocationTargetException ex) { throw ex.getCause(); }
            });
        });
        var request = UUID.randomUUID();
        loseReply.set(true);
        try (primary) {
            assertThrows(SQLException.class, () -> primary.grant(player, scope, List.of(document), request, at, "in-game"));
            assertTrue(primary.isAccepted(player, scope, List.of(document)));
            primary.grant(player, scope, List.of(document), request, at, "in-game");
            assertEquals(1, count("cg_audit_events")); assertEquals(1, count("cg_acceptance_events"));
        }
    }

    @Test void primaryConstructorDoesNotAcceptAnUnavailableDatabase() {
        assertThrows(SQLException.class, () -> new RemoteAcceptanceRepository(() -> { throw new SQLException("Simulated outage"); }));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CG_TEST_DB_SOCKET_FAULTS", matches = "true")
    void socketLossBeforeCommitRollsBackTheWholeGrant() throws Exception {
        try (var proxy = new SqlFaultProxy(settings.host(), settings.port(), SqlFaultProxy.Fault.BEFORE_COMMIT);
             var interrupted = new RemoteAcceptanceRepository(through(proxy)); var primary = repository()) {
            proxy.arm();
            assertThrows(SQLException.class, () -> interrupted.grant(player, scope, List.of(document), UUID.randomUUID(), at, "in-game"));
            assertTrue(proxy.wasCut());
            assertFalse(primary.isAccepted(player, scope, List.of(document)));
            assertEquals(0, count("cg_audit_events"));
            assertEquals(0, count("cg_acceptance_events"));
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CG_TEST_DB_SOCKET_FAULTS", matches = "true")
    void lostCommitReplyOnTheSocketDeniesTheAttemptAndKeepsOneDurableGrant() throws Exception {
        var request = UUID.randomUUID();
        try (var proxy = new SqlFaultProxy(settings.host(), settings.port(), SqlFaultProxy.Fault.COMMIT_REPLY);
             var interrupted = new RemoteAcceptanceRepository(through(proxy)); var primary = repository()) {
            proxy.arm();
            assertThrows(SQLException.class, () -> interrupted.grant(player, scope, List.of(document), request, at, "in-game"));
            assertTrue(proxy.wasCut());
            assertTrue(proxy.sawCommittedReply(), "The server must confirm commit to the proxy before the reply is dropped");
            assertTrue(primary.isAccepted(player, scope, List.of(document)));
            primary.grant(player, scope, List.of(document), request, at, "in-game");
            assertEquals(1, count("cg_audit_events"));
            assertEquals(1, count("cg_acceptance_events"));
        }
    }

    private RemoteStorageConfig through(SqlFaultProxy proxy) {
        return new RemoteStorageConfig(java.net.InetAddress.getLoopbackAddress().getHostAddress(), proxy.port(), settings.database(),
                settings.username(), settings.password(), "disable", null, 3000, 5000);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CG_TEST_DB_SERVER_CERTIFICATE", matches = ".+")
    void verifiedTlsConnectionUsesEncryption() throws Exception {
        assertEquals("verify-full", settings.sslMode());
        try (var connection = RemoteAcceptanceRepository.openConnection(settings);
             var query = connection.createStatement(); var rows = query.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            assertTrue(rows.next());
            assertFalse(rows.getString(2).isBlank(), "The database connection must negotiate a TLS cipher");
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CG_TEST_DB_UNTRUSTED_CERTIFICATE", matches = ".+")
    void untrustedCertificateRejectsTheConnectionWithoutChangingRecords() throws Exception {
        var untrusted = new RemoteStorageConfig(settings.host(), settings.port(), settings.database(), settings.username(),
                settings.password(), "verify-ca", Path.of(System.getenv("CG_TEST_DB_UNTRUSTED_CERTIFICATE")), 3000, 5000);
        assertThrows(SQLException.class, () -> RemoteAcceptanceRepository.openConnection(untrusted));
        try (var primary = repository()) { assertFalse(primary.isAccepted(player, scope, List.of(document))); }
        assertEquals(0, count("cg_audit_events"));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "CG_TEST_DB_WRONG_HOST", matches = ".+")
    void hostnameMismatchFailsOnlyWhenHostnameVerificationIsRequired() throws Exception {
        String alias = System.getenv("CG_TEST_DB_WRONG_HOST");
        var trusted = new RemoteStorageConfig(alias, settings.port(), settings.database(), settings.username(), settings.password(),
                "verify-ca", settings.serverCertificate(), 3000, 5000);
        try (var ignored = RemoteAcceptanceRepository.openConnection(trusted)) { }
        var mismatch = new RemoteStorageConfig(alias, settings.port(), settings.database(), settings.username(), settings.password(),
                "verify-full", settings.serverCertificate(), 3000, 5000);
        assertThrows(SQLException.class, () -> RemoteAcceptanceRepository.openConnection(mismatch));
        try (var primary = repository()) { assertFalse(primary.isAccepted(player, scope, List.of(document))); }
    }

    @Test void bothIsolatedPlatformDriversCanConnectAndReadTheRealDatabase() throws Exception {
        for (String platform : List.of("paper", "velocity")) {
            var artifact = Path.of(System.getProperty("consentgate." + platform + "Artifact"));
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                var driver = (Driver) loader.loadClass("io.github.consentgate.internal.mariadb.Driver").getConstructor().newInstance();
                var properties = new Properties();
                properties.setProperty("user", settings.username()); properties.setProperty("password", settings.password());
                properties.setProperty("sslMode", settings.sslMode()); properties.setProperty("connectTimeout", "3000"); properties.setProperty("socketTimeout", "5000");
                if (settings.serverCertificate() != null) properties.setProperty("serverSslCert", settings.serverCertificate().toString());
                properties.setProperty("allowLocalInfile", "false");
                try (var connection = driver.connect("jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/" + settings.database(), properties);
                     var query = connection.createStatement(); var rows = query.executeQuery("SELECT version FROM cg_schema_history")) {
                    assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
                }
            }
        }
    }

    @Test void newerSchemaIsRejectedWithoutChangesAndOriginalSchemaCanReopen() throws Exception {
        try (var connection = RemoteAcceptanceRepository.openConnection(settings); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE cg_schema_history SET version=2 WHERE version=1");
            try {
                assertThrows(SQLException.class, this::repository);
                try (var rows = statement.executeQuery("SELECT version FROM cg_schema_history")) { rows.next(); assertEquals(2, rows.getInt(1)); }
            } finally { statement.executeUpdate("UPDATE cg_schema_history SET version=1 WHERE version=2"); }
        }
        try (var repository = repository()) { assertFalse(repository.isAccepted(player, scope, List.of(document))); }
    }

    private int count(String table) throws SQLException {
        if (!Set.of("cg_audit_events", "cg_acceptance_events", "cg_document_revisions").contains(table)) throw new IllegalArgumentException();
        try (var connection = RemoteAcceptanceRepository.openConnection(settings); var query = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE scope=?")) {
            query.setString(1, scope); try (var rows = query.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }
}
