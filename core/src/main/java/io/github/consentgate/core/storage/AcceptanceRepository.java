package io.github.consentgate.core.storage;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface AcceptanceRepository extends AutoCloseable {
    /** Returns matching document IDs from one primary read, or one complete fresh cached check. */
    Set<String> acceptedDocuments(UUID playerId, String scope, AcceptanceRequirements required) throws SQLException;

    default Set<String> acceptedDocumentsAuthoritatively(UUID playerId, String scope, AcceptanceRequirements required) throws SQLException {
        return acceptedDocuments(playerId, scope, required);
    }

    default boolean isAccepted(UUID playerId, String scope, Collection<ShownDocument> required) throws SQLException {
        var requirements = AcceptanceRequirements.exact(required);
        return acceptedDocuments(playerId, scope, requirements).containsAll(requirements.documentIds());
    }

    default boolean isAcceptedAuthoritatively(UUID playerId, String scope, Collection<ShownDocument> required) throws SQLException {
        var requirements = AcceptanceRequirements.exact(required);
        return acceptedDocumentsAuthoritatively(playerId, scope, requirements).containsAll(requirements.documentIds());
    }

    void validateRevisions(String scope, Collection<ShownDocument> documents) throws SQLException;

    void grant(UUID playerId, String scope, List<ShownDocument> shown, UUID requestId,
               Instant decidedAt, String method) throws SQLException;

    void withdraw(UUID playerId, String scope, Collection<String> documentIds, UUID requestId,
                  Instant decidedAt, String method) throws SQLException;

    @Override default void close() throws SQLException { }
}
