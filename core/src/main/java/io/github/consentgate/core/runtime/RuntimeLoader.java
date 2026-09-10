package io.github.consentgate.core.runtime;

import io.github.consentgate.core.admission.AdmissionService;
import io.github.consentgate.core.config.ConfigLoadException;
import io.github.consentgate.core.config.ConfigLoader;
import io.github.consentgate.core.document.DocumentLoadException;
import io.github.consentgate.core.document.DocumentLoader;
import io.github.consentgate.core.storage.SqliteAcceptanceRepository;

import java.nio.file.Path;
import java.sql.SQLException;

public final class RuntimeLoader {
    public ConsentGateRuntime load(Path dataDirectory) throws ConfigLoadException, DocumentLoadException, SQLException {
        Path root = dataDirectory.toAbsolutePath().normalize();
        var config = new ConfigLoader().load(root, root.resolve("config.yml"));
        if (!config.enabled()) return new ConsentGateRuntime(config, null);
        var catalog = new DocumentLoader().loadDirectory(config.documentsDirectory());
        if (catalog.required().isEmpty()) throw new IllegalArgumentException("An enabled gate needs at least one required document");
        var repository = new SqliteAcceptanceRepository(config.sqliteFile());
        try {
            return new ConsentGateRuntime(config, new AdmissionService(config, catalog, repository));
        } catch (RuntimeException ex) {
            try { repository.close(); }
            catch (SQLException closeFailure) { ex.addSuppressed(closeFailure); }
            throw ex;
        }
    }
}
