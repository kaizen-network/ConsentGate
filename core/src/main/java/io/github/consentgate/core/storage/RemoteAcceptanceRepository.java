package io.github.consentgate.core.storage;

import io.github.consentgate.core.config.RemoteStorageConfig;
import java.sql.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/** One primary, one connection per operation, bounded by the platform's database workers. */
public final class RemoteAcceptanceRepository implements AcceptanceRepository {
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private final Connections connections;

    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }

    public RemoteAcceptanceRepository(RemoteStorageConfig config) throws SQLException {
        this(() -> openConnection(config));
    }

    public RemoteAcceptanceRepository(Connections connections) throws SQLException {
        this.connections = Objects.requireNonNull(connections);
        try (Connection connection = connections.open()) { validateSchema(connection); }
    }

    public static Connection openConnection(RemoteStorageConfig config) throws SQLException {
        var properties = new Properties();
        properties.setProperty("user", config.username());
        properties.setProperty("password", config.password());
        properties.setProperty("sslMode", config.sslMode());
        if (config.serverCertificate() != null) properties.setProperty("serverSslCert", config.serverCertificate().toString());
        properties.setProperty("connectTimeout", String.valueOf(config.connectTimeoutMillis()));
        properties.setProperty("socketTimeout", String.valueOf(config.socketTimeoutMillis()));
        properties.setProperty("allowLocalInfile", "false");
        properties.setProperty("allowMultiQueries", "false");
        properties.setProperty("transactionReplay", "false");
        properties.setProperty("maxQuerySizeToLog", "0");
        Connection connection = new org.mariadb.jdbc.Driver().connect(
                "jdbc:mariadb://" + config.host() + ":" + config.port() + "/" + config.database(), properties);
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try (var statement = connection.createStatement()) {
                statement.execute("SET SESSION innodb_lock_wait_timeout=5");
                statement.execute("SET SESSION sql_mode='STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION'");
            }
            return connection;
        } catch (SQLException | RuntimeException ex) {
            try { connection.close(); } catch (SQLException closing) { ex.addSuppressed(closing); }
            throw ex;
        }
    }

    private static void validateSchema(Connection connection) throws SQLException {
        try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT version FROM cg_schema_history ORDER BY version")) {
            if (!rows.next() || rows.getInt(1) != 1 || rows.next()) throw new SQLException("Unsupported remote schema; install the supplied mysql-v1.sql in an empty database");
        }
        for (String table : List.of("cg_schema_history", "cg_player_locks", "cg_document_revisions", "cg_acceptance_events", "cg_acceptance_state", "cg_audit_events")) {
            try (var query = prepare(connection, "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?", table);
                 var rows = query.executeQuery()) {
                if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString(1))) throw new SQLException("Remote schema requires InnoDB table " + table);
            }
        }
        for (String sql : List.of("SELECT player_uuid,scope FROM cg_player_locks LIMIT 0",
                "SELECT scope,document_id,version,locale,content_hash,title,content_snapshot,created_at FROM cg_document_revisions LIMIT 0",
                "SELECT event_id,player_uuid,scope,document_id,version,locale,content_hash,decision,decided_at,method FROM cg_acceptance_events LIMIT 0",
                "SELECT player_uuid,scope,document_id,version,locale,content_hash,decision,decided_at,event_id FROM cg_acceptance_state LIMIT 0",
                "SELECT request_id,player_uuid,scope,action,payload,decided_at,method FROM cg_audit_events LIMIT 0")) {
            try (var query = connection.createStatement(); var ignored = query.executeQuery(sql)) { }
        }
    }

    @Override public boolean isAccepted(UUID playerId, String scope, Collection<ShownDocument> required) throws SQLException {
        validatePlayer(playerId, scope);
        var documents = unique(required);
        if (documents.isEmpty()) return true;
        try (var connection = connections.open()) { return accepted(connection, playerId, scope, documents); }
    }

    private static boolean accepted(Connection connection, UUID player, String scope, Map<String, ShownDocument> documents) throws SQLException {
        String placeholders = String.join(",", Collections.nCopies(documents.size(), "?"));
        var args = new ArrayList<String>(List.of(player.toString(), scope));
        args.addAll(documents.keySet());
        try (var query = prepare(connection, "SELECT document_id,version,content_hash,decision FROM cg_acceptance_state WHERE player_uuid=? AND scope=? AND document_id IN (" + placeholders + ")", args.toArray(String[]::new));
             var rows = query.executeQuery()) {
            int count = 0;
            while (rows.next()) {
                var expected = documents.get(rows.getString(1));
                if (expected != null && expected.version().equals(rows.getString(2)) && expected.contentHash().equals(rows.getString(3))
                        && "granted".equals(rows.getString(4))) count++;
            }
            return count == documents.size();
        }
    }

    @Override public void validateRevisions(String scope, Collection<ShownDocument> documents) throws SQLException {
        validateScope(scope);
        try (var connection = connections.open()) {
            for (var document : documents) verifyRevision(connection, scope, document, false);
        }
    }

    @Override public void grant(UUID playerId, String scope, List<ShownDocument> shown, UUID requestId, Instant at, String method) throws SQLException {
        validateWrite(playerId, scope, requestId, at, method);
        var documents = unique(shown);
        if (documents.isEmpty()) throw new IllegalArgumentException("At least one document is required");
        transaction(playerId, scope, connection -> {
            if (audit(connection, playerId, scope, requestId, at, method, "acceptance_granted", payload(documents.values()))) {
                for (var document : documents.values()) {
                    execute(connection, "INSERT INTO cg_document_revisions(scope,document_id,version,locale,content_hash,title,content_snapshot,created_at) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE scope=scope",
                            scope, document.documentId(), document.version(), document.locale(), document.contentHash(), document.title(), document.contentSnapshot(), timestamp(at));
                    verifyRevision(connection, scope, document, true);
                    decision(connection, playerId, scope, document.documentId(), document.version(), document.locale(), document.contentHash(), "granted", requestId, at, method);
                }
            }
            if (!accepted(connection, playerId, scope, documents)) throw new SQLException("Acceptance request is older than the current decision; reconnect to review again");
        });
    }

    @Override public void withdraw(UUID playerId, String scope, Collection<String> documentIds, UUID requestId, Instant at, String method) throws SQLException {
        validateWrite(playerId, scope, requestId, at, method);
        var ids = new TreeSet<>(documentIds);
        if (ids.isEmpty() || ids.size() > 32) throw new IllegalArgumentException("Between 1 and 32 document IDs are required");
        for (String id : ids) if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid document ID");
        transaction(playerId, scope, connection -> {
            if (audit(connection, playerId, scope, requestId, at, method, "acceptance_withdrawn", String.join("\n", ids))) {
                for (String id : ids) {
                    String version = null, locale = null, hash = null;
                    try (var query = prepare(connection, "SELECT version,locale,content_hash FROM cg_acceptance_state WHERE player_uuid=? AND scope=? AND document_id=?", playerId.toString(), scope, id);
                         var rows = query.executeQuery()) {
                        if (rows.next()) { version = rows.getString(1); locale = rows.getString(2); hash = rows.getString(3); }
                    }
                    decision(connection, playerId, scope, id, version, locale, hash, "withdrawn", requestId, at, method);
                }
            }
            for (String id : ids) {
                try (var query = prepare(connection, "SELECT decision FROM cg_acceptance_state WHERE player_uuid=? AND scope=? AND document_id=?", playerId.toString(), scope, id);
                     var rows = query.executeQuery()) {
                    if (!rows.next() || !"withdrawn".equals(rows.getString(1))) {
                        throw new SQLException("Withdrawal request is older than the current decision; check host clocks and retry");
                    }
                }
            }
        });
    }

    private static void decision(Connection connection, UUID player, String scope, String document, String version, String locale,
                                 String hash, String decision, UUID request, Instant at, String method) throws SQLException {
        String event = request + ":" + document;
        execute(connection, "INSERT INTO cg_acceptance_events(event_id,player_uuid,scope,document_id,version,locale,content_hash,decision,decided_at,method) VALUES(?,?,?,?,?,?,?,?,?,?)",
                event, player.toString(), scope, document, version, locale, hash, decision, timestamp(at), method);
        try (var query = prepare(connection, "SELECT decided_at,decision FROM cg_acceptance_state WHERE player_uuid=? AND scope=? AND document_id=?", player.toString(), scope, document);
             var rows = query.executeQuery()) {
            if (rows.next()) {
                int order = at.compareTo(Instant.parse(rows.getString(1)));
                if (order < 0 || (order == 0 && "withdrawn".equals(rows.getString(2)) && decision.equals("granted"))) return;
            }
        }
        execute(connection, "INSERT INTO cg_acceptance_state(player_uuid,scope,document_id,version,locale,content_hash,decision,decided_at,event_id) VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                        + "version=?,locale=?,content_hash=?,decision=?,decided_at=?,event_id=?",
                player.toString(), scope, document, version, locale, hash, decision, timestamp(at), event,
                version, locale, hash, decision, timestamp(at), event);
    }

    private static boolean audit(Connection connection, UUID player, String scope, UUID request, Instant at, String method, String action, String payload) throws SQLException {
        try (var query = prepare(connection, "SELECT player_uuid,scope,action,payload,decided_at,method FROM cg_audit_events WHERE request_id=?", request.toString());
             var rows = query.executeQuery()) {
            if (rows.next()) {
                if (!player.toString().equals(rows.getString(1)) || !scope.equals(rows.getString(2)) || !action.equals(rows.getString(3))
                        || !payload.equals(rows.getString(4)) || !timestamp(at).equals(rows.getString(5)) || !method.equals(rows.getString(6))) {
                    throw new SQLException("Request ID was reused with different audit data");
                }
                return false;
            }
        }
        execute(connection, "INSERT INTO cg_audit_events(request_id,player_uuid,scope,action,payload,decided_at,method) VALUES(?,?,?,?,?,?,?)",
                request.toString(), player.toString(), scope, action, payload, timestamp(at), method);
        return true;
    }

    private static void verifyRevision(Connection connection, String scope, ShownDocument document, boolean mustExist) throws SQLException {
        try (var query = prepare(connection, "SELECT content_hash,content_snapshot FROM cg_document_revisions WHERE scope=? AND document_id=? AND version=? AND locale=?",
                scope, document.documentId(), document.version(), document.locale()); var rows = query.executeQuery()) {
            if (rows.next()) {
                if (!document.contentHash().equals(rows.getString(1)) || !document.contentSnapshot().equals(rows.getString(2))) throw new SQLException("Document content changed without a version bump: " + document.documentId());
            } else if (mustExist) throw new SQLException("Document revision was not registered");
        }
    }

    private void transaction(UUID player, String scope, Operation operation) throws SQLException {
        try (var connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                // This upsert obtains an exclusive row lock even for a player's first decision.
                execute(connection, "INSERT INTO cg_player_locks(player_uuid,scope) VALUES(?,?) ON DUPLICATE KEY UPDATE scope=scope", player.toString(), scope);
                operation.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException ex) {
                try { connection.rollback(); } catch (SQLException rollback) { ex.addSuppressed(rollback); }
                throw ex;
            }
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, String... values) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
            return statement;
        } catch (SQLException ex) { statement.close(); throw ex; }
    }

    private static void execute(Connection connection, String sql, String... values) throws SQLException {
        try (var statement = prepare(connection, sql, values)) { statement.executeUpdate(); }
    }

    private static Map<String, ShownDocument> unique(Collection<ShownDocument> documents) {
        var result = new TreeMap<String, ShownDocument>();
        for (var document : documents) {
            if (document.locale().length() > 64) throw new IllegalArgumentException("Locale exceeds 64 characters");
            var previous = result.putIfAbsent(document.documentId(), document);
            if (previous != null && !previous.equals(document)) throw new IllegalArgumentException("Conflicting document requirement");
        }
        if (result.size() > 32) throw new IllegalArgumentException("At most 32 documents are allowed");
        return result;
    }

    private static String payload(Collection<ShownDocument> documents) {
        var value = new StringBuilder();
        for (var document : documents) for (String field : List.of(document.documentId(), document.version(), document.locale(), document.contentHash())) {
            value.append(field.length()).append(':').append(field);
        }
        return value.toString();
    }

    private static String timestamp(Instant value) {
        if (value.isBefore(Instant.EPOCH) || value.isAfter(Instant.parse("9999-12-31T23:59:59.999999999Z"))) throw new IllegalArgumentException("Decision timestamp is out of range");
        return TIMESTAMP.format(value);
    }
    private static void validateScope(String scope) { if (!scope.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid scope"); }
    private static void validatePlayer(UUID player, String scope) { Objects.requireNonNull(player); validateScope(scope); }
    private static void validateWrite(UUID player, String scope, UUID request, Instant at, String method) {
        validatePlayer(player, scope); Objects.requireNonNull(request); timestamp(at);
        if (!method.matches("[a-z0-9][a-z0-9_.-]{0,31}")) throw new IllegalArgumentException("Invalid method");
    }
    @FunctionalInterface private interface Operation { void run(Connection connection) throws SQLException; }
}
