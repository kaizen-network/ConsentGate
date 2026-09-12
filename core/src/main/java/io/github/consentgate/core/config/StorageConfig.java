package io.github.consentgate.core.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public record StorageConfig(String type, Path sqliteFile, RemoteStorageConfig remote, Cache cache) {
    public record Cache(boolean enabled, Path file, int freshnessSeconds, int maxEntries) {
        public Cache {
            file = Objects.requireNonNull(file).toAbsolutePath().normalize();
            if (freshnessSeconds < 0 || freshnessSeconds > 300) throw new IllegalArgumentException("Cache freshness must be between 0 and 300 seconds");
            if (maxEntries < 1 || maxEntries > 1000000) throw new IllegalArgumentException("Cache capacity must be between 1 and 1000000");
        }
    }

    public StorageConfig {
        if (!Set.of("sqlite", "mysql", "mariadb").contains(type)) throw new IllegalArgumentException("Unsupported storage type");
        sqliteFile = Objects.requireNonNull(sqliteFile).toAbsolutePath().normalize();
        cache = Objects.requireNonNull(cache);
        if (!type.equals("sqlite")) Objects.requireNonNull(remote, "Remote storage settings are required");
        if (!type.equals("sqlite") && cache.enabled() && sqliteFile.equals(cache.file())) throw new IllegalArgumentException("Cache and primary SQLite paths must differ");
    }

    public static StorageConfig sqlite(Path file) {
        return new StorageConfig("sqlite", file, null, new Cache(false, file.resolveSibling("remote-cache.db"), 60, 100000));
    }
}
