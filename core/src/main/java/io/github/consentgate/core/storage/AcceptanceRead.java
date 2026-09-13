package io.github.consentgate.core.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Reads at most one current row per required document, using one query on either database. */
final class AcceptanceRead {
    private AcceptanceRead() { }

    static Set<String> accepted(Connection connection, UUID player, String scope, AcceptanceRequirements requirements)
            throws SQLException {
        if (requirements.documentIds().isEmpty()) return Set.of();
        String placeholders = String.join(",", Collections.nCopies(requirements.documentIds().size(), "?"));
        try (var query = connection.prepareStatement("SELECT document_id,version,content_hash,decision FROM cg_acceptance_state "
                + "WHERE player_uuid=? AND scope=? AND document_id IN (" + placeholders + ")")) {
            query.setString(1, player.toString());
            query.setString(2, scope);
            int index = 3;
            for (String id : requirements.documentIds()) query.setString(index++, id);
            var accepted = new HashSet<String>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    if ("granted".equals(rows.getString(4)) && requirements.matches(id, rows.getString(2), rows.getString(3))) {
                        accepted.add(id);
                    }
                }
            }
            return Set.copyOf(accepted);
        }
    }
}
