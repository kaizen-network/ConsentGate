package io.github.consentgate.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class SqliteAcceptanceRepository implements AcceptanceRepository {
    private static final Pattern SCOPE = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    private static final Pattern METHOD = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,31}");
    private static final Pattern DOCUMENT_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private static final int SCHEMA_VERSION = 1;
    private final String jdbcUrl;

    public SqliteAcceptanceRepository(Path databaseFile) throws SQLException {
        loadDriver();
        Path file = databaseFile.toAbsolutePath().normalize();
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException ex) {
            throw new SQLException("Cannot create the SQLite directory", ex);
        }
        jdbcUrl = "jdbc:sqlite:" + file;
        initialize();
    }

    private static void loadDriver() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC", true, SqliteAcceptanceRepository.class.getClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new SQLException("SQLite JDBC driver is unavailable", ex);
        }
    }

    @Override
    public Set<String> acceptedDocuments(UUID playerId, String scope, AcceptanceRequirements required) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(required, "required");
        validateScope(scope);
        if (required.documentIds().isEmpty()) return Set.of();
        try (Connection connection = connection()) { return AcceptanceRead.accepted(connection, playerId, scope, required); }
    }

    private static boolean accepted(Connection connection, UUID playerId, String scope, Map<String, ShownDocument> expected) throws SQLException {
        return AcceptanceRead.accepted(connection, playerId, scope, AcceptanceRequirements.exact(expected.values()))
                .containsAll(expected.keySet());
    }

    @Override
    public void validateRevisions(String scope, Collection<ShownDocument> documents) throws SQLException {
        validateScope(scope);
        try (Connection connection = connection(); PreparedStatement query = connection.prepareStatement(
                "SELECT content_hash, content_snapshot FROM cg_document_revisions WHERE scope=? AND document_id=? AND version=? AND locale=?")) {
            for (var document : documents) {
                query.setString(1, scope);
                query.setString(2, document.documentId());
                query.setString(3, document.version());
                query.setString(4, document.locale());
                try (ResultSet row = query.executeQuery()) {
                    if (row.next() && (!document.contentHash().equals(row.getString(1))
                            || !document.contentSnapshot().equals(row.getString(2)))) {
                        throw new SQLException("Document content changed without a version bump: " + document.documentId());
                    }
                }
            }
        }
    }

    @Override
    public void grant(UUID playerId, String scope, List<ShownDocument> shown, UUID requestId,
                      Instant decidedAt, String method) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(shown, "shown");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(decidedAt, "decidedAt");
        validateScope(scope);
        validateMethod(method);
        Map<String, ShownDocument> unique = uniqueDocuments(shown);
        if (unique.isEmpty()) throw new IllegalArgumentException("At least one document is required");
        transaction(connection -> {
            boolean fresh = insertAudit(connection, requestId, playerId, scope, "acceptance_granted",
                    auditPayload(unique.values()), decidedAt, method);
            rejectWithdrawnRequest(connection, playerId, scope, unique.keySet(), decidedAt);
            if (fresh) {
                for (ShownDocument document : unique.values()) {
                    registerRevision(connection, scope, document, decidedAt);
                    String eventId = requestId + ":" + document.documentId();
                    insertEvent(connection, eventId, playerId, scope, document.documentId(), document.version(),
                            document.locale(), document.contentHash(), "granted", decidedAt, method);
                    updateState(connection, playerId, scope, document.documentId(), document.version(),
                            document.locale(), document.contentHash(), "granted", decidedAt, eventId);
                }
            }
            if (!accepted(connection, playerId, scope, unique)) throw new SQLException("Acceptance request is older than the current decision; reconnect to review again");
        });
    }

    @Override
    public void withdraw(UUID playerId, String scope, Collection<String> documentIds, UUID requestId,
                         Instant decidedAt, String method) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(documentIds, "documentIds");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(decidedAt, "decidedAt");
        validateScope(scope);
        validateMethod(method);
        Set<String> ids = Set.copyOf(documentIds);
        if (ids.isEmpty()) throw new IllegalArgumentException("At least one document id is required");
        if (ids.size() > 32) throw new IllegalArgumentException("At most 32 document ids are allowed");
        ids.forEach(id -> {
            if (!DOCUMENT_ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid document id: " + id);
        });
        transaction(connection -> {
            String payload = ids.stream().sorted().collect(java.util.stream.Collectors.joining("\n"));
            if (insertAudit(connection, requestId, playerId, scope, "acceptance_withdrawn", payload, decidedAt, method)) {
                for (String id : ids) {
                    CurrentState current = currentState(connection, playerId, scope, id);
                    if (current == null) continue;
                    String eventId = requestId + ":" + id;
                    insertEvent(connection, eventId, playerId, scope, id, current.version,
                            current.locale, current.contentHash, "withdrawn", decidedAt, method);
                    updateState(connection, playerId, scope, id, current.version,
                            current.locale, current.contentHash, "withdrawn", decidedAt, eventId);
                }
            }
            try (var query = connection.prepareStatement("SELECT document_id FROM cg_acceptance_state WHERE player_uuid=? AND scope=? AND decision='granted'")) {
                query.setString(1, playerId.toString()); query.setString(2, scope);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) if (ids.contains(rows.getString(1))) {
                        throw new SQLException("Withdrawal request is older than the current decision; check host clocks and retry");
                    }
                }
            }
        });
    }

    private static void rejectWithdrawnRequest(Connection connection, UUID player, String scope, Set<String> ids, Instant at) throws SQLException {
        // Version 1 already records resets for missing players in the audit table.
        try (var query = connection.prepareStatement("SELECT payload,decided_at FROM cg_audit_events WHERE player_uuid=? AND scope=? AND action='acceptance_withdrawn'")) {
            query.setString(1, player.toString()); query.setString(2, scope);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    Instant withdrawal;
                    try { withdrawal = Instant.parse(rows.getString(2)); }
                    catch (RuntimeException ex) { throw new SQLException("Invalid withdrawal timestamp", ex); }
                    if (!withdrawal.isBefore(at) && java.util.Arrays.stream(rows.getString(1).split("\n")).anyMatch(ids::contains)) {
                        throw new SQLException("Acceptance request is older than or equal to a withdrawal; reconnect to review again");
                    }
                }
            }
        }
    }

    private void initialize() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("CREATE TABLE IF NOT EXISTS cg_schema_history (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            Integer version;
            try (ResultSet rows = statement.executeQuery("SELECT MAX(version) FROM cg_schema_history")) {
                Object value = rows.next() ? rows.getObject(1) : null;
                version = value instanceof Number number ? number.intValue() : null;
            }
            if (version != null && version > SCHEMA_VERSION) {
                throw new SQLException("Database schema " + version + " is newer than supported schema " + SCHEMA_VERSION);
            }
            if (version == null) applyVersionOne(connection);
            else if (version < SCHEMA_VERSION) throw new SQLException("Missing database upgrade from schema " + version);
            statement.execute("CREATE INDEX IF NOT EXISTS cg_audit_events_player ON cg_audit_events(player_uuid,scope,action)");
        }
    }

    private static void applyVersionOne(Connection connection) throws SQLException {
        boolean original = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE cg_document_revisions (scope TEXT NOT NULL, document_id TEXT NOT NULL, "
                    + "version TEXT NOT NULL, locale TEXT NOT NULL, content_hash TEXT NOT NULL, title TEXT NOT NULL, "
                    + "content_snapshot TEXT NOT NULL, created_at TEXT NOT NULL, "
                    + "PRIMARY KEY(scope, document_id, version, locale))");
            statement.execute("CREATE TABLE cg_acceptance_events (event_id TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, "
                    + "scope TEXT NOT NULL, document_id TEXT NOT NULL, version TEXT NOT NULL, locale TEXT NOT NULL, "
                    + "content_hash TEXT NOT NULL, decision TEXT NOT NULL CHECK(decision IN ('granted','withdrawn')), "
                    + "decided_at TEXT NOT NULL, method TEXT NOT NULL, "
                    + "FOREIGN KEY(scope, document_id, version, locale) REFERENCES cg_document_revisions(scope, document_id, version, locale))");
            statement.execute("CREATE INDEX cg_acceptance_events_player ON cg_acceptance_events(player_uuid, scope, decided_at)");
            statement.execute("CREATE TABLE cg_acceptance_state (player_uuid TEXT NOT NULL, scope TEXT NOT NULL, "
                    + "document_id TEXT NOT NULL, version TEXT NOT NULL, locale TEXT NOT NULL, content_hash TEXT NOT NULL, "
                    + "decision TEXT NOT NULL CHECK(decision IN ('granted','withdrawn')), decided_at TEXT NOT NULL, "
                    + "event_id TEXT NOT NULL, PRIMARY KEY(player_uuid, scope, document_id), "
                    + "FOREIGN KEY(event_id) REFERENCES cg_acceptance_events(event_id))");
            statement.execute("CREATE TABLE cg_audit_events (request_id TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, "
                    + "scope TEXT NOT NULL, action TEXT NOT NULL, payload TEXT NOT NULL, decided_at TEXT NOT NULL, method TEXT NOT NULL)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cg_schema_history(version, applied_at) VALUES(?,?)")) {
                insert.setInt(1, SCHEMA_VERSION);
                insert.setString(2, timestamp(Instant.now()));
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(original);
        }
    }

    private void registerRevision(Connection connection, String scope, ShownDocument document, Instant createdAt)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO cg_document_revisions(scope, document_id, version, locale, content_hash, title, content_snapshot, created_at) VALUES(?,?,?,?,?,?,?,?)")) {
            insert.setString(1, scope); insert.setString(2, document.documentId()); insert.setString(3, document.version());
            insert.setString(4, document.locale()); insert.setString(5, document.contentHash()); insert.setString(6, document.title());
            insert.setString(7, document.contentSnapshot()); insert.setString(8, timestamp(createdAt));
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT content_hash, content_snapshot FROM cg_document_revisions WHERE scope=? AND document_id=? AND version=? AND locale=?")) {
            query.setString(1, scope); query.setString(2, document.documentId()); query.setString(3, document.version()); query.setString(4, document.locale());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || !document.contentHash().equals(row.getString(1))
                        || !document.contentSnapshot().equals(row.getString(2))) {
                    throw new SQLException("Document content changed without a version bump: " + document.documentId());
                }
            }
        }
    }

    private static void insertEvent(Connection connection, String eventId, UUID playerId, String scope,
                                    String documentId, String version, String locale, String contentHash,
                                    String decision, Instant at, String method) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO cg_acceptance_events(event_id,player_uuid,scope,document_id,version,locale,content_hash,decision,decided_at,method) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, eventId); statement.setString(2, playerId.toString()); statement.setString(3, scope);
            statement.setString(4, documentId); statement.setString(5, version); statement.setString(6, locale);
            statement.setString(7, contentHash); statement.setString(8, decision); statement.setString(9, timestamp(at));
            statement.setString(10, method);
            if (statement.executeUpdate() == 0) verifyExistingEvent(connection, eventId, playerId, scope, documentId,
                    version, locale, contentHash, decision, at, method);
        }
    }

    private static void verifyExistingEvent(Connection connection, String eventId, UUID playerId, String scope,
                                            String documentId, String version, String locale, String contentHash,
                                            String decision, Instant at, String method) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_uuid,scope,document_id,version,locale,content_hash,decision,decided_at,method FROM cg_acceptance_events WHERE event_id=?")) {
            query.setString(1, eventId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || !playerId.toString().equals(row.getString(1)) || !scope.equals(row.getString(2))
                        || !documentId.equals(row.getString(3)) || !version.equals(row.getString(4))
                        || !locale.equals(row.getString(5)) || !contentHash.equals(row.getString(6))
                        || !decision.equals(row.getString(7)) || !timestamp(at).equals(row.getString(8))
                        || !method.equals(row.getString(9))) {
                    throw new SQLException("Request id was reused with different acceptance data: " + eventId);
                }
            }
        }
    }

    private static void updateState(Connection connection, UUID playerId, String scope, String documentId,
                                    String version, String locale, String contentHash, String decision,
                                    Instant at, String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO cg_acceptance_state(player_uuid,scope,document_id,version,locale,content_hash,decision,decided_at,event_id) "
                        + "VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(player_uuid,scope,document_id) DO UPDATE SET "
                        + "version=excluded.version,locale=excluded.locale,content_hash=excluded.content_hash,decision=excluded.decision,decided_at=excluded.decided_at,event_id=excluded.event_id "
                        + "WHERE excluded.decided_at > cg_acceptance_state.decided_at OR (excluded.decided_at = cg_acceptance_state.decided_at "
                        + "AND (cg_acceptance_state.decision <> 'withdrawn' OR excluded.decision = 'withdrawn'))")) {
            statement.setString(1, playerId.toString()); statement.setString(2, scope); statement.setString(3, documentId);
            statement.setString(4, version); statement.setString(5, locale); statement.setString(6, contentHash);
            statement.setString(7, decision); statement.setString(8, timestamp(at)); statement.setString(9, eventId);
            statement.executeUpdate();
        }
    }

    private static CurrentState currentState(Connection connection, UUID playerId, String scope, String documentId)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT version,locale,content_hash FROM cg_acceptance_state WHERE player_uuid=? AND scope=? AND document_id=?")) {
            query.setString(1, playerId.toString()); query.setString(2, scope); query.setString(3, documentId);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? new CurrentState(row.getString(1), row.getString(2), row.getString(3)) : null;
            }
        }
    }

    private static boolean insertAudit(Connection connection, UUID requestId, UUID playerId, String scope,
                                       String action, String payload, Instant at, String method) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO cg_audit_events(request_id,player_uuid,scope,action,payload,decided_at,method) VALUES(?,?,?,?,?,?,?)")) {
            statement.setString(1, requestId.toString()); statement.setString(2, playerId.toString());
            statement.setString(3, scope); statement.setString(4, action); statement.setString(5, payload);
            statement.setString(6, timestamp(at)); statement.setString(7, method);
            if (statement.executeUpdate() == 0) {
                verifyAudit(connection, requestId, playerId, scope, action, payload, at, method);
                return false;
            }
            return true;
        }
    }

    private static void verifyAudit(Connection connection, UUID requestId, UUID playerId, String scope,
                                    String action, String payload, Instant at, String method) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_uuid,scope,action,payload,decided_at,method FROM cg_audit_events WHERE request_id=?")) {
            query.setString(1, requestId.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || !playerId.toString().equals(row.getString(1)) || !scope.equals(row.getString(2))
                        || !action.equals(row.getString(3)) || !payload.equals(row.getString(4))
                        || !timestamp(at).equals(row.getString(5)) || !method.equals(row.getString(6))) {
                    throw new SQLException("Request id was reused with different audit data: " + requestId);
                }
            }
        }
    }

    private static String auditPayload(Collection<ShownDocument> documents) {
        var payload = new StringBuilder();
        documents.stream().sorted(java.util.Comparator.comparing(ShownDocument::documentId)).forEach(document -> {
            auditField(payload, document.documentId());
            auditField(payload, document.version());
            auditField(payload, document.locale());
            auditField(payload, document.contentHash());
        });
        return payload.toString();
    }

    private static void auditField(StringBuilder payload, String value) {
        payload.append(value.length()).append(':').append(value);
    }

    private static String timestamp(Instant value) { return TIMESTAMP.format(value); }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            return connection;
        } catch (SQLException | RuntimeException ex) {
            try { connection.close(); }
            catch (SQLException closeFailure) { ex.addSuppressed(closeFailure); }
            throw ex;
        }
    }

    private void transaction(SqlOperation operation) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                operation.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    private static Map<String, ShownDocument> uniqueDocuments(Collection<ShownDocument> documents) {
        var result = new HashMap<String, ShownDocument>();
        for (ShownDocument document : documents) {
            Objects.requireNonNull(document, "document");
            ShownDocument previous = result.putIfAbsent(document.documentId(), document);
            if (previous != null && !previous.equals(document)) {
                throw new IllegalArgumentException("Conflicting document requirement: " + document.documentId());
            }
        }
        if (result.size() > 32) throw new IllegalArgumentException("At most 32 documents are allowed");
        return result;
    }

    private static void validateScope(String scope) {
        if (!SCOPE.matcher(scope).matches()) throw new IllegalArgumentException("Invalid scope: " + scope);
    }

    private static void validateMethod(String method) {
        if (!METHOD.matcher(method).matches()) throw new IllegalArgumentException("Invalid method: " + method);
    }

    @FunctionalInterface private interface SqlOperation { void run(Connection connection) throws SQLException; }
    private record CurrentState(String version, String locale, String contentHash) { }
}
