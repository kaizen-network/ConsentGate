package io.github.consentgate.core.storage;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AcceptanceRepository extends AutoCloseable {
    boolean isAccepted(UUID playerId, String scope, Collection<ShownDocument> required) throws SQLException;

    void grant(UUID playerId, String scope, List<ShownDocument> shown, UUID requestId,
               Instant decidedAt, String method) throws SQLException;

    void withdraw(UUID playerId, String scope, Collection<String> documentIds, UUID requestId,
                  Instant decidedAt, String method) throws SQLException;

    @Override default void close() throws SQLException { }
}
