package io.github.consentgate.core.runtime;

import io.github.consentgate.core.admission.AdmissionService;
import io.github.consentgate.core.config.ConfigLoadException;
import io.github.consentgate.core.config.ConfigLoader;
import io.github.consentgate.core.config.ConsentGateConfig;
import io.github.consentgate.core.document.DocumentCatalog;
import io.github.consentgate.core.document.DocumentLoadException;
import io.github.consentgate.core.document.DocumentLoader;
import io.github.consentgate.core.storage.SqliteAcceptanceRepository;

import java.nio.file.Path;
import java.sql.SQLException;

public final class RuntimeLoader {
    public record Prepared(ConsentGateConfig config, DocumentCatalog catalog) { }

    public Prepared prepare(Path dataDirectory) throws ConfigLoadException, DocumentLoadException {
        Path root = dataDirectory.toAbsolutePath().normalize();
        var config = new ConfigLoader().load(root, root.resolve("config.yml"));
        if (!config.enabled()) return new Prepared(config, new DocumentCatalog(java.util.List.of()));
        var catalog = new DocumentLoader().loadDirectory(config.documentsDirectory());
        if (catalog.required().isEmpty()) throw new IllegalArgumentException("An enabled gate needs at least one required document");
        if (config.languageSelector().enabled()) {
            for (var document : catalog.required()) {
                for (String locale : config.languageSelector().options().keySet()) {
                    if (!document.translations().containsKey(locale)) {
                        throw new IllegalArgumentException("Language selector locale " + locale + " is missing from " + document.id());
                    }
                }
            }
        }
        return new Prepared(config, catalog);
    }

    public ConsentGateRuntime load(Path dataDirectory) throws ConfigLoadException, DocumentLoadException, SQLException {
        var prepared = prepare(dataDirectory);
        var config = prepared.config();
        if (!config.enabled()) return new ConsentGateRuntime(config, null);
        var repository = new SqliteAcceptanceRepository(config.sqliteFile());
        try {
            return new ConsentGateRuntime(config, new AdmissionService(config, prepared.catalog(), repository));
        } catch (RuntimeException ex) {
            try { repository.close(); }
            catch (SQLException closeFailure) { ex.addSuppressed(closeFailure); }
            throw ex;
        }
    }
}
