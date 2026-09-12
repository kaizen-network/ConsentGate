package io.github.consentgate.core.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public record RemoteStorageConfig(String host, int port, String database, String username, String password,
                                  String sslMode, Path serverCertificate, int connectTimeoutMillis, int socketTimeoutMillis) {
    public RemoteStorageConfig {
        Objects.requireNonNull(host); Objects.requireNonNull(database); Objects.requireNonNull(username);
        Objects.requireNonNull(password); Objects.requireNonNull(sslMode);
        if (!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}|\\[[0-9A-Fa-f:]{2,45}\\]")) throw new IllegalArgumentException("Invalid remote host");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid remote port");
        if (!database.matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid remote database name");
        if (username.isEmpty() || username.length() > 80 || password.length() > 1024) throw new IllegalArgumentException("Invalid remote credentials");
        if (!Set.of("verify-full", "verify-ca", "disable").contains(sslMode)) throw new IllegalArgumentException("Invalid remote ssl-mode");
        if (connectTimeoutMillis < 100 || connectTimeoutMillis > 30000 || socketTimeoutMillis < 100 || socketTimeoutMillis > 30000) {
            throw new IllegalArgumentException("Remote timeouts must be between 100 and 30000 milliseconds");
        }
        if (serverCertificate != null) serverCertificate = serverCertificate.toAbsolutePath().normalize();
    }

    @Override public String toString() { return "RemoteStorageConfig[connection details redacted]"; }
}
