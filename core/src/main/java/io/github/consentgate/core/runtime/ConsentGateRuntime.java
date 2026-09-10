package io.github.consentgate.core.runtime;

import io.github.consentgate.core.admission.AdmissionService;
import io.github.consentgate.core.config.ConsentGateConfig;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public final class ConsentGateRuntime implements AutoCloseable {
    private final ConsentGateConfig config;
    private final AdmissionService admissionService;

    ConsentGateRuntime(ConsentGateConfig config, AdmissionService admissionService) {
        this.config = Objects.requireNonNull(config, "config");
        this.admissionService = admissionService;
        if (config.enabled() != (admissionService != null)) {
            throw new IllegalArgumentException("Enabled state and admission service do not match");
        }
    }

    public ConsentGateConfig config() { return config; }
    public boolean enabled() { return config.enabled(); }
    public Optional<AdmissionService> admissionService() { return Optional.ofNullable(admissionService); }

    @Override public void close() throws SQLException {
        if (admissionService != null) admissionService.close();
    }
}
